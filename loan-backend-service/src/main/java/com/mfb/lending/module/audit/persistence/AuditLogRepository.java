package com.mfb.lending.module.audit.persistence;

import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AuditLogRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public AuditLogRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            String correlationId,
            String actor,
            String action,
            String entityType,
            String entityId,
            String beforeState,
            String afterState,
            String ipAddress,
            String userAgent) {
        jdbc.update(
                """
                INSERT INTO audit_logs (
                    id, correlation_id, actor, action, entity_type, entity_id, before_state, after_state, ip_address, user_agent)
                VALUES (
                    :id, :correlationId, :actor, :action, :entityType, :entityId,
                    CAST(:beforeState AS JSONB), CAST(:afterState AS JSONB), :ipAddress, :userAgent)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("correlationId", correlationId)
                        .addValue("actor", actor)
                        .addValue("action", action)
                        .addValue("entityType", entityType)
                        .addValue("entityId", entityId)
                        .addValue("beforeState", beforeState)
                        .addValue("afterState", afterState)
                        .addValue("ipAddress", ipAddress)
                        .addValue("userAgent", userAgent));
    }
}
