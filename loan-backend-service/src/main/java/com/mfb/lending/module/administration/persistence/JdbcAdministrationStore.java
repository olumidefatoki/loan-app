package com.mfb.lending.module.administration.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.administration.domain.AdministrationModels.Branch;
import com.mfb.lending.module.administration.domain.AdministrationModels.BusinessCalendar;
import com.mfb.lending.module.administration.domain.AdministrationModels.ChangeRequest;
import com.mfb.lending.module.administration.domain.AdministrationModels.FeatureFlag;
import com.mfb.lending.module.administration.domain.AdministrationModels.PublicHoliday;
import com.mfb.lending.module.administration.domain.AdministrationModels.SystemConfiguration;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdministrationStore implements AdministrationStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcAdministrationStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean databaseReady() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", new MapSqlParameterSource(), Integer.class);
            return one != null && one == 1;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    @Override
    public List<Branch> listBranches(int limit, int offset) {
        return jdbc.query(
                "SELECT * FROM branches ORDER BY code LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset),
                (rs, row) -> branch(rs));
    }

    @Override
    public long countBranches() {
        return count("SELECT COUNT(*) FROM branches");
    }

    @Override
    public Optional<Branch> findBranch(UUID id) {
        return one("SELECT * FROM branches WHERE id = :id", new MapSqlParameterSource("id", id), (rs, row) -> branch(rs));
    }

    @Override
    public Optional<Branch> findBranchByCode(String code) {
        return one("SELECT * FROM branches WHERE code = :code", new MapSqlParameterSource("code", code), (rs, row) -> branch(rs));
    }

    @Override
    public Branch insertBranch(Branch branch, String actor) {
        jdbc.update(
                """
                INSERT INTO branches (id, code, name, address_line, city, state, country_code, phone_number, email, status, created_by, updated_by)
                VALUES (:id, :code, :name, :addressLine, :city, :state, :countryCode, :phoneNumber, :email, :status, :actor, :actor)
                """,
                branchParams(branch).addValue("actor", actor));
        return branch;
    }

    @Override
    public void updateBranch(Branch branch, String actor) {
        jdbc.update(
                """
                UPDATE branches
                SET code = :code, name = :name, address_line = :addressLine, city = :city, state = :state,
                    country_code = :countryCode, phone_number = :phoneNumber, email = :email, status = :status, updated_by = :actor
                WHERE id = :id
                """,
                branchParams(branch).addValue("actor", actor));
    }

    @Override
    public void deleteBranch(UUID id) {
        jdbc.update("DELETE FROM branches WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    @Override
    public List<SystemConfiguration> listConfigurations(int limit, int offset) {
        return jdbc.query(
                "SELECT * FROM system_configurations ORDER BY config_key LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource().addValue("limit", limit).addValue("offset", offset),
                (rs, row) -> configuration(rs));
    }

    @Override
    public long countConfigurations() {
        return count("SELECT COUNT(*) FROM system_configurations");
    }

    @Override
    public Optional<SystemConfiguration> findConfiguration(UUID id) {
        return one("SELECT * FROM system_configurations WHERE id = :id", new MapSqlParameterSource("id", id), (rs, row) -> configuration(rs));
    }

    @Override
    public Optional<SystemConfiguration> findConfigurationByKey(String key) {
        return one("SELECT * FROM system_configurations WHERE config_key = :key", new MapSqlParameterSource("key", key), (rs, row) -> configuration(rs));
    }

    @Override
    public SystemConfiguration upsertConfiguration(String key, String value, String description, String valueType, boolean sensitive, String actor) {
        jdbc.update(
                """
                INSERT INTO system_configurations (id, config_key, config_value, description, value_type, sensitive, updated_by)
                VALUES (:id, :key, :value, :description, :valueType, :sensitive, :actor)
                ON CONFLICT (config_key) DO UPDATE SET
                    config_value = EXCLUDED.config_value,
                    description = EXCLUDED.description,
                    value_type = EXCLUDED.value_type,
                    sensitive = EXCLUDED.sensitive,
                    updated_by = EXCLUDED.updated_by
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("key", key)
                        .addValue("value", value)
                        .addValue("description", description)
                        .addValue("valueType", valueType)
                        .addValue("sensitive", sensitive)
                        .addValue("actor", actor));
        return findConfigurationByKey(key).orElseThrow();
    }

    @Override
    public void deleteConfiguration(UUID id) {
        jdbc.update("DELETE FROM system_configurations WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    @Override
    public ChangeRequest insertChange(ChangeRequest request) {
        jdbc.update(
                """
                INSERT INTO configuration_change_requests (
                    id, target_type, target_key, current_value, proposed_value, value_type, description, sensitive,
                    operation, status, requested_by, requested_by_name, requested_at)
                VALUES (
                    :id, :targetType, :targetKey, :currentValue, :proposedValue, :valueType, :description, :sensitive,
                    :operation, :status, :requestedBy, :requestedByName, :requestedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", request.id())
                        .addValue("targetType", request.targetType())
                        .addValue("targetKey", request.targetKey())
                        .addValue("currentValue", request.currentValue())
                        .addValue("proposedValue", request.proposedValue())
                        .addValue("valueType", request.valueType())
                        .addValue("description", request.description())
                        .addValue("sensitive", request.sensitive())
                        .addValue("operation", request.operation())
                        .addValue("status", request.status())
                        .addValue("requestedBy", request.requestedBy())
                        .addValue("requestedByName", request.requestedByName())
                        .addValue("requestedAt", offset(request.requestedAt())));
        return request;
    }

    @Override
    public Optional<ChangeRequest> findChange(UUID id) {
        return one("SELECT * FROM configuration_change_requests WHERE id = :id", new MapSqlParameterSource("id", id), (rs, row) -> change(rs));
    }

    @Override
    public List<ChangeRequest> listChanges(String status, int limit, int offset) {
        return jdbc.query(
                """
                SELECT * FROM configuration_change_requests
                WHERE (CAST(:status AS TEXT) IS NULL OR status = CAST(:status AS TEXT))
                ORDER BY requested_at DESC LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource().addValue("status", status).addValue("limit", limit).addValue("offset", offset),
                (rs, row) -> change(rs));
    }

    @Override
    public long countChanges(String status) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM configuration_change_requests WHERE (CAST(:status AS TEXT) IS NULL OR status = CAST(:status AS TEXT))",
                new MapSqlParameterSource("status", status),
                Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public void decideChange(UUID id, String status, UUID decidedBy, String decidedByName, String reason, Instant decidedAt) {
        jdbc.update(
                """
                UPDATE configuration_change_requests
                SET status = :status, decided_by = :decidedBy, decided_by_name = :decidedByName,
                    decision_reason = :reason, decided_at = :decidedAt
                WHERE id = :id AND status = 'PENDING'
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("status", status)
                        .addValue("decidedBy", decidedBy)
                        .addValue("decidedByName", decidedByName)
                        .addValue("reason", reason)
                        .addValue("decidedAt", offset(decidedAt)));
    }

    @Override
    public long pendingApprovals() {
        return count("SELECT COUNT(*) FROM configuration_change_requests WHERE status = 'PENDING'");
    }

    @Override
    public List<BusinessCalendar> listCalendars() {
        return jdbc.query("SELECT * FROM business_calendars ORDER BY code", (rs, row) -> calendar(rs));
    }

    @Override
    public Optional<BusinessCalendar> findCalendar(UUID id) {
        return one("SELECT * FROM business_calendars WHERE id = :id", new MapSqlParameterSource("id", id), (rs, row) -> calendar(rs));
    }

    @Override
    public BusinessCalendar insertCalendar(BusinessCalendar calendar, String actor) {
        jdbc.update(
                """
                INSERT INTO business_calendars (id, code, name, timezone, weekend_days, active, created_by, updated_by)
                VALUES (:id, :code, :name, :timezone, :weekendDays, :active, :actor, :actor)
                """,
                calendarParams(calendar).addValue("actor", actor));
        return calendar;
    }

    @Override
    public void updateCalendar(BusinessCalendar calendar, String actor) {
        jdbc.update(
                """
                UPDATE business_calendars
                SET code = :code, name = :name, timezone = :timezone, weekend_days = :weekendDays, active = :active, updated_by = :actor
                WHERE id = :id
                """,
                calendarParams(calendar).addValue("actor", actor));
    }

    @Override
    public void deleteCalendar(UUID id) {
        jdbc.update("DELETE FROM public_holidays WHERE calendar_id = :id", new MapSqlParameterSource("id", id));
        jdbc.update("DELETE FROM business_calendars WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    @Override
    public List<PublicHoliday> holidays(UUID calendarId) {
        return jdbc.query(
                "SELECT * FROM public_holidays WHERE calendar_id = :id ORDER BY holiday_date",
                new MapSqlParameterSource("id", calendarId),
                (rs, row) -> holiday(rs));
    }

    @Override
    public PublicHoliday insertHoliday(PublicHoliday holiday, String actor) {
        jdbc.update(
                """
                INSERT INTO public_holidays (id, calendar_id, holiday_date, name, created_by)
                VALUES (:id, :calendarId, :holidayDate, :name, :actor)
                """,
                new MapSqlParameterSource()
                        .addValue("id", holiday.id())
                        .addValue("calendarId", holiday.calendarId())
                        .addValue("holidayDate", holiday.holidayDate())
                        .addValue("name", holiday.name())
                        .addValue("actor", actor));
        return holiday;
    }

    @Override
    public void deleteHoliday(UUID calendarId, UUID holidayId) {
        jdbc.update(
                "DELETE FROM public_holidays WHERE id = :id AND calendar_id = :calendarId",
                new MapSqlParameterSource().addValue("id", holidayId).addValue("calendarId", calendarId));
    }

    @Override
    public boolean holiday(UUID calendarId, LocalDate date) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM public_holidays WHERE calendar_id = :id AND holiday_date = :holidayDate",
                new MapSqlParameterSource().addValue("id", calendarId).addValue("holidayDate", date),
                Integer.class);
        return count != null && count > 0;
    }

    @Override
    public List<FeatureFlag> listFlags() {
        return jdbc.query("SELECT * FROM feature_flags ORDER BY flag_key", (rs, row) -> flag(rs));
    }

    @Override
    public Optional<FeatureFlag> findFlag(UUID id) {
        return one("SELECT * FROM feature_flags WHERE id = :id", new MapSqlParameterSource("id", id), (rs, row) -> flag(rs));
    }

    @Override
    public Optional<FeatureFlag> findFlagByKey(String key) {
        return one("SELECT * FROM feature_flags WHERE flag_key = :key", new MapSqlParameterSource("key", key), (rs, row) -> flag(rs));
    }

    @Override
    public FeatureFlag upsertFlag(String key, boolean enabled, String description, boolean sensitive, String actor) {
        jdbc.update(
                """
                INSERT INTO feature_flags (id, flag_key, enabled, description, sensitive, updated_by)
                VALUES (:id, :key, :enabled, :description, :sensitive, :actor)
                ON CONFLICT (flag_key) DO UPDATE SET
                    enabled = EXCLUDED.enabled,
                    description = EXCLUDED.description,
                    sensitive = EXCLUDED.sensitive,
                    updated_by = EXCLUDED.updated_by
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("key", key)
                        .addValue("enabled", enabled)
                        .addValue("description", description)
                        .addValue("sensitive", sensitive)
                        .addValue("actor", actor));
        return findFlagByKey(key).orElseThrow();
    }

    @Override
    public void deleteFlag(UUID id) {
        jdbc.update("DELETE FROM feature_flags WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    private long count(String sql) {
        Long count = jdbc.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return count == null ? 0 : count;
    }

    private <T> Optional<T> one(String sql, MapSqlParameterSource params, org.springframework.jdbc.core.RowMapper<T> mapper) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(sql, params, mapper));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    private static MapSqlParameterSource branchParams(Branch branch) {
        return new MapSqlParameterSource()
                .addValue("id", branch.id())
                .addValue("code", branch.code())
                .addValue("name", branch.name())
                .addValue("addressLine", branch.addressLine())
                .addValue("city", branch.city())
                .addValue("state", branch.state())
                .addValue("countryCode", branch.countryCode())
                .addValue("phoneNumber", branch.phoneNumber())
                .addValue("email", branch.email())
                .addValue("status", branch.status());
    }

    private static MapSqlParameterSource calendarParams(BusinessCalendar calendar) {
        return new MapSqlParameterSource()
                .addValue("id", calendar.id())
                .addValue("code", calendar.code())
                .addValue("name", calendar.name())
                .addValue("timezone", calendar.timezone())
                .addValue("weekendDays", calendar.weekendDays())
                .addValue("active", calendar.active());
    }

    private static Branch branch(ResultSet rs) throws SQLException {
        return new Branch(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("address_line"),
                rs.getString("city"), rs.getString("state"), rs.getString("country_code"), rs.getString("phone_number"),
                rs.getString("email"), rs.getString("status"));
    }

    private static SystemConfiguration configuration(ResultSet rs) throws SQLException {
        return new SystemConfiguration(
                rs.getObject("id", UUID.class), rs.getString("config_key"), rs.getString("config_value"), rs.getString("description"),
                rs.getString("value_type"), rs.getBoolean("sensitive"), false);
    }

    private static ChangeRequest change(ResultSet rs) throws SQLException {
        return new ChangeRequest(
                rs.getObject("id", UUID.class), rs.getString("target_type"), rs.getString("target_key"), rs.getString("current_value"),
                rs.getString("proposed_value"), rs.getString("value_type"), rs.getString("description"), rs.getBoolean("sensitive"),
                rs.getString("operation"), rs.getString("status"), rs.getObject("requested_by", UUID.class), rs.getString("requested_by_name"),
                rs.getObject("decided_by", UUID.class), rs.getString("decided_by_name"), rs.getString("decision_reason"),
                instant(rs, "requested_at"), instant(rs, "decided_at"));
    }

    private static BusinessCalendar calendar(ResultSet rs) throws SQLException {
        return new BusinessCalendar(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"), rs.getString("timezone"),
                rs.getString("weekend_days"), rs.getBoolean("active"));
    }

    private static PublicHoliday holiday(ResultSet rs) throws SQLException {
        return new PublicHoliday(rs.getObject("id", UUID.class), rs.getObject("calendar_id", UUID.class), rs.getObject("holiday_date", LocalDate.class), rs.getString("name"));
    }

    private static FeatureFlag flag(ResultSet rs) throws SQLException {
        return new FeatureFlag(rs.getObject("id", UUID.class), rs.getString("flag_key"), rs.getBoolean("enabled"), rs.getString("description"), rs.getBoolean("sensitive"));
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
