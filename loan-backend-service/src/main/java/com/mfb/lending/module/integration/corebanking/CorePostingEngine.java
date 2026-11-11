package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ErrorCode;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class CorePostingEngine {

    private final CoreBankingStore store;
    private final CoreBankingAdapter adapter;
    private final DisbursementLoanPort loans;
    private final IntegrationCircuitBreaker circuit;
    private final CoreBankingMetrics metrics;
    private final UnitOfWork unitOfWork;
    private final java.time.Clock clock;

    public CorePostingEngine(
            CoreBankingStore store,
            CoreBankingAdapter adapter,
            DisbursementLoanPort loans,
            IntegrationCircuitBreaker circuit,
            CoreBankingMetrics metrics,
            UnitOfWork unitOfWork,
            java.time.Clock clock) {
        this.store = store;
        this.adapter = adapter;
        this.loans = loans;
        this.circuit = circuit;
        this.metrics = metrics;
        this.unitOfWork = unitOfWork;
        this.clock = clock;
    }

    public CoreTransaction submitDisbursement(UUID id) {
        CoreTransaction current = store.require(id);
        if (current.state() != FinancialTransactionState.PENDING) {
            return current;
        }
        if (current.stage() == InstructionStage.NEW) {
            current = registerLoan(current);
            if (current.state() != FinancialTransactionState.PENDING) {
                return current;
            }
        }
        if (current.stage() != InstructionStage.LOAN_REGISTERED || !current.paymentUnsent()) {
            return current;
        }
        return sendPayment(current, true);
    }

    public CoreTransaction submitRepayment(UUID id) {
        CoreTransaction current = store.require(id);
        if (current.state() != FinancialTransactionState.PENDING || !current.paymentUnsent()) {
            return current;
        }
        return sendPayment(current, false);
    }

    public CoreTransaction submitReversal(UUID id) {
        CoreTransaction current = store.require(id);
        if (current.state() != FinancialTransactionState.PENDING || !current.paymentUnsent()) {
            return current;
        }
        ensureSendable();
        var claimed = unitOfWork.required(() -> store.claimPayment(id));
        if (claimed.isEmpty()) {
            return store.require(id);
        }
        CoreTransaction reversal = claimed.get();
        CoreTransaction original = store.require(reversal.relatedTransactionId());
        try {
            CoreBankingAdapter.AdapterResult<CoreBankingAdapter.PostingView> result = adapter.notifyReversal(new CoreBankingAdapter.ReversalCommand(
                    reversal.reference(),
                    reversal.idempotencyKey(),
                    original.reference(),
                    reversal.amount(),
                    reversal.currencyCode(),
                    reversal.correlationId()));
            CoreTransaction submitted = acceptSubmission(reversal, result);
            if (submitted.state() != FinancialTransactionState.SUBMITTED) {
                return submitted;
            }
            return verifyReversal(submitted, original);
        } catch (CoreTransportException ex) {
            return transportFailure(reversal, ex, false);
        }
    }

    public CoreTransaction enquire(UUID id) {
        CoreTransaction current = store.require(id);
        if (current.state().terminal() || current.state() == FinancialTransactionState.SUCCESSFUL) {
            return current;
        }
        if (current.kind() == FinancialTransactionKind.REVERSAL) {
            return verifyReversal(current, store.require(current.relatedTransactionId()));
        }
        if (current.kind() == FinancialTransactionKind.DISBURSEMENT && current.paymentUnsent() && current.loanAttempt() > 0) {
            return continueLoan(current);
        }
        return verifyPayment(current, current.kind() == FinancialTransactionKind.DISBURSEMENT);
    }

    public ReconciliationResult reconcile(UUID id) {
        CoreTransaction current = store.require(id);
        CoreBankingAdapter.AdapterResult<CoreBankingAdapter.PostingList> postings =
                adapter.transactionPostings(statusQuery(current));
        CoreBankingAdapter.AdapterResult<CoreBankingAdapter.PostingList> gl = adapter.glTransactions(statusQuery(current));
        BigDecimal actual = sum(gl.body());
        if (actual == null) {
            actual = sum(postings.body());
        }
        String itemStatus;
        FinancialEvent event = null;
        if (actual == null || postings.outcome() == CoreOutcome.NOT_FOUND) {
            itemStatus = "MISSING";
            if (current.state() == FinancialTransactionState.SUCCESSFUL) {
                event = FinancialEvent.MISMATCH;
            }
        } else if (actual.compareTo(current.amount()) == 0) {
            itemStatus = "MATCHED";
        } else {
            itemStatus = "MISMATCH";
            metrics.mismatch();
            if (current.state() == FinancialTransactionState.SUCCESSFUL || current.state() == FinancialTransactionState.SUBMITTED) {
                event = FinancialEvent.MISMATCH;
            }
        }
        FinancialEvent transition = event;
        String finalStatus = itemStatus;
        BigDecimal actualAmount = actual;
        return unitOfWork.required(() -> {
            CoreTransaction latest = store.require(id);
            CoreTransaction updated = latest;
            if (transition != null && latest.state() == FinancialTransactionState.SUCCESSFUL) {
                updated = move(latest, transition, patch(null, null, null, null, "Reconciliation " + finalStatus, Instant.now(clock)), "Reconciliation " + finalStatus);
            }
            UUID batchId = store.openBatch();
            store.addItem(batchId, id, updated.providerReference(), updated.amount(), actualAmount, finalStatus, "Compared with adapter postings");
            store.closeBatch(batchId, "MATCHED".equals(finalStatus) ? 0 : 1);
            return new ReconciliationResult(batchId, updated, finalStatus, updated.amount(), actualAmount);
        });
    }

    private CoreTransaction registerLoan(CoreTransaction current) {
        ensureSendable();
        var claimed = unitOfWork.required(() -> store.claimLoanRegistration(current.id()));
        if (claimed.isEmpty()) {
            return store.require(current.id());
        }
        CoreTransaction loanClaim = claimed.get();
        try {
            CoreBankingAdapter.AdapterResult<CoreBankingAdapter.LoanAccountView> result = adapter.createLoanAccount(
                    new CoreBankingAdapter.LoanAccountCommand(
                            loanClaim.reference(),
                            loanClaim.idempotencyKey() + ":loan",
                            loanClaim.customerId().toString(),
                            loanClaim.amount(),
                            loanClaim.currencyCode(),
                            loanClaim.correlationId()));
            if (result.outcome() == CoreOutcome.SUCCESSFUL && result.body() != null && result.body().loanAccountReference() != null) {
                return unitOfWork.required(() -> store.annotate(
                        loanClaim.id(),
                        loanClaim.version(),
                        new TransactionPatch(
                                null,
                                InstructionStage.LOAN_REGISTERED,
                                result.providerReference(),
                                result.body().loanAccountReference(),
                                null,
                                null,
                                null,
                                "",
                                null)));
            }
            return unitOfWork.required(() -> {
                CoreTransaction latest = store.require(loanClaim.id());
                if (result.outcome() == CoreOutcome.UNKNOWN) {
                    CoreTransaction unknown = move(latest, FinancialEvent.UNCERTAIN, patch(
                            null, result.providerReference(), null, null, result.message(), Instant.now(clock)), "Loan account outcome is unknown");
                    deadLetter(unknown, "UNKNOWN", "Loan account outcome is unknown");
                    return unknown;
                }
                return fail(latest, result.message() == null ? "Loan account registration failed" : result.message());
            });
        } catch (CoreTransportException ex) {
            return transportFailure(loanClaim, ex, true);
        }
    }

    private CoreTransaction continueLoan(CoreTransaction current) {
        try {
            CoreBankingAdapter.AdapterResult<CoreBankingAdapter.LoanAccountView> result =
                    adapter.loanAccountStatus(new CoreBankingAdapter.LoanAccountLookup(
                            current.loanAccountReference(), current.reference(), current.correlationId()));
            if (result.outcome() == CoreOutcome.SUCCESSFUL && result.body() != null && result.body().loanAccountReference() != null) {
                CoreTransaction ready = unitOfWork.required(() -> {
                    CoreTransaction pending = move(
                            store.require(current.id()),
                            FinancialEvent.CONTINUE_UNSENT,
                            patch(InstructionStage.LOAN_REGISTERED, result.providerReference(), result.body().loanAccountReference(), null, "", null),
                            "Loan account registration was found");
                    return store.annotate(pending.id(), pending.version(), new TransactionPatch(
                            null, InstructionStage.LOAN_REGISTERED, result.providerReference(), result.body().loanAccountReference(), null, null, null, "", null));
                });
                return submitDisbursement(ready.id());
            }
            if (result.outcome() == CoreOutcome.NOT_FOUND || result.outcome() == CoreOutcome.FAILED || result.outcome() == CoreOutcome.REJECTED) {
                return unitOfWork.required(() -> fail(store.require(current.id()), "Loan account was not registered"));
            }
            return current;
        } catch (CoreTransportException ex) {
            return enquiryFailure(current, ex);
        }
    }

    private CoreTransaction sendPayment(CoreTransaction current, boolean disbursement) {
        ensureSendable();
        var claimed = unitOfWork.required(() -> store.claimPayment(current.id()));
        if (claimed.isEmpty()) {
            return store.require(current.id());
        }
        CoreTransaction payment = claimed.get();
        try {
            CoreBankingAdapter.PaymentCommand command = new CoreBankingAdapter.PaymentCommand(
                    payment.reference(),
                    payment.idempotencyKey(),
                    payment.customerId().toString(),
                    payment.accountId() == null ? null : payment.accountId().toString(),
                    payment.loanAccountReference(),
                    payment.amount(),
                    payment.currencyCode(),
                    payment.correlationId());
            CoreBankingAdapter.AdapterResult<CoreBankingAdapter.PostingView> result = disbursement
                    ? adapter.submitDisbursement(command)
                    : adapter.submitRepayment(command);
            CoreTransaction submitted = acceptSubmission(payment, result);
            if (submitted.state() != FinancialTransactionState.SUBMITTED) {
                return submitted;
            }
            return verifyPayment(submitted, disbursement);
        } catch (CoreTransportException ex) {
            return transportFailure(payment, ex, false);
        }
    }

    private CoreTransaction acceptSubmission(CoreTransaction payment, CoreBankingAdapter.AdapterResult<CoreBankingAdapter.PostingView> result) {
        return unitOfWork.required(() -> {
            CoreTransaction latest = store.require(payment.id());
            if (latest.state() != FinancialTransactionState.PENDING) {
                return latest;
            }
            if (result.outcome() == CoreOutcome.FAILED || result.outcome() == CoreOutcome.REJECTED || result.outcome() == CoreOutcome.NOT_FOUND) {
                return fail(latest, result.message());
            }
            if (result.outcome() == CoreOutcome.UNKNOWN) {
                CoreTransaction unknown = move(latest, FinancialEvent.UNCERTAIN, patch(
                        InstructionStage.PAYMENT_SENT, result.providerReference(), null, null, "Outcome unknown", Instant.now(clock)), "No definitive posting response");
                deadLetter(unknown, "UNKNOWN", "No definitive posting response");
                return unknown;
            }
            return move(latest, FinancialEvent.ACCEPT_SUBMISSION, patch(
                    InstructionStage.PAYMENT_SENT, result.providerReference(), null, null, "", null), "Instruction accepted");
        });
    }

    private CoreTransaction verifyPayment(CoreTransaction current, boolean disbursement) {
        try {
            CoreBankingAdapter.AdapterResult<CoreBankingAdapter.PostingView> status = disbursement
                    ? adapter.disbursementStatus(statusQuery(current))
                    : adapter.repaymentStatus(statusQuery(current));
            return unitOfWork.required(() -> applyPosting(store.require(current.id()), status, disbursement));
        } catch (CoreTransportException ex) {
            return enquiryFailure(current, ex);
        }
    }

    private CoreTransaction verifyReversal(CoreTransaction reversal, CoreTransaction original) {
        try {
            CoreBankingAdapter.AdapterResult<CoreBankingAdapter.PostingView> status = adapter.repaymentStatus(statusQuery(reversal));
            return unitOfWork.required(() -> {
                CoreTransaction latest = store.require(reversal.id());
                CoreTransaction source = store.require(original.id());
                if (status.body() != null && status.body().posted() && status.outcome() == CoreOutcome.SUCCESSFUL) {
                    if (latest.state() == FinancialTransactionState.PENDING) {
                        latest = move(latest, FinancialEvent.ACCEPT_SUBMISSION, patch(InstructionStage.PAYMENT_SENT, status.providerReference(), null, null, "", null), "Reversal accepted");
                    }
                    if (latest.state() == FinancialTransactionState.SUBMITTED || latest.state() == FinancialTransactionState.UNKNOWN) {
                        latest = move(latest, FinancialEvent.CONFIRM, patch(null, status.providerReference(), null, latest.amount(), "", Instant.now(clock)), "Reversal posted");
                    }
                    if (source.state() == FinancialTransactionState.SUCCESSFUL) {
                        move(source, FinancialEvent.REVERSE, patch(null, null, null, null, "Reversed", Instant.now(clock)), "Repayment reversed");
                    }
                    store.resolveErrors(latest.reference());
                    metrics.transition(latest.state());
                    return latest;
                }
                if (status.outcome() == CoreOutcome.NOT_FOUND || status.outcome() == CoreOutcome.FAILED) {
                    return fail(latest, "Reversal was not posted");
                }
                return latest;
            });
        } catch (CoreTransportException ex) {
            return enquiryFailure(reversal, ex);
        }
    }

    public CoreTransaction confirmReported(UUID id, boolean disbursement) {
        return verifyPayment(store.require(id), disbursement);
    }

    public CoreTransaction confirmReportedReversal(UUID repaymentId) {
        CoreTransaction repayment = store.require(repaymentId);
        try {
            CoreBankingAdapter.AdapterResult<CoreBankingAdapter.PostingView> status = adapter.repaymentStatus(statusQuery(repayment));
            if (status.body() != null && status.body().reversed()) {
                return unitOfWork.required(() -> {
                    CoreTransaction latest = store.require(repaymentId);
                    if (latest.state() != FinancialTransactionState.SUCCESSFUL) {
                        return latest;
                    }
                    CoreTransaction reversed = move(latest, FinancialEvent.REVERSE, patch(null, null, null, null, "Reversal notification", Instant.now(clock)), "Reversal notification confirmed");
                    store.resolveErrors(reversed.reference());
                    return reversed;
                });
            }
            return unitOfWork.required(() -> {
                CoreTransaction latest = store.require(repaymentId);
                deadLetter(latest, "RECONCILIATION", "Reversal notification was not confirmed");
                if (latest.state() == FinancialTransactionState.SUCCESSFUL) {
                    return move(latest, FinancialEvent.MISMATCH, patch(null, null, null, null, "Reversal was not confirmed", null), "Reversal was not confirmed");
                }
                return latest;
            });
        } catch (CoreTransportException ex) {
            return enquiryFailure(repayment, ex);
        }
    }

    private CoreTransaction applyPosting(
            CoreTransaction current,
            CoreBankingAdapter.AdapterResult<CoreBankingAdapter.PostingView> status,
            boolean disbursement) {
        CoreBankingAdapter.PostingView posting = status.body();
        boolean posted = posting != null && posting.posted() && status.outcome() == CoreOutcome.SUCCESSFUL;
        if (posted) {
            if (current.state() == FinancialTransactionState.PENDING) {
                current = move(current, FinancialEvent.ACCEPT_SUBMISSION, patch(InstructionStage.PAYMENT_SENT, status.providerReference(), null, null, "", null), "Posting accepted");
            }
            if (current.kind() == FinancialTransactionKind.REPAYMENT) {
                BigDecimal net = store.postedNet(current.applicationId());
                if (current.amount().compareTo(net) > 0
                        && (current.state() == FinancialTransactionState.SUBMITTED || current.state() == FinancialTransactionState.UNKNOWN)) {
                    CoreTransaction mismatch = move(current, FinancialEvent.MISMATCH, patch(
                            null, status.providerReference(), null, net.max(BigDecimal.ZERO), "Amount exceeds the outstanding balance", Instant.now(clock)),
                            "Amount exceeds the outstanding balance");
                    accounting(mismatch);
                    return mismatch;
                }
            }
            if (current.state() == FinancialTransactionState.SUBMITTED || current.state() == FinancialTransactionState.UNKNOWN) {
                current = move(current, FinancialEvent.CONFIRM, patch(
                        null, first(status.providerReference(), posting.providerReference()), null, current.amount(), "", Instant.now(clock)), "Posting confirmed");
            }
            if (disbursement && current.state() == FinancialTransactionState.SUCCESSFUL) {
                loans.markDisbursed(current.applicationId(), current.reference(), current.actorId(), current.actorName());
            }
            if (current.kind() == FinancialTransactionKind.REPAYMENT && current.state().countsAsPosted()) {
                accounting(current);
            }
            store.resolveErrors(current.reference());
            metrics.transition(current.state());
            return current;
        }
        if (status.outcome() == CoreOutcome.NOT_FOUND || status.outcome() == CoreOutcome.FAILED || status.outcome() == CoreOutcome.REJECTED) {
            if (current.state() == FinancialTransactionState.SUBMITTED) {
                return move(current, FinancialEvent.MISMATCH, patch(null, null, null, null, "Posting was not found", Instant.now(clock)), "Posting was not found after acceptance");
            }
            if (current.state() == FinancialTransactionState.UNKNOWN || current.state() == FinancialTransactionState.PENDING) {
                return fail(current, "Posting was not found");
            }
        }
        if (current.state() == FinancialTransactionState.SUBMITTED || current.state() == FinancialTransactionState.PENDING) {
            CoreTransaction unknown = move(current, FinancialEvent.UNCERTAIN, patch(null, null, null, null, "Enquiry did not resolve the posting", Instant.now(clock)), "Enquiry did not resolve the posting");
            deadLetter(unknown, "UNKNOWN", "Enquiry did not resolve the posting");
            return unknown;
        }
        return current;
    }

    private CoreTransaction transportFailure(CoreTransaction claimed, CoreTransportException ex, boolean loanClaim) {
        if (!ex.failure().sent()) {
            unitOfWork.required(() -> store.revertClaim(claimed.id(), claimed.version(), loanClaim));
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "Core banking did not accept the instruction");
        }
        return unitOfWork.required(() -> {
            CoreTransaction latest = store.require(claimed.id());
            if (latest.state() != FinancialTransactionState.PENDING) {
                return latest;
            }
            if (ex.failure().uncertain()) {
                CoreTransaction unknown = move(latest, FinancialEvent.UNCERTAIN, patch(
                        loanClaim ? null : InstructionStage.PAYMENT_SENT, null, null, null, ex.failure().name(), Instant.now(clock)), ex.getMessage());
                deadLetter(unknown, ex.failure().name(), ex.getMessage());
                return unknown;
            }
            return fail(latest, ex.getMessage());
        });
    }

    private CoreTransaction enquiryFailure(CoreTransaction current, CoreTransportException ex) {
        if (!ex.failure().sent() || !ex.failure().uncertain()) {
            if (ex.failure() == CoreTransportException.Failure.CIRCUIT_OPEN || ex.failure() == CoreTransportException.Failure.NOT_CONFIGURED) {
                throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "Core banking enquiry is unavailable");
            }
        }
        return unitOfWork.required(() -> {
            CoreTransaction latest = store.require(current.id());
            if (latest.state() != FinancialTransactionState.PENDING && latest.state() != FinancialTransactionState.SUBMITTED) {
                return latest;
            }
            CoreTransaction unknown = move(latest, FinancialEvent.UNCERTAIN, patch(null, null, null, null, ex.failure().name(), Instant.now(clock)), ex.getMessage());
            deadLetter(unknown, ex.failure().name(), ex.getMessage());
            return unknown;
        });
    }

    private CoreTransaction fail(CoreTransaction current, String reason) {
        CoreTransaction failed = move(current, FinancialEvent.REJECT, patch(null, null, null, null, reason, Instant.now(clock)), reason);
        store.resolveErrors(failed.reference());
        metrics.transition(failed.state());
        return failed;
    }

    private CoreTransaction move(CoreTransaction current, FinancialEvent event, TransactionPatch patch, String reason) {
        if (current.state() == FinancialTransactionState.PENDING && event == FinancialEvent.REJECT) {
            return store.transition(current.id(), current.version(), event, patch, reason);
        }
        return store.transition(current.id(), current.version(), event, patch, reason);
    }

    private void deadLetter(CoreTransaction transaction, String category, String message) {
        store.insertError(transaction.kind().name(), transaction.reference(), category, message, true, "{}");
        metrics.deadLetter();
    }

    private void accounting(CoreTransaction transaction) {
        store.insertOutbox(transaction.id(), "AccountingEntryRequested",
                "{\"transactionReference\":\"" + transaction.reference() + "\",\"amount\":\"" + transaction.amount().toPlainString() + "\",\"currencyCode\":\"NGN\"}");
        store.insertOutbox(transaction.id(), "RepaymentPosted",
                "{\"transactionReference\":\"" + transaction.reference() + "\",\"applicationId\":\"" + transaction.applicationId() + "\"}");
    }

    private void ensureSendable() {
        if (!circuit.permitting(Instant.now(clock))) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "Core banking circuit is open");
        }
    }

    private CoreBankingAdapter.StatusQuery statusQuery(CoreTransaction transaction) {
        return new CoreBankingAdapter.StatusQuery(transaction.reference(), transaction.providerReference(), transaction.correlationId());
    }

    private static TransactionPatch patch(
            InstructionStage stage,
            String providerReference,
            String loanAccountReference,
            BigDecimal appliedAmount,
            String lastError,
            Instant resolvedAt) {
        return new TransactionPatch(null, stage, providerReference, loanAccountReference, appliedAmount, null, null, lastError, resolvedAt);
    }

    private static BigDecimal sum(CoreBankingAdapter.PostingList list) {
        if (list == null || list.lines() == null || list.lines().isEmpty()) {
            return null;
        }
        return list.lines().stream().map(CoreBankingAdapter.PostingLine::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String first(String preferred, String fallback) {
        return preferred == null || preferred.isBlank() ? fallback : preferred;
    }

    public record ReconciliationResult(
            UUID batchId,
            CoreTransaction transaction,
            String itemStatus,
            BigDecimal expectedAmount,
            BigDecimal actualAmount
    ) {
    }
}
