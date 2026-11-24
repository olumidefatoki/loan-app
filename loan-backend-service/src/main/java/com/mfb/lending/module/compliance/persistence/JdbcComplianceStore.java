package com.mfb.lending.module.compliance.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.compliance.domain.ComplianceModels.CaseActivity;
import com.mfb.lending.module.compliance.domain.ComplianceModels.ComplianceAlert;
import com.mfb.lending.module.compliance.domain.ComplianceModels.ComplianceCase;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcComplianceStore implements ComplianceStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcComplianceStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void insertAlert(ComplianceAlert alert) {
        jdbc.update(
                """
                INSERT INTO compliance_alerts (
                    id, customer_id, screening_result_id, alert_type, severity, status, summary, risk_classification, opened_at)
                VALUES (:id, :customerId, :screeningResultId, :alertType, :severity, :status, :summary, :riskClassification, :openedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", alert.id())
                        .addValue("customerId", alert.customerId())
                        .addValue("screeningResultId", alert.screeningResultId())
                        .addValue("alertType", alert.alertType())
                        .addValue("severity", alert.severity())
                        .addValue("status", alert.status())
                        .addValue("summary", alert.summary())
                        .addValue("riskClassification", alert.riskClassification())
                        .addValue("openedAt", offset(alert.openedAt())));
    }

    @Override
    public ComplianceCase insertCase(ComplianceCase complianceCase) {
        jdbc.update(
                """
                INSERT INTO compliance_cases (
                    id, reference, customer_id, alert_id, case_type, status, risk_classification, assignee_id, summary, opened_by, opened_at)
                VALUES (
                    :id, :reference, :customerId, :alertId, :caseType, :status, :riskClassification, :assigneeId, :summary, :openedBy, :openedAt)
                """,
                caseParams(complianceCase));
        return complianceCase;
    }

    @Override
    public Optional<ComplianceAlert> findAlert(UUID id) {
        return jdbc.query("SELECT * FROM compliance_alerts WHERE id = :id", new MapSqlParameterSource("id", id), (rs, row) -> alert(rs))
                .stream().findFirst();
    }

    @Override
    public Optional<ComplianceCase> findCase(UUID id) {
        return jdbc.query("SELECT * FROM compliance_cases WHERE id = :id", new MapSqlParameterSource("id", id), (rs, row) -> complianceCase(rs))
                .stream().findFirst();
    }

    @Override
    public long countAlerts(String status, String alertType) {
        return count("compliance_alerts", status, alertType, "alert_type");
    }

    @Override
    public List<ComplianceAlert> listAlerts(String status, String alertType, int limit, int offset) {
        return jdbc.query(
                "SELECT * FROM compliance_alerts WHERE " + filter("alert_type") + " ORDER BY opened_at DESC LIMIT :limit OFFSET :offset",
                params(status, alertType, limit, offset),
                (rs, row) -> alert(rs));
    }

    @Override
    public long countCases(String status, String caseType) {
        return count("compliance_cases", status, caseType, "case_type");
    }

    @Override
    public List<ComplianceCase> listCases(String status, String caseType, int limit, int offset) {
        return jdbc.query(
                "SELECT * FROM compliance_cases WHERE " + filter("case_type") + " ORDER BY opened_at DESC LIMIT :limit OFFSET :offset",
                params(status, caseType, limit, offset),
                (rs, row) -> complianceCase(rs));
    }

    @Override
    public List<CaseActivity> activities(UUID caseId) {
        return jdbc.query(
                "SELECT * FROM compliance_case_activities WHERE case_id = :caseId ORDER BY created_at",
                new MapSqlParameterSource("caseId", caseId),
                (rs, row) -> activity(rs));
    }

    @Override
    public void insertActivity(CaseActivity activity) {
        jdbc.update(
                """
                INSERT INTO compliance_case_activities (id, case_id, activity_type, note, actor_id, actor_name, created_at)
                VALUES (:id, :caseId, :activityType, :note, :actorId, :actorName, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", activity.id())
                        .addValue("caseId", activity.caseId())
                        .addValue("activityType", activity.activityType())
                        .addValue("note", activity.note())
                        .addValue("actorId", activity.actorId())
                        .addValue("actorName", activity.actorName())
                        .addValue("createdAt", offset(activity.createdAt())));
    }

    @Override
    public void assign(UUID id, UUID assigneeId, String status) {
        jdbc.update(
                "UPDATE compliance_cases SET assignee_id = :assigneeId, status = :status WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("assigneeId", assigneeId).addValue("status", status));
    }

    @Override
    public void markInReview(UUID id) {
        jdbc.update(
                "UPDATE compliance_cases SET status = 'IN_REVIEW' WHERE id = :id AND status IN ('OPEN', 'ASSIGNED')",
                new MapSqlParameterSource("id", id));
    }

    @Override
    public void escalate(UUID id, Instant escalatedAt) {
        jdbc.update(
                "UPDATE compliance_cases SET status = 'ESCALATED', escalated_at = :escalatedAt WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("escalatedAt", offset(escalatedAt)));
        jdbc.update(
                """
                UPDATE compliance_alerts SET status = 'ESCALATED'
                WHERE id = (SELECT alert_id FROM compliance_cases WHERE id = :id) AND status NOT IN ('CLEARED', 'REJECTED')
                """,
                new MapSqlParameterSource("id", id));
    }

    @Override
    public void resolve(ComplianceCase decided) {
        jdbc.update(
                """
                UPDATE compliance_cases
                SET status = :status, decision = :decision, decision_reason = :reason, decided_by = :decidedBy,
                    decided_at = :decidedAt, evidence_retained_until = :retained, risk_classification = :risk
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", decided.id())
                        .addValue("status", decided.status())
                        .addValue("decision", decided.decision())
                        .addValue("reason", decided.decisionReason())
                        .addValue("decidedBy", decided.decidedBy())
                        .addValue("decidedAt", offset(decided.decidedAt()))
                        .addValue("retained", offset(decided.evidenceRetainedUntil()))
                        .addValue("risk", decided.riskClassification()));
    }

    @Override
    public void updateAlertStatus(UUID alertId, String status) {
        if (alertId == null) {
            return;
        }
        jdbc.update(
                "UPDATE compliance_alerts SET status = :status WHERE id = :id",
                new MapSqlParameterSource().addValue("id", alertId).addValue("status", status));
    }

    @Override
    public boolean officerCanWork(UUID userId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM system_users u
                JOIN user_roles ur ON ur.user_id = u.id
                JOIN role_permissions rp ON rp.role_id = ur.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE u.id = :id AND u.principal_type = 'STAFF' AND u.status = 'ACTIVE'
                  AND p.code IN ('COMPLIANCE_MANAGE', 'COMPLIANCE_DECIDE')
                """,
                new MapSqlParameterSource("id", userId),
                Integer.class);
        return count != null && count > 0;
    }

    @Override
    public void updateRisk(UUID customerId, String classification, int score, String actor) {
        jdbc.update(
                "UPDATE customers SET risk_classification = :classification, updated_by = :actor WHERE id = :id",
                new MapSqlParameterSource().addValue("id", customerId).addValue("classification", classification).addValue("actor", actor));
        jdbc.update(
                """
                INSERT INTO customer_risk_profiles (customer_id, classification, score, factors, assessed_by)
                VALUES (:customerId, :classification, :score, '[]'::jsonb, :actor)
                ON CONFLICT (customer_id) DO UPDATE SET
                    classification = EXCLUDED.classification,
                    score = EXCLUDED.score,
                    assessed_at = NOW(),
                    assessed_by = EXCLUDED.assessed_by
                """,
                new MapSqlParameterSource()
                        .addValue("customerId", customerId)
                        .addValue("classification", classification)
                        .addValue("score", score)
                        .addValue("actor", actor));
    }

    @Override
    public long openCases() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM compliance_cases WHERE status IN ('OPEN', 'ASSIGNED', 'IN_REVIEW', 'ESCALATED')",
                new MapSqlParameterSource(),
                Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public String nextReference() {
        Long value = jdbc.queryForObject("SELECT nextval('compliance_case_reference_seq')", new MapSqlParameterSource(), Long.class);
        return "CML" + String.format("%08d", value == null ? 0 : value);
    }

    private long count(String table, String status, String type, String typeColumn) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + filter(typeColumn),
                params(status, type, 0, 0),
                Long.class);
        return count == null ? 0 : count;
    }

    private static String filter(String typeColumn) {
        return "(CAST(:status AS TEXT) IS NULL OR status = CAST(:status AS TEXT)) AND (CAST(:type AS TEXT) IS NULL OR "
                + typeColumn + " = CAST(:type AS TEXT))";
    }

    private static MapSqlParameterSource params(String status, String type, int limit, int offset) {
        return new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("type", type)
                .addValue("limit", limit)
                .addValue("offset", offset);
    }

    private static MapSqlParameterSource caseParams(ComplianceCase complianceCase) {
        return new MapSqlParameterSource()
                .addValue("id", complianceCase.id())
                .addValue("reference", complianceCase.reference())
                .addValue("customerId", complianceCase.customerId())
                .addValue("alertId", complianceCase.alertId())
                .addValue("caseType", complianceCase.caseType())
                .addValue("status", complianceCase.status())
                .addValue("riskClassification", complianceCase.riskClassification())
                .addValue("assigneeId", complianceCase.assigneeId())
                .addValue("summary", complianceCase.summary())
                .addValue("openedBy", complianceCase.openedBy())
                .addValue("openedAt", offset(complianceCase.openedAt()));
    }

    private static ComplianceAlert alert(ResultSet rs) throws SQLException {
        return new ComplianceAlert(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("screening_result_id", UUID.class),
                rs.getString("alert_type"),
                rs.getString("severity"),
                rs.getString("status"),
                rs.getString("summary"),
                rs.getString("risk_classification"),
                instant(rs, "opened_at"));
    }

    private static ComplianceCase complianceCase(ResultSet rs) throws SQLException {
        return new ComplianceCase(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("alert_id", UUID.class),
                rs.getString("case_type"),
                rs.getString("status"),
                rs.getString("risk_classification"),
                rs.getObject("assignee_id", UUID.class),
                rs.getString("summary"),
                rs.getString("decision"),
                rs.getString("decision_reason"),
                rs.getObject("opened_by", UUID.class),
                rs.getObject("decided_by", UUID.class),
                instant(rs, "decided_at"),
                instant(rs, "escalated_at"),
                instant(rs, "evidence_retained_until"),
                instant(rs, "opened_at"));
    }

    private static CaseActivity activity(ResultSet rs) throws SQLException {
        return new CaseActivity(
                rs.getObject("id", UUID.class),
                rs.getObject("case_id", UUID.class),
                rs.getString("activity_type"),
                rs.getString("note"),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_name"),
                instant(rs, "created_at"));
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
