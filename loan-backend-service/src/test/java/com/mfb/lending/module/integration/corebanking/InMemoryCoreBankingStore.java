package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;

public class InMemoryCoreBankingStore implements CoreBankingStore {

    private final java.time.Clock clock;
    private final Object lock = new Object();
    private final ConcurrentHashMap<UUID, CoreTransaction> transactions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> byKey = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> byReference = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> webhooks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, OutboxRow> outbox = new ConcurrentHashMap<>();
    private final List<DeadLetter> deadLetters = new ArrayList<>();
    private final AtomicLong references = new AtomicLong();

    public InMemoryCoreBankingStore(java.time.Clock clock) {
        this.clock = clock;
    }

    @Override
    public Optional<CoreTransaction> findById(UUID id) {
        synchronized (lock) {
            return Optional.ofNullable(transactions.get(id));
        }
    }

    @Override
    public Optional<CoreTransaction> findByIdempotency(String idempotencyKey) {
        synchronized (lock) {
            UUID id = byKey.get(idempotencyKey);
            return id == null ? Optional.empty() : Optional.ofNullable(transactions.get(id));
        }
    }

    @Override
    public Optional<CoreTransaction> findByReference(String reference) {
        synchronized (lock) {
            UUID id = byReference.get(reference);
            return id == null ? Optional.empty() : Optional.ofNullable(transactions.get(id));
        }
    }

    @Override
    public Optional<CoreTransaction> findOpenDisbursement(UUID applicationId) {
        synchronized (lock) {
            return transactions.values().stream()
                    .filter(tx -> tx.kind() == FinancialTransactionKind.DISBURSEMENT)
                    .filter(tx -> applicationId.equals(tx.applicationId()))
                    .filter(tx -> tx.state().countsAsPosted()
                            || tx.state() == FinancialTransactionState.INITIATED
                            || tx.state() == FinancialTransactionState.PENDING
                            || tx.state() == FinancialTransactionState.SUBMITTED
                            || tx.state() == FinancialTransactionState.UNKNOWN)
                    .findFirst();
        }
    }

    @Override
    public CoreTransaction insert(CoreTransaction transaction) {
        synchronized (lock) {
            if (byKey.containsKey(transaction.idempotencyKey()) || byReference.containsKey(transaction.reference())) {
                throw new ConflictException("A core banking transaction already exists for this instruction");
            }
            if (transaction.kind() == FinancialTransactionKind.DISBURSEMENT
                    && findOpenDisbursement(transaction.applicationId()).isPresent()) {
                throw new ConflictException("A core banking transaction already exists for this instruction");
            }
            transactions.put(transaction.id(), transaction);
            byKey.put(transaction.idempotencyKey(), transaction.id());
            byReference.put(transaction.reference(), transaction.id());
            return transaction;
        }
    }

    @Override
    public CoreTransaction transition(UUID id, int expectedVersion, FinancialEvent event, TransactionPatch patch, String reason) {
        synchronized (lock) {
            CoreTransaction current = requiredLocked(id);
            if (current.version() != expectedVersion) {
                throw new ConflictException("The core banking transaction was updated by another process");
            }
            FinancialTransactionState to = FinancialStateMachine.next(current.state(), event);
            TransactionPatch enforced = patch == null
                    ? TransactionPatch.state(to, current.lastError(), null)
                    : new TransactionPatch(to, patch.stage(), patch.providerReference(), patch.loanAccountReference(),
                            patch.appliedAmount(), patch.loanAttempt(), patch.paymentAttempt(), patch.lastError(), patch.resolvedAt());
            return replace(current.apply(enforced, Instant.now(clock)));
        }
    }

    @Override
    public Optional<CoreTransaction> claimLoanRegistration(UUID id) {
        synchronized (lock) {
            CoreTransaction current = requiredLocked(id);
            if (current.kind() != FinancialTransactionKind.DISBURSEMENT
                    || current.state() != FinancialTransactionState.PENDING
                    || current.stage() != InstructionStage.NEW
                    || current.loanAttempt() != 0) {
                return Optional.empty();
            }
            return Optional.of(replace(current.apply(new TransactionPatch(null, null, null, null, null, 1, null, null, null), Instant.now(clock))));
        }
    }

