package com.mfb.lending.module.auth.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.auth.domain.RefreshTokenRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RefreshTokenRepository {

    private static final RowMapper<RefreshTokenRecord> MAPPER = (rs, rowNum) -> new RefreshTokenRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("session_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("family_id", UUID.class),
            rs.getString("token_hash"),
            JdbcTimes.instant(rs, "expires_at"),
            JdbcTimes.instant(rs, "created_at"),
            JdbcTimes.instant(rs, "revoked_at"),
            rs.getObject("replaced_by", UUID.class));

    private final NamedParameterJdbcTemplate jdbc;

    public RefreshTokenRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(RefreshTokenRecord token) {
        jdbc.update(
                """
                INSERT INTO refresh_tokens (id, session_id, user_id, family_id, token_hash, expires_at, created_at)
                VALUES (:id, :sessionId, :userId, :familyId, :tokenHash, :expiresAt, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", token.id())
                        .addValue("sessionId", token.sessionId())
                        .addValue("userId", token.userId())
                        .addValue("familyId", token.familyId())
                        .addValue("tokenHash", token.tokenHash())
                        .addValue("expiresAt", JdbcTimes.offset(token.expiresAt()))
                        .addValue("createdAt", JdbcTimes.offset(token.createdAt())));
    }

    public Optional<RefreshTokenRecord> lockByHash(String tokenHash) {
        List<RefreshTokenRecord> rows = jdbc.query(
                "SELECT * FROM refresh_tokens WHERE token_hash = :tokenHash FOR UPDATE",
                new MapSqlParameterSource("tokenHash", tokenHash),
                MAPPER);
        return rows.stream().findFirst();
    }

    public void replace(UUID id, UUID replacedBy, Instant revokedAt) {
        jdbc.update(
                """
                UPDATE refresh_tokens
                SET revoked_at = :revokedAt, replaced_by = :replacedBy
                WHERE id = :id AND revoked_at IS NULL
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("replacedBy", replacedBy)
                        .addValue("revokedAt", JdbcTimes.offset(revokedAt)));
    }

    public void revokeFamily(UUID familyId, Instant revokedAt) {
        jdbc.update(
                """
                UPDATE refresh_tokens
                SET revoked_at = :revokedAt
                WHERE family_id = :familyId AND revoked_at IS NULL
                """,
                new MapSqlParameterSource()
                        .addValue("familyId", familyId)
                        .addValue("revokedAt", JdbcTimes.offset(revokedAt)));
    }

    public void revokeSession(UUID sessionId, Instant revokedAt) {
        jdbc.update(
                """
                UPDATE refresh_tokens
                SET revoked_at = :revokedAt
                WHERE session_id = :sessionId AND revoked_at IS NULL
                """,
                new MapSqlParameterSource()
                        .addValue("sessionId", sessionId)
                        .addValue("revokedAt", JdbcTimes.offset(revokedAt)));
    }

    public void revokeUser(UUID userId, Instant revokedAt) {
        jdbc.update(
                """
                UPDATE refresh_tokens
                SET revoked_at = :revokedAt
                WHERE user_id = :userId AND revoked_at IS NULL
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("revokedAt", JdbcTimes.offset(revokedAt)));
    }
}
