package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import org.springframework.stereotype.Service;

@Service
public class CoreRepaymentService {

    private final CoreBankingStore store;
    private final CorePostingEngine engine;
    private final CoreBankingOutboxDispatcher dispatcher;
    private final UnitOfWork unitOfWork;
    private final AuditService audit;
    private final java.time.Clock clock;

    public CoreRepaymentService(
            CoreBankingStore store,
            CorePostingEngine engine,
            CoreBankingOutboxDispatcher dispatcher,
            UnitOfWork unitOfWork,
            AuditService audit,
            java.time.Clock clock) {
        this.store = store;
        this.engine = engine;
        this.dispatcher = dispatcher;
        this.unitOfWork = unitOfWork;
        this.audit = audit;
        this.clock = clock;
    }

    public CoreTransaction repay(RepaymentInstruction instruction, ClientContext client) {
        String key = CoreIds.idempotency(instruction.idempotencyKey());
        BigDecimal amount = CoreMoney.ngn(instruction.amount(), "amount");
        var existing = store.findByIdempotency(key);
        if (existing.isPresent()) {
            return resume(existing.get());
        }
        CoreTransaction disbursement = postedDisbursement(instruction.applicationId());
        BigDecimal outstanding = store.postedNet(instruction.applicationId());
        if (amount.compareTo(outstanding) > 0) {
            throw ValidationException.field("amount", "amount exceeds the outstanding balance");
        }
        Prepared prepared;
        try {
            prepared = unitOfWork.required(() -> insert(instruction, key, amount, disbursement, FinancialTransactionKind.REPAYMENT, null));
        } catch (ConflictException ex) {
            return store.findByIdempotency(key).orElseThrow(() -> ex);
        }
        return submit(prepared, false, client);
    }

    public CoreTransaction acceptExternal(RepaymentInstruction instruction) {
        String key = CoreIds.idempotency(instruction.idempotencyKey());
        var existing = store.findByIdempotency(key);
        if (existing.isPresent()) {
            CoreTransaction current = existing.get();
            if (current.state().countsAsPosted() || current.state().terminal()) {
                return current;
            }
            return engine.confirmReported(current.id(), false);
        }
        CoreTransaction disbursement = postedDisbursement(instruction.applicationId());
        CoreTransaction pending = unitOfWork.required(() -> insert(
                instruction,
                key,
                CoreMoney.ngn(instruction.amount(), "amount"),
                disbursement,
                FinancialTransactionKind.REPAYMENT,
                null).transaction());
        return engine.confirmReported(pending.id(), false);
    }

    public CoreTransaction reverse(UUID repaymentId, String idempotencyKey, UUID actorId, String actorName, ClientContext client) {
        String key = CoreIds.idempotency(idempotencyKey);
        if (actorId == null) {
            throw ValidationException.field("actorId", "An authorising officer is required");
        }
        var existing = store.findByIdempotency(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        CoreTransaction repayment = store.require(repaymentId);
        if (repayment.kind() != FinancialTransactionKind.REPAYMENT || repayment.state() != FinancialTransactionState.SUCCESSFUL) {
            throw new ConflictException("Only a confirmed repayment can be reversed");
        }
        Prepared prepared = unitOfWork.required(() -> insert(
                new RepaymentInstruction(repayment.applicationId(), key, repayment.amount(), null, actorId, actorName, null),
                key,
                repayment.amount(),
                repayment,
                FinancialTransactionKind.REVERSAL,
                repayment.id()));
        try {
            CoreTransaction result = engine.submitReversal(prepared.transaction().id());
            dispatcher.finish(prepared.outboxId(), result);
            if (audit != null) {
                audit.record("CORE_REVERSAL", "core_banking_transaction", result.id().toString(), actorName, client, Map.of("state", result.state().name()));
            }
            CoreTransaction original = store.require(repaymentId);
            return original.state() == FinancialTransactionState.REVERSED ? original : result;
        } catch (ApiException ex) {
            if (ex.getErrorCode() == ErrorCode.SERVICE_UNAVAILABLE) {
                dispatcher.finish(prepared.outboxId(), store.require(prepared.transaction().id()));
            }
            throw ex;
        }
    }

    public CoreTransaction acceptReversal(String transactionReference) {
        CoreTransaction repayment = store.findByReference(transactionReference)
                .orElseThrow(() -> new ConflictException("No repayment matches this reversal notification"));
        if (repayment.kind() != FinancialTransactionKind.REPAYMENT) {
            throw new ConflictException("Reversal notification did not match a repayment");
        }
        if (repayment.state() == FinancialTransactionState.REVERSED) {
            return repayment;
        }
        return engine.confirmReportedReversal(repayment.id());
    }

    private CoreTransaction resume(CoreTransaction current) {
        if (current.kind() != FinancialTransactionKind.REPAYMENT) {
            throw new ConflictException("The idempotency key belongs to another instruction");
        }
        if (current.state() == FinancialTransactionState.PENDING) {
            return engine.submitRepayment(current.id());
        }
        return current;
    }

    private CoreTransaction submit(Prepared prepared, boolean external, ClientContext client) {
        try {
            CoreTransaction result = external
                    ? engine.confirmReported(prepared.transaction().id(), false)
                    : engine.submitRepayment(prepared.transaction().id());
            dispatcher.finish(prepared.outboxId(), result);
            if (audit != null) {
                audit.record(
                        "CORE_REPAYMENT",
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

    private Prepared insert(
            RepaymentInstruction instruction,
            String key,
            BigDecimal amount,
            CoreTransaction source,
            FinancialTransactionKind kind,
            UUID relatedId) {
        Instant now = Instant.now(clock);
        UUID id = UUID.randomUUID();
        String actorName = instruction.actorName() == null || instruction.actorName().isBlank() ? "core-banking-webhook" : instruction.actorName();
        CoreTransaction created = new CoreTransaction(
                id,
                store.nextReference(),
                key,
                kind,
                FinancialTransactionState.INITIATED,
                InstructionStage.NEW,
                source.applicationId(),
                source.customerId(),
                source.accountId(),
                relatedId,
                amount,
                null,
                "NGN",
                instruction.providerReference(),
                source.loanAccountReference(),
                0,
                0,
                0,
                CoreIds.correlation(instruction.correlationId()),
                null,
                instruction.actorId(),
                actorName,
                now,
                now,
                null);
        store.insert(created);
        CoreTransaction pending = store.transition(
                id, 0, FinancialEvent.ACCEPT, TransactionPatch.state(FinancialTransactionState.PENDING, "", null), "Accepted for submission");
        String eventType = kind == FinancialTransactionKind.REVERSAL ? "ReversalRequested" : "RepaymentRequested";
        UUID outboxId = store.insertOutbox(id, eventType, "{\"transactionId\":\"" + id + "\"}");
        return new Prepared(pending, outboxId);
    }

    private CoreTransaction postedDisbursement(UUID applicationId) {
        if (applicationId == null) {
            throw ValidationException.field("applicationId", "applicationId is required");
        }
        CoreTransaction disbursement = store.findOpenDisbursement(applicationId)
                .orElseThrow(() -> new ConflictException("The loan has not been disbursed"));
        if (!disbursement.state().countsAsPosted()) {
            throw new ConflictException("The loan has not been disbursed");
        }
        return disbursement;
    }

    private record Prepared(CoreTransaction transaction, UUID outboxId) {
    }
}