    @Override
    public Optional<CoreTransaction> claimPayment(UUID id) {
        synchronized (lock) {
            CoreTransaction current = requiredLocked(id);
            boolean disbursementReady = current.kind() == FinancialTransactionKind.DISBURSEMENT && current.stage() == InstructionStage.LOAN_REGISTERED;
            boolean paymentReady = current.kind() != FinancialTransactionKind.DISBURSEMENT && current.stage() == InstructionStage.NEW;
            if (current.state() != FinancialTransactionState.PENDING || current.paymentAttempt() != 0 || !(disbursementReady || paymentReady)) {
                return Optional.empty();
            }
            return Optional.of(replace(current.apply(new TransactionPatch(null, null, null, null, null, null, 1, null, null), Instant.now(clock))));
        }
    }

    @Override
    public CoreTransaction annotate(UUID id, int expectedVersion, TransactionPatch patch) {
        synchronized (lock) {
            CoreTransaction current = requiredLocked(id);
            if (current.version() != expectedVersion) {
                throw new ConflictException("The core banking transaction was updated by another process");
            }
            return replace(current.apply(patch, Instant.now(clock)));
        }
    }

    @Override
    public CoreTransaction revertClaim(UUID id, int expectedVersion, boolean loanClaim) {
        synchronized (lock) {
            CoreTransaction current = requiredLocked(id);
            if (current.version() != expectedVersion) {
                throw new ConflictException("The core banking transaction was updated by another process");
            }
            TransactionPatch patch = loanClaim
                    ? new TransactionPatch(null, null, null, null, null, 0, null, "", null)
                    : new TransactionPatch(null, null, null, null, null, null, 0, "", null);
            return replace(current.apply(patch, Instant.now(clock)));
        }
    }

    @Override
    public String nextReference() {
        return "CB" + String.format("%012d", references.incrementAndGet());
    }

    @Override
    public List<CoreTransaction> list(String state, String kind, int limit, int offset) {
        synchronized (lock) {
            return transactions.values().stream()
                    .filter(tx -> state == null || state.isBlank() || tx.state().name().equals(state))
                    .filter(tx -> kind == null || kind.isBlank() || tx.kind().name().equals(kind))
                    .sorted(Comparator.comparing(CoreTransaction::createdAt).reversed())
                    .skip(offset)
                    .limit(limit)
                    .toList();
        }
    }

    @Override
    public long count(String state, String kind) {
        return list(state, kind, Integer.MAX_VALUE, 0).size();
    }

