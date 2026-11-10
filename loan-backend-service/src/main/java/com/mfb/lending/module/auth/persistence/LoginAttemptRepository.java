package com.mfb.lending.module.auth.persistence;

import java.time.Instant;
import java.util.UUID;

import com.mfb.lending.module.auth.domain.PrincipalType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LoginAttemptRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public LoginAttemptRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID userId,
            String identifier,
            PrincipalType principalType,
            boolean success,
            String failureReason,
            String ipAddress,
            String userAgent,
            Instant occurredAt) {
        jdbc.update(
                """
                INSERT INTO login_attempts (
                    id, user_id, identifier, principal_type, success, failure_reason, ip_address, user_agent, occurred_at)
                VALUES (
                    :id, :userId, :identifier, :principalType, :success, :failureReason, :ipAddress, :userAgent, :occurredAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("userId", userId)
                        .addValue("identifier", identifier)
                        .addValue("principalType", principalType.name())
                        .addValue("success", success)
                        .addValue("failureReason", failureReason)
                        .addValue("ipAddress", ipAddress)
                        .addValue("userAgent", userAgent)
                        .addValue("occurredAt", JdbcTimes.offset(occurredAt)));
    }

    public long countSince(String ipAddress, String identifier, Instant since) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM login_attempts
                WHERE occurred_at >= :since
                  AND (ip_address = :ipAddress OR identifier = :identifier)
                """,
                new MapSqlParameterSource()
                        .addValue("since", JdbcTimes.offset(since))
                        .addValue("ipAddress", ipAddress)
                        .addValue("identifier", identifier),
                Long.class);
        return count == null ? 0 : count;
    }
}
