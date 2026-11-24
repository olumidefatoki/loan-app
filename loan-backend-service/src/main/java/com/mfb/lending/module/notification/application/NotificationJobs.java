package com.mfb.lending.module.notification.application;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class NotificationJobs {

    private static final Logger log = LoggerFactory.getLogger(NotificationJobs.class);
    private static final Set<String> ALLOWED = Set.of(
            "loanReference", "reference", "amount", "dueDate", "daysPastDue", "overdueAmount", "promiseDate", "promiseAmount", "decision");
    private static final Map<String, String> EVENTS = Map.of(
            "UPCOMING_INSTALMENT", "UPCOMING_REPAYMENT",
            "DUE_DATE_REMINDER", "UPCOMING_REPAYMENT",
            "MISSED_REPAYMENT", "OVERDUE_REPAYMENT",
            "OVERDUE_BALANCE", "OVERDUE_REPAYMENT",
            "PAYMENT_CONFIRMATION", "PAYMENT_CONFIRMATION",
            "PAYMENT_FOLLOW_UP", "PAYMENT_CONFIRMATION");

    private final NotificationEngine engine;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final com.mfb.lending.config.PlatformOperationsProperties properties;
    private final java.time.Clock clock;

    public NotificationJobs(
            NotificationEngine engine,
            NamedParameterJdbcTemplate jdbc,
            ObjectMapper objectMapper,
            com.mfb.lending.config.PlatformOperationsProperties properties,
            java.time.Clock clock) {
        this.engine = engine;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.platform.notification-poll-ms:15000}")
    public void poll() {
        if (!properties.isJobsEnabled()) {
            return;
        }
        importCollectionOutbox();
        engine.dispatchDue();
    }

    public int importCollectionOutbox() {
        int imported = 0;
        var pending = jdbc.query(
                """
                SELECT id, customer_id, template_code, payload::text AS payload
                FROM collection_notifications
                WHERE status = 'PENDING'
                ORDER BY created_at
                LIMIT 100
                """,
                (rs, row) -> new Pending(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("template_code"),
                        rs.getString("payload")));
        for (Pending notice : pending) {
            String event = EVENTS.get(notice.templateCode());
            if (event == null) {
                mark(notice.id(), "SKIPPED");
                continue;
            }
            engine.publish(NotificationEvent.of(event, notice.customerId(), null, "collection:" + notice.id(), fields(notice.payload())));
            mark(notice.id(), "SENT");
            imported++;
        }
        return imported;
    }

    private void mark(UUID id, String status) {
        jdbc.update(
                "UPDATE collection_notifications SET status = :status, sent_at = :sentAt WHERE id = :id AND status = 'PENDING'",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("status", status)
                        .addValue("sentAt", "SENT".equals(status) ? java.time.OffsetDateTime.ofInstant(Instant.now(clock), java.time.ZoneOffset.UTC) : null));
    }

    private Map<String, String> fields(String payload) {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            Map<String, Object> parsed = objectMapper.readValue(payload, new TypeReference<>() {
            });
            parsed.forEach((key, value) -> {
                if (ALLOWED.contains(key) && value != null) {
                    values.put("loanReference".equals(key) ? "reference" : key, String.valueOf(value));
                }
            });
        } catch (Exception ex) {
            log.warn("Collection notification payload was ignored");
        }
        if (values.containsKey("reference") && !values.containsKey("amount") && values.containsKey("overdueAmount")) {
            values.put("amount", values.get("overdueAmount"));
        }
        return values;
    }

    private record Pending(UUID id, UUID customerId, String templateCode, String payload) {
    }
}
