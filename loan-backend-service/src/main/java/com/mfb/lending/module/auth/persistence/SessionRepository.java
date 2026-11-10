package com.mfb.lending.module.auth.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.auth.domain.SessionRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SessionRepository {

    private static final RowMapper<SessionRecord> MAPPER = (rs, rowNum) -> new SessionRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getString("status"),
            rs.getString("ip_address"),
            rs.getString("user_agent"),
            JdbcTimes.instant(rs, "created_at"),
            JdbcTimes.instant(rs, "last_seen_at"),
            JdbcTimes.instant(rs, "expires_at"),
            JdbcTimes.instant(rs, "revoked_at"),
            rs.getString("revoked_reason"));

    private final NamedParameterJdbcTemplate jdbc;

    public SessionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(SessionRecord session) {
        jdbc.update(
                """
                INSERT INTO user_sessions (
                    id, user_id, status, ip_address, user_agent, created_at, last_seen_at, expires_at)
                VALUES (
                    :id, :userId, :status, :ipAddress, :userAgent, :createdAt, :lastSeenAt, :expiresAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", session.id())
                        .addValue("userId", session.userId())
                        .addValue("status", session.status())
                        .addValue("ipAddress", session.ipAddress())
                        .addValue("userAgent", session.userAgent())
                        .addValue("createdAt", JdbcTimes.offset(session.createdAt()))
                        .addValue("lastSeenAt", JdbcTimes.offset(session.lastSeenAt()))
                        .addValue("expiresAt", JdbcTimes.offset(session.expiresAt())));
    }

    public Optional<SessionRecord> findById(UUID id) {
        List<SessionRecord> rows = jdbc.query(
                "SELECT * FROM user_sessions WHERE id = :id",
                new MapSqlParameterSource("id", id),
                MAPPER);
        return rows.stream().findFirst();
    }

    public void touch(UUID id, Instant now) {
        jdbc.update(
                """
                UPDATE user_sessions
                SET last_seen_at = :now
                WHERE id = :id AND status = 'ACTIVE' AND last_seen_at < :threshold
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("now", JdbcTimes.offset(now))
                        .addValue("threshold", JdbcTimes.offset(now.minusSeconds(60))));
    }

    public void extend(UUID id, Instant now, Instant expiresAt) {
        jdbc.update(
                """
                UPDATE user_sessions
                SET last_seen_at = :now, expires_at = :expiresAt
                WHERE id = :id AND status = 'ACTIVE'
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("now", JdbcTimes.offset(now))
                        .addValue("expiresAt", JdbcTimes.offset(expiresAt)));
    }

    public void revoke(UUID id, Instant now, String reason) {
        jdbc.update(
                """
                UPDATE user_sessions
                SET status = 'REVOKED', revoked_at = :now, revoked_reason = :reason
                WHERE id = :id AND status = 'ACTIVE'
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("now", JdbcTimes.offset(now))
                        .addValue("reason", reason));
    }

    public int revokeAll(UUID userId, Instant now, String reason) {
        return jdbc.update(
                """
                UPDATE user_sessions
                SET status = 'REVOKED', revoked_at = :now, revoked_reason = :reason
                WHERE user_id = :userId AND status = 'ACTIVE'
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("now", JdbcTimes.offset(now))
                        .addValue("reason", reason));
    }

    public List<SessionRecord> listByUser(UUID userId, int limit) {
        return jdbc.query(
                """
                SELECT * FROM user_sessions
                WHERE user_id = :userId
                ORDER BY created_at DESC
                LIMIT :limit
                """,
                new MapSqlParameterSource().addValue("userId", userId).addValue("limit", limit),
                MAPPER);
    }
}
