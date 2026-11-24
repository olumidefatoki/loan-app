package com.mfb.lending.module.notification.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.notification.domain.NotificationModels.DeliveryLog;
import com.mfb.lending.module.notification.domain.NotificationModels.MessageQuery;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationMessage;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationPreference;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcNotificationStore implements NotificationStore {

    private static final String MESSAGE_COLUMNS = """
            id, customer_id, user_id, event_type, channel, template_code, subject, body, payload::text AS payload,
            status, dedupe_key, attempt_count, max_attempts, next_attempt_at, provider_code, provider_message_id,
            last_error, correlation_id, created_at, sent_at, read_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcNotificationStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<NotificationTemplate> activeTemplate(String eventType, String channel, String locale) {
        List<NotificationTemplate> rows = jdbc.query(
                """
                SELECT * FROM notification_templates
                WHERE event_type = :eventType AND channel = :channel AND locale = :locale AND active = TRUE
                """,
                new MapSqlParameterSource().addValue("eventType", eventType).addValue("channel", channel).addValue("locale", locale),
                (rs, row) -> template(rs));
        return rows.stream().findFirst();
    }

    @Override
    public boolean insertMessage(NotificationMessage message) {
        int updated = jdbc.update(
                """
                INSERT INTO notifications (
                    id, customer_id, user_id, event_type, channel, template_code, subject, body, payload, status,
                    dedupe_key, attempt_count, max_attempts, next_attempt_at, provider_code, provider_message_id,
                    last_error, correlation_id, created_at, sent_at, read_at)
                VALUES (
                    :id, :customerId, :userId, :eventType, :channel, :templateCode, :subject, :body, CAST(:payload AS JSONB),
                    :status, :dedupeKey, :attemptCount, :maxAttempts, :nextAttemptAt, :providerCode, :providerMessageId,
                    :lastError, :correlationId, :createdAt, :sentAt, :readAt)
                ON CONFLICT (dedupe_key, channel) DO NOTHING
                """,
                messageParams(message));
        return updated == 1;
    }

    @Override
    public void recoverStuck(Instant staleBefore) {
        jdbc.update(
                """
                UPDATE notifications
                SET status = 'FAILED', last_error = 'Dispatch interrupted'
                WHERE status = 'DISPATCHING' AND next_attempt_at < :staleBefore
                """,
                new MapSqlParameterSource("staleBefore", offset(staleBefore)));
    }

    @Override
    @Transactional
    public List<NotificationMessage> claimDue(Instant now, int limit) {
        return jdbc.query(
                """
                WITH due AS (
                    SELECT id FROM notifications
                    WHERE status IN ('PENDING', 'FAILED')
                      AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                    ORDER BY created_at
                    LIMIT :limit
                    FOR UPDATE SKIP LOCKED
                )
                UPDATE notifications AS n
                SET status = 'DISPATCHING', next_attempt_at = :now
                FROM due
                WHERE n.id = due.id
                RETURNING
                """ + MESSAGE_COLUMNS,
                new MapSqlParameterSource().addValue("now", offset(now)).addValue("limit", limit),
                (rs, row) -> message(rs));
    }

    @Override
    public void markSent(UUID id, String providerCode, String providerMessageId, Instant sentAt) {
        jdbc.update(
                """
                UPDATE notifications
                SET status = 'SENT', provider_code = :providerCode, provider_message_id = :providerMessageId,
                    sent_at = :sentAt, last_error = NULL
                WHERE id = :id AND status IN ('PENDING', 'DISPATCHING', 'FAILED')
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("providerCode", providerCode)
                        .addValue("providerMessageId", providerMessageId)
                        .addValue("sentAt", offset(sentAt)));
    }

    @Override
    public void markFailed(UUID id, int attemptCount, Instant nextAttemptAt, String error) {
        jdbc.update(
                """
                UPDATE notifications
                SET status = 'FAILED', attempt_count = :attemptCount, next_attempt_at = :nextAttemptAt, last_error = :error
                WHERE id = :id AND status = 'DISPATCHING'
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("attemptCount", attemptCount)
                        .addValue("nextAttemptAt", offset(nextAttemptAt))
                        .addValue("error", error));
    }

    @Override
    public void markDead(UUID id, int attemptCount, String error) {
        jdbc.update(
                """
                UPDATE notifications
                SET status = 'DEAD', attempt_count = :attemptCount, next_attempt_at = NULL, last_error = :error
                WHERE id = :id AND status IN ('PENDING', 'DISPATCHING', 'FAILED')
                """,
                new MapSqlParameterSource().addValue("id", id).addValue("attemptCount", attemptCount).addValue("error", error));
    }

    @Override
    public void markSkipped(UUID id, String error) {
        jdbc.update(
                """
                UPDATE notifications
                SET status = 'SKIPPED', next_attempt_at = NULL, last_error = :error
                WHERE id = :id AND status = 'DISPATCHING'
                """,
                new MapSqlParameterSource().addValue("id", id).addValue("error", error));
    }

    @Override
    public boolean markRead(UUID id, Instant readAt) {
        return jdbc.update(
                """
                UPDATE notifications
                SET read_at = :readAt,
                    status = CASE WHEN channel = 'IN_APP' AND status = 'SENT' THEN 'READ' ELSE status END
                WHERE id = :id AND read_at IS NULL AND status IN ('SENT', 'READ')
                """,
                new MapSqlParameterSource().addValue("id", id).addValue("readAt", offset(readAt))) == 1;
    }

    @Override
    public void insertDelivery(DeliveryLog log) {
        jdbc.update(
                """
                INSERT INTO notification_delivery_logs (
                    id, notification_id, attempt_number, provider_code, status, provider_message_id, error_message, attempted_at)
                VALUES (:id, :notificationId, :attemptNumber, :providerCode, :status, :providerMessageId, :errorMessage, :attemptedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", log.id())
                        .addValue("notificationId", log.notificationId())
                        .addValue("attemptNumber", log.attemptNumber())
                        .addValue("providerCode", log.providerCode())
                        .addValue("status", log.status())
                        .addValue("providerMessageId", log.providerMessageId())
                        .addValue("errorMessage", log.errorMessage())
                        .addValue("attemptedAt", offset(log.attemptedAt())));
    }

    @Override
    public Optional<NotificationMessage> findMessage(UUID id) {
        List<NotificationMessage> rows = jdbc.query(
                "SELECT " + MESSAGE_COLUMNS + " FROM notifications WHERE id = :id",
                new MapSqlParameterSource("id", id),
                (rs, row) -> message(rs));
        return rows.stream().findFirst();
    }

    @Override
    public long countMessages(MessageQuery query) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE " + filters(query),
                queryParams(query),
                Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<NotificationMessage> listMessages(MessageQuery query) {
        return jdbc.query(
                "SELECT " + MESSAGE_COLUMNS + " FROM notifications WHERE " + filters(query)
                        + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset",
                queryParams(query),
                (rs, row) -> message(rs));
    }

    @Override
    public List<NotificationPreference> preferences(UUID customerId) {
        return jdbc.query(
                "SELECT * FROM notification_preferences WHERE customer_id = :customerId ORDER BY channel, event_type",
                new MapSqlParameterSource("customerId", customerId),
                (rs, row) -> new NotificationPreference(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("channel"),
                        rs.getString("event_type"),
                        rs.getBoolean("enabled"),
                        instant(rs, "updated_at")));
    }

    @Override
    @Transactional
    public void replacePreferences(UUID customerId, List<NotificationPreference> preferences) {
        jdbc.update("DELETE FROM notification_preferences WHERE customer_id = :customerId", new MapSqlParameterSource("customerId", customerId));
        for (NotificationPreference preference : preferences) {
            jdbc.update(
                    """
                    INSERT INTO notification_preferences (id, customer_id, channel, event_type, enabled, updated_at)
                    VALUES (:id, :customerId, :channel, :eventType, :enabled, :updatedAt)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", preference.id())
                            .addValue("customerId", preference.customerId())
                            .addValue("channel", preference.channel())
                            .addValue("eventType", preference.eventType())
                            .addValue("enabled", preference.enabled())
                            .addValue("updatedAt", offset(preference.updatedAt())));
        }
    }

    @Override
    public NotificationTemplate insertTemplate(NotificationTemplate template) {
        jdbc.update(
                """
                INSERT INTO notification_templates (
                    id, code, channel, event_type, locale, subject, body, active, version, created_at, updated_at, created_by, updated_by)
                VALUES (
                    :id, :code, :channel, :eventType, :locale, :subject, :body, :active, :version, :createdAt, :updatedAt, :createdBy, :updatedBy)
                """,
                templateParams(template));
        return template;
    }

    @Override
    public Optional<NotificationTemplate> findTemplate(UUID id) {
        List<NotificationTemplate> rows = jdbc.query(
                "SELECT * FROM notification_templates WHERE id = :id",
                new MapSqlParameterSource("id", id),
                (rs, row) -> template(rs));
        return rows.stream().findFirst();
    }

    @Override
    public List<NotificationTemplate> listTemplates(int limit, int offset) {
        return jdbc.query(
                "SELECT * FROM notification_templates ORDER BY event_type, channel, version DESC LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset),
                (rs, row) -> template(rs));
    }

    @Override
    public long countTemplates() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM notification_templates", new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public void deactivateTemplate(UUID id, String actor) {
        jdbc.update(
                "UPDATE notification_templates SET active = FALSE, updated_by = :actor WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("actor", actor));
    }

    @Override
    public long countByStatus(String status) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM notifications WHERE status = :status",
                new MapSqlParameterSource("status", status),
                Long.class);
        return count == null ? 0 : count;
    }

    private static String filters(MessageQuery query) {
        return """
                (CAST(:customerId AS UUID) IS NULL OR customer_id = CAST(:customerId AS UUID))
                AND (CAST(:userId AS UUID) IS NULL OR user_id = CAST(:userId AS UUID))
                AND (CAST(:status AS TEXT) IS NULL OR status = CAST(:status AS TEXT))
                """;
    }

    private static MapSqlParameterSource queryParams(MessageQuery query) {
        return new MapSqlParameterSource()
                .addValue("customerId", query.customerId())
                .addValue("userId", query.userId())
                .addValue("status", query.status())
                .addValue("limit", query.limit())
                .addValue("offset", query.offset());
    }

    private static MapSqlParameterSource messageParams(NotificationMessage message) {
        return new MapSqlParameterSource()
                .addValue("id", message.id())
                .addValue("customerId", message.customerId())
                .addValue("userId", message.userId())
                .addValue("eventType", message.eventType())
                .addValue("channel", message.channel())
                .addValue("templateCode", message.templateCode())
                .addValue("subject", message.subject())
                .addValue("body", message.body())
                .addValue("payload", message.payload())
                .addValue("status", message.status())
                .addValue("dedupeKey", message.dedupeKey())
                .addValue("attemptCount", message.attemptCount())
                .addValue("maxAttempts", message.maxAttempts())
                .addValue("nextAttemptAt", offset(message.nextAttemptAt()))
                .addValue("providerCode", message.providerCode())
                .addValue("providerMessageId", message.providerMessageId())
                .addValue("lastError", message.lastError())
                .addValue("correlationId", message.correlationId())
                .addValue("createdAt", offset(message.createdAt()))
                .addValue("sentAt", offset(message.sentAt()))
                .addValue("readAt", offset(message.readAt()));
    }

    private static MapSqlParameterSource templateParams(NotificationTemplate template) {
        return new MapSqlParameterSource()
                .addValue("id", template.id())
                .addValue("code", template.code())
                .addValue("channel", template.channel())
                .addValue("eventType", template.eventType())
                .addValue("locale", template.locale())
                .addValue("subject", template.subject())
                .addValue("body", template.body())
                .addValue("active", template.active())
                .addValue("version", template.version())
                .addValue("createdAt", offset(template.createdAt()))
                .addValue("updatedAt", offset(template.updatedAt()))
                .addValue("createdBy", template.createdBy())
                .addValue("updatedBy", template.updatedBy());
    }

    private static NotificationMessage message(ResultSet rs) throws SQLException {
        return new NotificationMessage(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("user_id", UUID.class),
                rs.getString("event_type"),
                rs.getString("channel"),
                rs.getString("template_code"),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getString("payload"),
                rs.getString("status"),
                rs.getString("dedupe_key"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                instant(rs, "next_attempt_at"),
                rs.getString("provider_code"),
                rs.getString("provider_message_id"),
                rs.getString("last_error"),
                rs.getString("correlation_id"),
                instant(rs, "created_at"),
                instant(rs, "sent_at"),
                instant(rs, "read_at"));
    }

    private static NotificationTemplate template(ResultSet rs) throws SQLException {
        return new NotificationTemplate(
                rs.getObject("id", UUID.class),
                rs.getString("code"),
                rs.getString("channel"),
                rs.getString("event_type"),
                rs.getString("locale"),
                rs.getString("subject"),
                rs.getString("body"),
                rs.getBoolean("active"),
                rs.getInt("version"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                rs.getString("created_by"),
                rs.getString("updated_by"));
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
