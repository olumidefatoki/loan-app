package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCoreBankingStore implements CoreBankingStore {

    private static final String COLUMNS = """
            id, reference, idempotency_key, kind, state, stage, application_id, customer_id, account_id,
            related_transaction_id, amount, applied_amount, currency_code, provider_reference, loan_account_reference,
            loan_attempt, payment_attempt, version, correlation_id, last_error, actor_id, actor_name,
            created_at, updated_at, resolved_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final java.time.Clock clock;

    public JdbcCoreBankingStore(NamedParameterJdbcTemplate jdbc, java.time.Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    public Optional<CoreTransaction> findById(UUID id) {
        return one("SELECT " + COLUMNS + " FROM core_banking_transactions WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    @Override
    public Optional<CoreTransaction> findByIdempotency(String idempotencyKey) {
        return one(
                "SELECT " + COLUMNS + " FROM core_banking_transactions WHERE idempotency_key = :key",
                new MapSqlParameterSource("key", idempotencyKey));
    }

    @Override
    public Optional<CoreTransaction> findByReference(String reference) {
        return one(
                "SELECT " + COLUMNS + " FROM core_banking_transactions WHERE reference = :reference",
                new MapSqlParameterSource("reference", reference));
    }

    @Override
    public Optional<CoreTransaction> findOpenDisbursement(UUID applicationId) {
        return one(
                """
                SELECT %s FROM core_banking_transactions
                WHERE application_id = :applicationId
                  AND kind = 'DISBURSEMENT'
                  AND state IN ('INITIATED', 'PENDING', 'SUBMITTED', 'UNKNOWN', 'SUCCESSFUL', 'RECONCILIATION_REQUIRED')
                """.formatted(COLUMNS),
                new MapSqlParameterSource("applicationId", applicationId));
    }

    @Override
    public CoreTransaction insert(CoreTransaction transaction) {
        try {
            jdbc.update(
                    """
                    INSERT INTO core_banking_transactions (
                        id, reference, idempotency_key, kind, state, stage, application_id, customer_id, account_id,
                        related_transaction_id, amount, applied_amount, currency_code, provider_reference,
                        loan_account_reference, loan_attempt, payment_attempt, version, correlation_id, last_error,
                        actor_id, actor_name, created_at, updated_at, resolved_at)
                    VALUES (
                        :id, :reference, :idempotencyKey, :kind, :state, :stage, :applicationId, :customerId, :accountId,
                        :relatedTransactionId, :amount, :appliedAmount, :currencyCode, :providerReference,
                        :loanAccountReference, :loanAttempt, :paymentAttempt, :version, :correlationId, :lastError,
                        :actorId, :actorName, :createdAt, :updatedAt, :resolvedAt)
                    """,
                    params(transaction));
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("A core banking transaction already exists for this instruction");
        }
        jdbc.update(
                """
                INSERT INTO core_banking_transaction_events (id, transaction_id, from_state, to_state, reason)
                VALUES (:id, :transactionId, NULL, :toState, :reason)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("transactionId", transaction.id())
                        .addValue("toState", transaction.state().name())
                        .addValue("reason", "Instruction recorded"));
        return transaction;
    }

    @Override
    public CoreTransaction transition(UUID id, int expectedVersion, FinancialEvent event, TransactionPatch patch, String reason) {
        CoreTransaction current = lock(id);
        if (current.version() != expectedVersion) {
            throw new ConflictException("The core banking transaction was updated by another process");
        }
        FinancialTransactionState to = FinancialStateMachine.next(current.state(), event);
        TransactionPatch enforced = patch == null
                ? TransactionPatch.state(to, current.lastError(), null)
                : new TransactionPatch(
                        to,
                        patch.stage(),
                        patch.providerReference(),
                        patch.loanAccountReference(),
                        patch.appliedAmount(),
                        patch.loanAttempt(),
                        patch.paymentAttempt(),
                        patch.lastError(),
                        patch.resolvedAt());
        return save(current, current.apply(enforced, Instant.now(clock)), current.state(), reason);
    }

    @Override
    public Optional<CoreTransaction> claimLoanRegistration(UUID id) {
        CoreTransaction current = lock(id);
        if (current.kind() != FinancialTransactionKind.DISBURSEMENT
                || current.state() != FinancialTransactionState.PENDING
                || current.stage() != InstructionStage.NEW
                || current.loanAttempt() != 0) {
            return Optional.empty();
        }
        return Optional.of(saveAttempt(current, new TransactionPatch(null, null, null, null, null, 1, null, null, null)));
    }

    @Override
    public Optional<CoreTransaction> claimPayment(UUID id) {
        CoreTransaction current = lock(id);
        boolean disbursementReady = current.kind() == FinancialTransactionKind.DISBURSEMENT
                && current.stage() == InstructionStage.LOAN_REGISTERED;
        boolean paymentReady = current.kind() != FinancialTransactionKind.DISBURSEMENT
                && current.stage() == InstructionStage.NEW;
        if (current.state() != FinancialTransactionState.PENDING || current.paymentAttempt() != 0 || !(disbursementReady || paymentReady)) {
            return Optional.empty();
        }
        return Optional.of(saveAttempt(current, new TransactionPatch(null, null, null, null, null, null, 1, null, null)));
    }

    @Override
    public CoreTransaction annotate(UUID id, int expectedVersion, TransactionPatch patch) {
        CoreTransaction current = lock(id);
        if (current.version() != expectedVersion) {
            throw new ConflictException("The core banking transaction was updated by another process");
        }
        return save(current, current.apply(patch, Instant.now(clock)), null, null);
    }

    @Override
    public CoreTransaction revertClaim(UUID id, int expectedVersion, boolean loanClaim) {
        CoreTransaction current = lock(id);
        if (current.version() != expectedVersion) {
            throw new ConflictException("The core banking transaction was updated by another process");
        }
        TransactionPatch patch = loanClaim
                ? new TransactionPatch(null, null, null, null, null, 0, null, "", null)
                : new TransactionPatch(null, null, null, null, null, null, 0, "", null);
        return save(current, current.apply(patch, Instant.now(clock)), null, null);
    }

    @Override
    public String nextReference() {
        Long value = jdbc.queryForObject("SELECT nextval('core_banking_reference_seq')", new MapSqlParameterSource(), Long.class);
        return "CB" + String.format("%012d", value == null ? 1 : value);
    }

    @Override
    public List<CoreTransaction> list(String state, String kind, int limit, int offset) {
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM core_banking_transactions" + filter(state, kind)
                        + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset",
                filterParams(state, kind).addValue("limit", limit).addValue("offset", offset),
                (rs, rowNum) -> map(rs));
    }

    @Override
    public long count(String state, String kind) {
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM core_banking_transactions" + filter(state, kind),
                filterParams(state, kind),
                Long.class);
        return total == null ? 0 : total;
    }

    @Override
    public BigDecimal postedNet(UUID applicationId) {
        BigDecimal net = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(CASE
                    WHEN kind = 'DISBURSEMENT' AND state IN ('SUCCESSFUL', 'RECONCILIATION_REQUIRED') THEN amount
                    WHEN kind = 'REPAYMENT' AND state IN ('SUCCESSFUL', 'RECONCILIATION_REQUIRED') THEN -amount
                    ELSE 0 END), 0)
                FROM core_banking_transactions
                WHERE application_id = :applicationId
                """,
                new MapSqlParameterSource("applicationId", applicationId),
                BigDecimal.class);
        return net == null ? BigDecimal.ZERO : net;
    }

    @Override
    public void markServicingCollection(UUID transactionId) {
        int updated = jdbc.update(
                "UPDATE core_banking_transactions SET servicing_collection = TRUE WHERE id = :id",
                new MapSqlParameterSource("id", transactionId));
        if (updated != 1) {
            throw new com.mfb.lending.common.exception.ResourceNotFoundException("Core banking transaction");
        }
    }

    @Override
    public boolean servicingCollection(UUID transactionId) {
        Boolean value = jdbc.queryForObject(
                "SELECT servicing_collection FROM core_banking_transactions WHERE id = :id",
                new MapSqlParameterSource("id", transactionId),
                Boolean.class);
        return Boolean.TRUE.equals(value);
    }

    @Override
    public BigDecimal servicingRepaymentCash(UUID applicationId) {
        String sql = applicationId == null
                ? """
                SELECT COALESCE(SUM(amount), 0)
                FROM core_banking_transactions
                WHERE kind = 'REPAYMENT'
                  AND servicing_collection = TRUE
                  AND state IN ('SUCCESSFUL', 'RECONCILIATION_REQUIRED')
                """
                : """
                SELECT COALESCE(SUM(amount), 0)
                FROM core_banking_transactions
                WHERE kind = 'REPAYMENT'
                  AND servicing_collection = TRUE
                  AND state IN ('SUCCESSFUL', 'RECONCILIATION_REQUIRED')
                  AND application_id = :applicationId
                """;
        BigDecimal total = jdbc.queryForObject(sql, new MapSqlParameterSource("applicationId", applicationId), BigDecimal.class);
        return total == null ? BigDecimal.ZERO : total;
    }

    @Override
    public int countState(FinancialTransactionState state) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM core_banking_transactions WHERE state = :state",
                new MapSqlParameterSource("state", state.name()),
                Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public UUID insertRequest(UUID transactionId, String operation, String idempotencyKey, String correlationId, String payload) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO core_banking_requests (id, transaction_id, operation, idempotency_key, correlation_id, payload)
                VALUES (:id, :transactionId, :operation, :idempotencyKey, :correlationId, CAST(:payload AS jsonb))
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("transactionId", transactionId)
                        .addValue("operation", operation)
                        .addValue("idempotencyKey", idempotencyKey)
                        .addValue("correlationId", correlationId)
                        .addValue("payload", payload));
        return id;
    }

    @Override
    public void insertResponse(UUID requestId, String outcome, String providerReference, String payload, int latencyMs) {
        jdbc.update(
                """
                INSERT INTO core_banking_responses (id, request_id, outcome, provider_reference, payload, latency_ms)
                VALUES (:id, :requestId, :outcome, :providerReference, CAST(:payload AS jsonb), :latencyMs)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("requestId", requestId)
                        .addValue("outcome", outcome)
                        .addValue("providerReference", providerReference)
                        .addValue("payload", payload == null ? "{}" : payload)
                        .addValue("latencyMs", latencyMs));
    }

    @Override
    public boolean insertWebhook(UUID id, String messageId, String eventType, String payload) {
        try {
            jdbc.update(
                    """
                    INSERT INTO core_banking_webhook_events (id, message_id, event_type, payload, status)
                    VALUES (:id, :messageId, :eventType, CAST(:payload AS jsonb), 'ACCEPTED')
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", id)
                            .addValue("messageId", messageId)
                            .addValue("eventType", eventType)
                            .addValue("payload", payload));
            return true;
        } catch (DuplicateKeyException ex) {
            return false;
        }
    }

    @Override
    public void insertInbox(String messageId, String eventType, String payload) {
        jdbc.update(
                """
                INSERT INTO integration_inbox (id, message_id, source, event_type, payload)
                VALUES (:id, :messageId, 'core-banking', :eventType, CAST(:payload AS jsonb))
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("messageId", messageId)
                        .addValue("eventType", eventType)
                        .addValue("payload", payload));
    }

    @Override
    public UUID insertOutbox(UUID transactionId, String eventType, String payload) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO integration_outbox (id, aggregate_type, aggregate_id, event_type, payload, destination)
                VALUES (:id, 'core_banking_transaction', :aggregateId, :eventType, CAST(:payload AS jsonb), 'core-banking')
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("aggregateId", transactionId.toString())
                        .addValue("eventType", eventType)
                        .addValue("payload", payload));
        return id;
    }

    @Override
    public void completeOutbox(UUID outboxId, String status, String error, Instant availableAt) {
        jdbc.update(
                """
                UPDATE integration_outbox
                SET status = :status,
                    last_error = :error,
                    available_at = COALESCE(:availableAt, available_at),
                    published_at = CASE WHEN :status = 'PUBLISHED' THEN NOW() ELSE published_at END
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", outboxId)
                        .addValue("status", status)
                        .addValue("error", error)
                        .addValue("availableAt", offset(availableAt)));
    }

    @Override
    public List<OutboxMessage> claimDueOutbox(int limit) {
        List<OutboxMessage> due = jdbc.query(
                """
                SELECT id, aggregate_id, event_type
                FROM integration_outbox
                WHERE status = 'PENDING' AND destination = 'core-banking' AND available_at <= NOW()
                ORDER BY created_at
                LIMIT :limit
                FOR UPDATE SKIP LOCKED
                """,
                new MapSqlParameterSource("limit", limit),
                (rs, rowNum) -> new OutboxMessage(
                        rs.getObject("id", UUID.class),
                        UUID.fromString(rs.getString("aggregate_id")),
                        rs.getString("event_type")));
        Instant until = Instant.now(clock).plusSeconds(120);
        for (OutboxMessage message : due) {
            jdbc.update(
                    """
                    UPDATE integration_outbox
                    SET available_at = :availableAt, attempt_count = attempt_count + 1
                    WHERE id = :id
                    """,
                    new MapSqlParameterSource().addValue("id", message.id()).addValue("availableAt", offset(until)));
        }
        return due;
    }

    @Override
    public void insertError(String operation, String reference, String category, String message, boolean deadLetter, String payload) {
        jdbc.update(
                """
                INSERT INTO integration_errors (id, source, operation, reference, category, message, dead_letter, payload)
                VALUES (:id, 'core-banking', :operation, :reference, :category, :message, :deadLetter, CAST(:payload AS jsonb))
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("operation", operation)
                        .addValue("reference", reference)
                        .addValue("category", category)
                        .addValue("message", trim(message))
                        .addValue("deadLetter", deadLetter)
                        .addValue("payload", payload == null ? "{}" : payload));
    }

    @Override
    public List<DeadLetter> unresolvedDeadLetters() {
        return jdbc.query(
                """
                SELECT id, operation, reference, category, message
                FROM integration_errors
                WHERE dead_letter = TRUE AND resolved_at IS NULL
                ORDER BY created_at
                """,
                new MapSqlParameterSource(),
                (rs, rowNum) -> new DeadLetter(
                        rs.getObject("id", UUID.class),
                        rs.getString("operation"),
                        rs.getString("reference"),
                        rs.getString("category"),
                        rs.getString("message")));
    }

    @Override
    public void resolveErrors(String reference) {
        jdbc.update(
                """
                UPDATE integration_errors
                SET resolved_at = NOW()
                WHERE reference = :reference AND dead_letter = TRUE AND resolved_at IS NULL
                """,
                new MapSqlParameterSource("reference", reference));
    }

    @Override
    public UUID openBatch() {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO reconciliation_batches (id, source, status)
                VALUES (:id, 'core-banking', 'OPEN')
                """,
                new MapSqlParameterSource("id", id));
        return id;
    }

    @Override
    public void addItem(
            UUID batchId,
            UUID transactionId,
            String externalReference,
            BigDecimal expectedAmount,
            BigDecimal actualAmount,
            String status,
            String detail) {
        jdbc.update(
                """
                INSERT INTO reconciliation_items (
                    id, batch_id, transaction_id, external_reference, expected_amount, actual_amount, status, detail)
                VALUES (
                    :id, :batchId, :transactionId, :externalReference, :expectedAmount, :actualAmount, :status, :detail)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("batchId", batchId)
                        .addValue("transactionId", transactionId)
                        .addValue("externalReference", externalReference)
                        .addValue("expectedAmount", expectedAmount)
                        .addValue("actualAmount", actualAmount)
                        .addValue("status", status)
                        .addValue("detail", detail));
    }

    @Override
    public void closeBatch(UUID batchId, int mismatches) {
        jdbc.update(
                """
                UPDATE reconciliation_batches
                SET status = 'COMPLETED', mismatch_count = :mismatches, completed_at = NOW()
                WHERE id = :id
                """,
                new MapSqlParameterSource().addValue("id", batchId).addValue("mismatches", mismatches));
    }

    @Override
    public int countDeadLetters() {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM integration_errors WHERE dead_letter = TRUE AND resolved_at IS NULL",
                new MapSqlParameterSource(),
                Integer.class);
        return count == null ? 0 : count;
    }

    private CoreTransaction saveAttempt(CoreTransaction current, TransactionPatch patch) {
        return save(current, current.apply(patch, Instant.now(clock)), null, null);
    }

    private CoreTransaction save(CoreTransaction current, CoreTransaction next, FinancialTransactionState from, String reason) {
        int updated = jdbc.update(
                """
                UPDATE core_banking_transactions
                SET state = :state,
                    stage = :stage,
                    applied_amount = :appliedAmount,
                    provider_reference = :providerReference,
                    loan_account_reference = :loanAccountReference,
                    loan_attempt = :loanAttempt,
                    payment_attempt = :paymentAttempt,
                    version = :version,
                    last_error = :lastError,
                    resolved_at = :resolvedAt
                WHERE id = :id AND version = :expectedVersion
                """,
                params(next).addValue("expectedVersion", current.version()));
        if (updated != 1) {
            throw new ConflictException("The core banking transaction was updated by another process");
        }
        if (from != null && from != next.state()) {
            jdbc.update(
                    """
                    INSERT INTO core_banking_transaction_events (id, transaction_id, from_state, to_state, reason)
                    VALUES (:id, :transactionId, :fromState, :toState, :reason)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", UUID.randomUUID())
                            .addValue("transactionId", next.id())
                            .addValue("fromState", from.name())
                            .addValue("toState", next.state().name())
                            .addValue("reason", reason));
        }
        return next;
    }

    private CoreTransaction lock(UUID id) {
        return one("SELECT " + COLUMNS + " FROM core_banking_transactions WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", id))
                .orElseThrow(() -> new com.mfb.lending.common.exception.ResourceNotFoundException("Core banking transaction"));
    }

    private Optional<CoreTransaction> one(String sql, MapSqlParameterSource params) {
        List<CoreTransaction> rows = jdbc.query(sql, params, (rs, rowNum) -> map(rs));
        return rows.stream().findFirst();
    }

    private static CoreTransaction map(ResultSet rs) throws SQLException {
        return new CoreTransaction(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getString("idempotency_key"),
                FinancialTransactionKind.valueOf(rs.getString("kind")),
                FinancialTransactionState.valueOf(rs.getString("state")),
                InstructionStage.valueOf(rs.getString("stage")),
                rs.getObject("application_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("account_id", UUID.class),
                rs.getObject("related_transaction_id", UUID.class),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("applied_amount"),
                rs.getString("currency_code"),
                rs.getString("provider_reference"),
                rs.getString("loan_account_reference"),
                rs.getInt("loan_attempt"),
                rs.getInt("payment_attempt"),
                rs.getInt("version"),
                rs.getString("correlation_id"),
                rs.getString("last_error"),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_name"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                instant(rs, "resolved_at"));
    }

    private static MapSqlParameterSource params(CoreTransaction transaction) {
        return new MapSqlParameterSource()
                .addValue("id", transaction.id())
                .addValue("reference", transaction.reference())
                .addValue("idempotencyKey", transaction.idempotencyKey())
                .addValue("kind", transaction.kind().name())
                .addValue("state", transaction.state().name())
                .addValue("stage", transaction.stage().name())
                .addValue("applicationId", transaction.applicationId())
                .addValue("customerId", transaction.customerId())
                .addValue("accountId", transaction.accountId())
                .addValue("relatedTransactionId", transaction.relatedTransactionId())
                .addValue("amount", transaction.amount())
                .addValue("appliedAmount", transaction.appliedAmount())
                .addValue("currencyCode", transaction.currencyCode())
                .addValue("providerReference", transaction.providerReference())
                .addValue("loanAccountReference", transaction.loanAccountReference())
                .addValue("loanAttempt", transaction.loanAttempt())
                .addValue("paymentAttempt", transaction.paymentAttempt())
                .addValue("version", transaction.version())
                .addValue("correlationId", transaction.correlationId())
                .addValue("lastError", transaction.lastError())
                .addValue("actorId", transaction.actorId())
                .addValue("actorName", transaction.actorName())
                .addValue("createdAt", offset(transaction.createdAt()))
                .addValue("updatedAt", offset(transaction.updatedAt()))
                .addValue("resolvedAt", offset(transaction.resolvedAt()));
    }

    private static String filter(String state, String kind) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (state != null && !state.isBlank()) {
            where.append(" AND state = :state");
        }
        if (kind != null && !kind.isBlank()) {
            where.append(" AND kind = :kind");
        }
        return where.toString();
    }

    private static MapSqlParameterSource filterParams(String state, String kind) {
        return new MapSqlParameterSource().addValue("state", state).addValue("kind", kind);
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String trim(String message) {
        if (message == null) {
            return "Core banking integration error";
        }
        return message.length() <= 2000 ? message : message.substring(0, 2000);
    }
}