    @Override
    public BigDecimal postedNet(UUID applicationId) {
        synchronized (lock) {
            return transactions.values().stream()
                    .filter(tx -> applicationId.equals(tx.applicationId()))
                    .filter(tx -> tx.state().countsAsPosted())
                    .map(tx -> tx.kind() == FinancialTransactionKind.DISBURSEMENT ? tx.amount()
                            : tx.kind() == FinancialTransactionKind.REPAYMENT ? tx.amount().negate() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    private final java.util.Set<UUID> servicingCollections = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public void markServicingCollection(UUID transactionId) {
        synchronized (lock) {
            if (!transactions.containsKey(transactionId)) {
                throw new ResourceNotFoundException("Core banking transaction");
            }
            servicingCollections.add(transactionId);
        }
    }

    @Override
    public boolean servicingCollection(UUID transactionId) {
        return servicingCollections.contains(transactionId);
    }

    @Override
    public BigDecimal servicingRepaymentCash(UUID applicationId) {
        synchronized (lock) {
            return transactions.values().stream()
                    .filter(tx -> servicingCollections.contains(tx.id()))
                    .filter(tx -> tx.kind() == FinancialTransactionKind.REPAYMENT)
                    .filter(tx -> tx.state().countsAsPosted())
                    .filter(tx -> applicationId == null || applicationId.equals(tx.applicationId()))
                    .map(CoreTransaction::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    @Override
    public int countState(FinancialTransactionState state) {
        synchronized (lock) {
            return (int) transactions.values().stream().filter(tx -> tx.state() == state).count();
        }
    }

    @Override
    public UUID insertRequest(UUID transactionId, String operation, String idempotencyKey, String correlationId, String payload) {
        return UUID.randomUUID();
    }

    @Override
    public void insertResponse(UUID requestId, String outcome, String providerReference, String payload, int latencyMs) {
    }

    @Override
    public boolean insertWebhook(UUID id, String messageId, String eventType, String payload) {
        return webhooks.putIfAbsent(messageId, true) == null;
    }

    @Override
    public void insertInbox(String messageId, String eventType, String payload) {
        if (webhooks.putIfAbsent("inbox:" + messageId, true) != null) {
            throw new ConflictException("Webhook message already received");
        }
    }

    @Override
    public UUID insertOutbox(UUID transactionId, String eventType, String payload) {
        UUID id = UUID.randomUUID();
        outbox.put(id, new OutboxRow(id, transactionId, eventType, "PENDING", Instant.now(clock)));
        return id;
    }

    @Override
    public void completeOutbox(UUID outboxId, String status, String error, Instant availableAt) {
        OutboxRow row = outbox.get(outboxId);
        if (row != null) {
            outbox.put(outboxId, row.complete(status, availableAt == null ? row.availableAt() : availableAt));
        }
    }

    @Override
    public List<OutboxMessage> claimDueOutbox(int limit) {
        synchronized (lock) {
            Instant now = Instant.now(clock);
            List<OutboxRow> due = outbox.values().stream()
                    .filter(row -> "PENDING".equals(row.status()) && !row.availableAt().isAfter(now))
                    .limit(limit)
                    .toList();
            List<OutboxMessage> messages = new ArrayList<>();
            for (OutboxRow row : due) {
                outbox.put(row.id(), row.complete("PENDING", now.plusSeconds(120)));
                messages.add(new OutboxMessage(row.id(), row.transactionId(), row.eventType()));
            }
            return messages;
        }
    }

    @Override
    public void insertError(String operation, String reference, String category, String message, boolean deadLetter, String payload) {
        synchronized (lock) {
            if (deadLetter) {
                deadLetters.add(new DeadLetter(UUID.randomUUID(), operation, reference, category, message));
            }
        }
    }

    @Override
    public List<DeadLetter> unresolvedDeadLetters() {
        synchronized (lock) {
            return List.copyOf(deadLetters);
        }
    }

    @Override
    public void resolveErrors(String reference) {
        synchronized (lock) {
            deadLetters.removeIf(letter -> reference.equals(letter.reference()));
        }
    }

    @Override
    public UUID openBatch() {
        return UUID.randomUUID();
    }

    @Override
    public void addItem(UUID batchId, UUID transactionId, String externalReference, BigDecimal expectedAmount, BigDecimal actualAmount, String status, String detail) {
    }

    @Override
    public void closeBatch(UUID batchId, int mismatches) {
    }

    @Override
    public int countDeadLetters() {
        return unresolvedDeadLetters().size();
    }

    private CoreTransaction requiredLocked(UUID id) {
        CoreTransaction current = transactions.get(id);
        if (current == null) {
            throw new ResourceNotFoundException("Core banking transaction");
        }
        return current;
    }

    private CoreTransaction replace(CoreTransaction next) {
        transactions.put(next.id(), next);
        return next;
    }

    private record OutboxRow(UUID id, UUID transactionId, String eventType, String status, Instant availableAt) {
        private OutboxRow complete(String status, Instant availableAt) {
            return new OutboxRow(id, transactionId, eventType, status, availableAt);
        }
    }
}
