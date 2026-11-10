package com.mfb.lending.module.auth.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.auth.domain.CustomerType;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    private static final String COLUMNS = """
            id, principal_type, customer_type, full_name, business_name, email, phone_number, password_hash,
            status, email_verified, phone_verified, failed_login_count, locked_until, password_changed_at,
            password_expires_at, mfa_enabled, mfa_secret_encrypted, mfa_last_accepted_step, created_at, updated_at,
            created_by, updated_by
            """;

    private static final RowMapper<UserRecord> MAPPER = (rs, rowNum) -> new UserRecord(
            rs.getObject("id", UUID.class),
            PrincipalType.valueOf(rs.getString("principal_type")),
            CustomerType.valueOf(rs.getString("customer_type")),
            rs.getString("full_name"),
            rs.getString("business_name"),
            rs.getString("email"),
            rs.getString("phone_number"),
            rs.getString("password_hash"),
            UserStatus.valueOf(rs.getString("status")),
            rs.getBoolean("email_verified"),
            rs.getBoolean("phone_verified"),
            rs.getInt("failed_login_count"),
            JdbcTimes.instant(rs, "locked_until"),
            JdbcTimes.instant(rs, "password_changed_at"),
            JdbcTimes.instant(rs, "password_expires_at"),
            rs.getBoolean("mfa_enabled"),
            rs.getString("mfa_secret_encrypted"),
            rs.getObject("mfa_last_accepted_step") == null ? null : rs.getLong("mfa_last_accepted_step"),
            JdbcTimes.instant(rs, "created_at"),
            JdbcTimes.instant(rs, "updated_at"),
            rs.getString("created_by"),
            rs.getString("updated_by"));

    private final NamedParameterJdbcTemplate jdbc;

    public UserRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(UserRecord user) {
        jdbc.update(
                """
                INSERT INTO system_users (
                    id, principal_type, customer_type, full_name, business_name, email, phone_number, password_hash,
                    status, email_verified, phone_verified, failed_login_count, password_changed_at, password_expires_at,
                    mfa_enabled, mfa_secret_encrypted, created_by, updated_by)
                VALUES (
                    :id, :principalType, :customerType, :fullName, :businessName, :email, :phoneNumber, :passwordHash,
                    :status, :emailVerified, :phoneVerified, :failedLoginCount, :passwordChangedAt, :passwordExpiresAt,
                    :mfaEnabled, :mfaSecretEncrypted, :createdBy, :updatedBy)
                """,
                userParams(user));
    }

    public Optional<UserRecord> findById(UUID id) {
        return queryOne("SELECT " + COLUMNS + " FROM system_users WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public Optional<UserRecord> lockById(UUID id) {
        return queryOne(
                "SELECT " + COLUMNS + " FROM system_users WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id));
    }

    public Optional<UserRecord> findByEmail(String email) {
        return queryOne(
                "SELECT " + COLUMNS + " FROM system_users WHERE email = :email",
                new MapSqlParameterSource("email", email));
    }

    public Optional<UserRecord> findByPhone(String phoneNumber) {
        return queryOne(
                "SELECT " + COLUMNS + " FROM system_users WHERE phone_number = :phoneNumber",
                new MapSqlParameterSource("phoneNumber", phoneNumber));
    }

    public Optional<UserRecord> findByIdentifier(String identifier) {
        if (identifier.indexOf('@') >= 0) {
            return findByEmail(identifier);
        }
        return findByPhone(identifier);
    }

    public boolean existsByEmail(String email) {
        return exists("SELECT 1 FROM system_users WHERE email = :email", new MapSqlParameterSource("email", email));
    }

    public boolean existsByPhone(String phoneNumber) {
        return exists(
                "SELECT 1 FROM system_users WHERE phone_number = :phoneNumber",
                new MapSqlParameterSource("phoneNumber", phoneNumber));
    }

    public void updateFailure(UUID id, int failedLoginCount, java.time.Instant lockedUntil, String updatedBy) {
        jdbc.update(
                """
                UPDATE system_users
                SET failed_login_count = :failedLoginCount,
                    locked_until = :lockedUntil,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("failedLoginCount", failedLoginCount)
                        .addValue("lockedUntil", JdbcTimes.offset(lockedUntil))
                        .addValue("updatedBy", updatedBy));
    }

    public void clearFailures(UUID id, String updatedBy) {
        jdbc.update(
                """
                UPDATE system_users
                SET failed_login_count = 0, locked_until = NULL, updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource().addValue("id", id).addValue("updatedBy", updatedBy));
    }

    public void markEmailVerified(UUID id) {
        jdbc.update(
                "UPDATE system_users SET email_verified = TRUE, updated_by = 'auth' WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    public void markPhoneVerified(UUID id) {
        jdbc.update(
                "UPDATE system_users SET phone_verified = TRUE, updated_by = 'auth' WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    public void activateIfVerified(UUID id) {
        jdbc.update(
                """
                UPDATE system_users
                SET status = 'ACTIVE', updated_by = 'auth'
                WHERE id = :id
                  AND email_verified = TRUE
                  AND phone_verified = TRUE
                  AND status = 'PENDING_VERIFICATION'
                """,
                new MapSqlParameterSource("id", id));
    }

    public void updatePassword(UUID id, String passwordHash, java.time.Instant changedAt, java.time.Instant expiresAt) {
        jdbc.update(
                """
                UPDATE system_users
                SET password_hash = :passwordHash,
                    password_changed_at = :changedAt,
                    password_expires_at = :expiresAt,
                    failed_login_count = 0,
                    locked_until = NULL,
                    updated_by = 'auth'
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("passwordHash", passwordHash)
                        .addValue("changedAt", JdbcTimes.offset(changedAt))
                        .addValue("expiresAt", JdbcTimes.offset(expiresAt)));
    }

    public void updateStatus(UUID id, UserStatus status, String updatedBy) {
        jdbc.update(
                "UPDATE system_users SET status = :status, updated_by = :updatedBy WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("status", status.name())
                        .addValue("updatedBy", updatedBy));
    }

    public void updateProfile(
            UUID id,
            String fullName,
            String businessName,
            String email,
            String phoneNumber,
            String updatedBy) {
        jdbc.update(
                """
                UPDATE system_users
                SET full_name = :fullName,
                    business_name = :businessName,
                    email = :email,
                    phone_number = :phoneNumber,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("fullName", fullName)
                        .addValue("businessName", businessName)
                        .addValue("email", email)
                        .addValue("phoneNumber", phoneNumber)
                        .addValue("updatedBy", updatedBy));
    }

    public void updateMfa(UUID id, boolean enabled, String secretEncrypted, Long lastAcceptedStep, String updatedBy) {
        jdbc.update(
                """
                UPDATE system_users
                SET mfa_enabled = :enabled,
                    mfa_secret_encrypted = :secretEncrypted,
                    mfa_last_accepted_step = :lastAcceptedStep,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("enabled", enabled)
                        .addValue("secretEncrypted", secretEncrypted)
                        .addValue("lastAcceptedStep", lastAcceptedStep)
                        .addValue("updatedBy", updatedBy));
    }

    public List<UserRecord> search(UserSearch search) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(search, params);
        params.addValue("limit", search.limit());
        params.addValue("offset", search.offset());
        return jdbc.query(
                "SELECT " + COLUMNS + " FROM system_users " + where + " ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset",
                params,
                MAPPER);
    }

    public long count(UserSearch search) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(search, params);
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM system_users " + where, params, Long.class);
        return count == null ? 0 : count;
    }

    private static String where(UserSearch search, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder("WHERE 1 = 1");
        if (search.principalType() != null) {
            where.append(" AND principal_type = :principalType");
            params.addValue("principalType", search.principalType().name());
        }
        if (search.branchId() != null) {
            where.append("""
                     AND EXISTS (
                        SELECT 1 FROM user_branches ub
                        WHERE ub.user_id = system_users.id AND ub.branch_id = :branchId)
                    """);
            params.addValue("branchId", search.branchId());
        }
        if (!search.unrestricted()) {
            where.append("""
                     AND (
                        id = :actorId
                        OR EXISTS (
                            SELECT 1 FROM user_branches ub
                            WHERE ub.user_id = system_users.id AND ub.branch_id IN (:branchIds))
                     )
                    """);
            params.addValue("actorId", search.actorId());
            params.addValue("branchIds", search.branchIds().isEmpty()
                    ? List.of(UUID.fromString("00000000-0000-0000-0000-000000000000"))
                    : search.branchIds());
        }
        return where.toString();
    }

    private Optional<UserRecord> queryOne(String sql, MapSqlParameterSource params) {
        List<UserRecord> rows = jdbc.query(sql, params, MAPPER);
        return rows.stream().findFirst();
    }

    private boolean exists(String sql, MapSqlParameterSource params) {
        List<Integer> rows = jdbc.query(sql, params, (rs, rowNum) -> 1);
        return !rows.isEmpty();
    }

    private static MapSqlParameterSource userParams(UserRecord user) {
        return new MapSqlParameterSource()
                .addValue("id", user.id())
                .addValue("principalType", user.principalType().name())
                .addValue("customerType", user.customerType().name())
                .addValue("fullName", user.fullName())
                .addValue("businessName", user.businessName())
                .addValue("email", user.email())
                .addValue("phoneNumber", user.phoneNumber())
                .addValue("passwordHash", user.passwordHash())
                .addValue("status", user.status().name())
                .addValue("emailVerified", user.emailVerified())
                .addValue("phoneVerified", user.phoneVerified())
                .addValue("failedLoginCount", user.failedLoginCount())
                .addValue("passwordChangedAt", JdbcTimes.offset(user.passwordChangedAt()))
                .addValue("passwordExpiresAt", JdbcTimes.offset(user.passwordExpiresAt()))
                .addValue("mfaEnabled", user.mfaEnabled())
                .addValue("mfaSecretEncrypted", user.mfaSecretEncrypted())
                .addValue("createdBy", user.createdBy())
                .addValue("updatedBy", user.updatedBy());
    }

    public record UserSearch(
            PrincipalType principalType,
            UUID branchId,
            UUID actorId,
            List<UUID> branchIds,
            boolean unrestricted,
            int limit,
            int offset
    ) {
    }
}
