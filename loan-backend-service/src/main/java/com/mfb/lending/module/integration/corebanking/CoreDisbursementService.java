package com.mfb.lending.module.integration.corebanking;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;
import org.springframework.stereotype.Service;

@Service
public class CoreDisbursementService {

    private final CoreBankingStore store;
    private final CoreBankingAdapter adapter;
    private final CorePostingEngine engine;
    private final DisbursementLoanPort loans;
    private final CoreBankingOutboxDispatcher dispatcher;
    private final UnitOfWork unitOfWork;
    private final AuditService audit;
    private final java.time.Clock clock;

    public CoreDisbursementService(
            CoreBankingStore store,
            CoreBankingAdapter adapter,
            CorePostingEngine engine,
            DisbursementLoanPort loans,
            CoreBankingOutboxDispatcher dispatcher,
            UnitOfWork unitOfWork,
            AuditService audit,
            java.time.Clock clock) {
        this.store = store;
        this.adapter = adapter;
        this.engine = engine;
        this.loans = loans;
        this.dispatcher = dispatcher;
        this.unitOfWork = unitOfWork;
        this.audit = audit;
        this.clock = clock;
    }

    public CoreTransaction disburse(DisbursementInstruction instruction, ClientContext client) {
        String key = CoreIds.idempotency(instruction.idempotencyKey());
        if (instruction.actorId() == null || instruction.actorName() == null || instruction.actorName().isBlank()) {
            throw ValidationException.field("actorId", "An authorising officer is required");
        }
        var existing = store.findByIdempotency(key);
        if (existing.isPresent()) {
            return resume(existing.get());
        }
        DisbursementLoanPort.DisbursableLoan loan = loans.load(instruction.applicationId(), instruction.accountId(), instruction.amount());
        if (loan.status() != ApplicationStatus.PENDING_DISBURSEMENT) {
            throw new ConflictException("The loan is not authorised for disbursement");
        }
        verify(loan, instruction.correlationId());
        Prepared prepared;
        try {
            prepared = unitOfWork.required(() -> insert(instruction, key, loan));
        } catch (ConflictException ex) {
            return store.findByIdempotency(key).orElseThrow(() -> ex);
        }
        return submit(prepared, client);
    }

    public CoreTransaction acceptNotification(String reference, String providerReference) {
        CoreTransaction existing = reference == null ? null : store.findByReference(reference).orElse(null);
        if (existing == null || existing.kind() != FinancialTransactionKind.DISBURSEMENT) {
            store.insertError("DISBURSEMENT", reference, "RECONCILIATION", "Disbursement notification did not match a local instruction", true, "{}");
            throw new ConflictException("No disbursement instruction matches this notification");
        }
        if (providerReference != null && (existing.providerReference() == null || existing.providerReference().isBlank())) {
            existing = store.annotate(existing.id(), existing.version(), new TransactionPatch(
                    null, null, providerReference, null, null, null, null, null, null));
        }
        if (existing.state() == FinancialTransactionState.PENDING) {
            return engine.confirmReported(existing.id(), true);
        }
        return engine.enquire(existing.id());
    }

    private CoreTransaction resume(CoreTransaction current) {
        if (current.kind() != FinancialTransactionKind.DISBURSEMENT) {
            throw new ConflictException("The idempotency key belongs to another instruction");
        }
        if (current.state() == FinancialTransactionState.PENDING) {
            return engine.submitDisbursement(current.id());
        }
        return current;
    }

    private void verify(DisbursementLoanPort.DisbursableLoan loan, String correlationId) {
        String correlation = CoreIds.correlation(correlationId);
        try {
            var customer = adapter.verifyCustomer(new CoreBankingAdapter.CustomerQuery(loan.customerId().toString(), correlation));
            if (customer.outcome() != CoreOutcome.SUCCESSFUL || customer.body() == null || !customer.body().verified()) {
                throw new ConflictException("Core banking did not verify the customer");
            }
            var account = adapter.validateAccount(new CoreBankingAdapter.AccountQuery(
                    loan.customerId().toString(), loan.accountId().toString(), correlation));
            if (account.outcome() == CoreOutcome.NOT_FOUND) {
                throw new ResourceNotFoundException("Destination account");
            }
            if (account.outcome() != CoreOutcome.SUCCESSFUL || account.body() == null || !account.body().valid()) {
                throw ValidationException.field("accountId", "Core banking did not validate the destination account");
            }
        } catch (CoreTransportException ex) {
            throw CoreFailures.api(ex);
        }
    }

    private Prepared insert(DisbursementInstruction instruction, String key, DisbursementLoanPort.DisbursableLoan loan) {
        Instant now = Instant.now(clock);
        UUID id = UUID.randomUUID();
        CoreTransaction created = new CoreTransaction(
                id,
                store.nextReference(),
                key,
                FinancialTransactionKind.DISBURSEMENT,
                FinancialTransactionState.INITIATED,
                InstructionStage.NEW,
                loan.applicationId(),
                loan.customerId(),
                loan.accountId(),
                null,
                CoreMoney.ngn(loan.amount(), "amount"),
                null,
                "NGN",
                null,
                null,
                0,
                0,
                0,
                CoreIds.correlation(instruction.correlationId()),
                null,
                instruction.actorId(),
                instruction.actorName(),
                now,
                now,
                null);
        store.insert(created);
        CoreTransaction pending = store.transition(id, 0, FinancialEvent.ACCEPT, TransactionPatch.state(FinancialTransactionState.PENDING, "", null), "Accepted for submission");
        UUID outboxId = store.insertOutbox(id, "DisbursementRequested", "{\"transactionId\":\"" + id + "\"}");
        return new Prepared(pending, outboxId);
    }

    private CoreTransaction submit(Prepared prepared, ClientContext client) {
        if (prepared.outboxId() == null) {
            return prepared.transaction();
        }
        try {
            CoreTransaction result = engine.submitDisbursement(prepared.transaction().id());
            dispatcher.finish(prepared.outboxId(), result);
            if (audit != null) {
                audit.record(
                        "CORE_DISBURSEMENT",
                        "core_banking_transaction",
                        result.id().toString(),
                        result.actorName(),
                        client,
                        Map.of("state", result.state().name(), "reference", result.reference()));
            }
            return result;
        } catch (ApiException ex) {
            if (ex.getErrorCode() == ErrorCode.SERVICE_UNAVAILABLE) {
                dispatcher.finish(prepared.outboxId(), store.require(prepared.transaction().id()));
            }
            throw ex;
        }
    }

    private record Prepared(CoreTransaction transaction, UUID outboxId) {
    }
}
