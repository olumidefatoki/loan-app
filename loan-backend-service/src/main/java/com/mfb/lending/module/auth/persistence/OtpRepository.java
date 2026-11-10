package com.mfb.lending.module.auth.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.auth.domain.OtpChannel;
import com.mfb.lending.module.auth.domain.OtpPurpose;
import com.mfb.lending.module.auth.domain.OtpRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OtpRepository {

    private static final RowMapper<OtpRecord> MAPPER = (rs, rowNum) -> new OtpRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            OtpPurpose.valueOf(rs.getString("purpose")),
            OtpChannel.valueOf(rs.getString("channel")),
            rs.getString("destination"),
            rs.getString("code_hash"),
            JdbcTimes.instant(rs, "expires_at"),
            rs.getInt("attempt_count"),
            rs.getInt("max_attempts"),
            JdbcTimes.instant(rs, "consumed_at"),
            JdbcTimes.instant(rs, "created_at"));

    private final NamedParameterJdbcTemplate jdbc;

    public OtpRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(OtpRecord otp) {
        jdbc.update(
                """
                INSERT INTO otp_records (
                    id, user_id, purpose, channel, destination, code_hash, expires_at, attempt_count, max_attempts, created_at)
                VALUES (
                    :id, :userId, :purpose, :channel, :destination, :codeHash, :expiresAt, :attemptCount, :maxAttempts, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", otp.id())
                        .addValue("userId", otp.userId())
                        .addValue("purpose", otp.purpose().name())
                        .addValue("channel", otp.channel().name())
                        .addValue("destination", otp.destination())
                        .addValue("codeHash", otp.codeHash())
                        .addValue("expiresAt", JdbcTimes.offset(otp.expiresAt()))
                        .addValue("attemptCount", otp.attemptCount())
                        .addValue("maxAttempts", otp.maxAttempts())
                        .addValue("createdAt", JdbcTimes.offset(otp.createdAt())));
    }

    public Optional<OtpRecord> findLatest(UUID userId, OtpPurpose purpose) {
        List<OtpRecord> rows = jdbc.query(
                """
                SELECT * FROM otp_records
                WHERE user_id = :userId AND purpose = :purpose
                ORDER BY created_at DESC
                LIMIT 1
                """,
                new MapSqlParameterSource().addValue("userId", userId).addValue("purpose", purpose.name()),
                MAPPER);
        return rows.stream().findFirst();
    }

    public void supersedeOpen(UUID userId, OtpPurpose purpose, Instant consumedAt) {
        jdbc.update(
                """
                UPDATE otp_records
                SET consumed_at = :consumedAt
                WHERE user_id = :userId AND purpose = :purpose AND consumed_at IS NULL
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("purpose", purpose.name())
                        .addValue("consumedAt", JdbcTimes.offset(consumedAt)));
    }

    public void consume(UUID id, Instant consumedAt) {
        jdbc.update(
                "UPDATE otp_records SET consumed_at = :consumedAt WHERE id = :id AND consumed_at IS NULL",
                new MapSqlParameterSource().addValue("id", id).addValue("consumedAt", JdbcTimes.offset(consumedAt)));
    }

    public void recordFailure(UUID id, int attemptCount, Instant consumedAt) {
        jdbc.update(
                """
                UPDATE otp_records
                SET attempt_count = :attemptCount, consumed_at = :consumedAt
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("attemptCount", attemptCount)
                        .addValue("consumedAt", JdbcTimes.offset(consumedAt)));
    }
}
