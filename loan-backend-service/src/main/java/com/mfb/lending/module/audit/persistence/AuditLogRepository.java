package com.mfb.lending.module.audit.persistence;

import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AuditLogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AuditLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public void insert(
            String correlationId,
            String actor,
            UUID actorUserId,
            String actorRole,
            String action,
            String module,
            String entityType,
            String entityId,
            String beforeState,
            String afterState,
            String ipAddress,
            String userAgent,
            String outcome) {
        jdbc.update(
                """
                INSERT INTO audit_logs (
                    id, correlation_id, actor, actor_user_id, actor_role, action, module, entity_type, entity_id,
                    before_state, after_state, ip_address, user_agent, outcome)
                VALUES (
                    :id, :correlationId, :actor, :actorUserId, :actorRole, :action, :module, :entityType, :entityId,
                    CAST(:beforeState AS JSONB), CAST(:afterState AS JSONB), :ipAddress, :userAgent, :outcome)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("correlationId", correlationId)
                        .addValue("actor", actor)
                        .addValue("actorUserId", actorUserId)
                        .addValue("actorRole", clip(actorRole, 64))
                        .addValue("action", action)
                        .addValue("module", clip(module, 64))
                        .addValue("entityType", entityType)
                        .addValue("entityId", entityId)
                        .addValue("beforeState", beforeState)
                        .addValue("afterState", afterState)
                        .addValue("ipAddress", ipAddress)
                        .addValue("userAgent", userAgent)
                        .addValue("outcome", outcome == null ? "SUCCESS" : outcome));
    }

    private static String clip(String value, int max) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
    }
}
