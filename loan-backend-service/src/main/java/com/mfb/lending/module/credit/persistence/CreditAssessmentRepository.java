package com.mfb.lending.module.credit.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.credit.domain.AnalystRecommendation;
import com.mfb.lending.module.credit.domain.AssessmentStatus;
import com.mfb.lending.module.credit.domain.BureauCheckStatus;
import com.mfb.lending.module.credit.domain.CreditAssessmentRecord;
import com.mfb.lending.module.credit.domain.CreditBureauCheck;
import com.mfb.lending.module.credit.domain.CreditComment;
import com.mfb.lending.module.credit.domain.CreditPolicyException;
import com.mfb.lending.module.credit.domain.CreditQueueItem;
import com.mfb.lending.module.credit.domain.CreditResultRecord;
import com.mfb.lending.module.credit.domain.DecisionOutcome;
import com.mfb.lending.module.credit.domain.DecisionReason;
import com.mfb.lending.module.credit.domain.FindingSeverity;
import com.mfb.lending.module.credit.domain.ScoreComponentResult;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CreditAssessmentRepository {

    private static final String ASSESSMENT_COLUMNS = """
            id, reference, application_id, customer_id, product_id, product_version_id, branch_id, segment,
            status, decision, score, risk_category, scoring_model_id, scoring_model_version_id, policy_version,
            inputs::text AS inputs_json, calculations::text AS calculations_json, analyst_recommendation,
            analyst_justification, recommended_by, recommended_at, assigned_to, revision, created_at, updated_at,
            created_by, updated_by
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CreditAssessmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextReference(int year) {
        Long value = jdbc.queryForObject(
                "SELECT nextval('credit_assessment_reference_seq')",
                new MapSqlParameterSource(),
                Long.class);
        if (value == null || value < 1 || value > 99_999_999L) {
            throw new IllegalStateException("Credit assessment reference sequence is exhausted");
        }
        return "CA" + year + String.format("%08d", value);
    }

    public void insert(CreditAssessmentRecord assessment) {
        jdbc.update(
                """
                INSERT INTO credit_assessments (
                    id, reference, application_id, customer_id, product_id, product_version_id, branch_id, segment,
                    status, inputs, assigned_to, revision, created_by, updated_by)
                VALUES (
                    :id, :reference, :applicationId, :customerId, :productId, :productVersionId, :branchId, :segment,
                    :status, CAST(:inputs AS jsonb), :assignedTo, :revision, :createdBy, :updatedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", assessment.id())
                        .addValue("reference", assessment.reference())
                        .addValue("applicationId", assessment.applicationId())
                        .addValue("customerId", assessment.customerId())
                        .addValue("productId", assessment.productId())
                        .addValue("productVersionId", assessment.productVersionId())
                        .addValue("branchId", assessment.branchId())
                        .addValue("segment", assessment.segment())
                        .addValue("status", assessment.status().name())
                        .addValue("inputs", assessment.inputsJson())
                        .addValue("assignedTo", assessment.assignedTo())
                        .addValue("revision", assessment.revision())
                        .addValue("createdBy", assessment.createdBy())
                        .addValue("updatedBy", assessment.updatedBy()));
    }

    public Optional<CreditAssessmentRecord> lock(UUID id) {
        return one("SELECT " + ASSESSMENT_COLUMNS + " FROM credit_assessments WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), ASSESSMENT_MAPPER);
    }

    public Optional<CreditAssessmentRecord> find(UUID id) {
        return one("SELECT " + ASSESSMENT_COLUMNS + " FROM credit_assessments WHERE id = :id",
                new MapSqlParameterSource("id", id), ASSESSMENT_MAPPER);
    }

    public Optional<CreditAssessmentRecord> findLatestForApplication(UUID applicationId) {
        return one(
                "SELECT " + ASSESSMENT_COLUMNS + """
                 FROM credit_assessments
                 WHERE application_id = :applicationId AND status <> 'CLOSED'
                 ORDER BY created_at DESC
                 LIMIT 1
                """,
                new MapSqlParameterSource("applicationId", applicationId),
                ASSESSMENT_MAPPER);
    }

    public void saveEvaluation(
            UUID id,
            AssessmentStatus status,
            DecisionOutcome decision,
            BigDecimal score,
            String riskCategory,
            UUID modelId,
            UUID modelVersionId,
            String policyVersion,
            String calculationsJson,
            String updatedBy) {
        jdbc.update(
                """
                UPDATE credit_assessments
                SET status = :status,
                    decision = :decision,
                    score = :score,
                    risk_category = :riskCategory,
                    scoring_model_id = :modelId,
                    scoring_model_version_id = :modelVersionId,
                    policy_version = :policyVersion,
                    calculations = CAST(:calculations AS jsonb),
                    revision = revision + 1,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("status", status.name())
                        .addValue("decision", decision.name())
                        .addValue("score", score)
                        .addValue("riskCategory", riskCategory)
                        .addValue("modelId", modelId)
                        .addValue("modelVersionId", modelVersionId)
                        .addValue("policyVersion", policyVersion)
                        .addValue("calculations", calculationsJson)
                        .addValue("updatedBy", updatedBy));
    }

    public void saveRecommendation(
            UUID id,
            AnalystRecommendation recommendation,
            String justification,
            UUID recommendedBy,
            Instant recommendedAt,
            AssessmentStatus status,
            DecisionOutcome decision,
            String updatedBy) {
        jdbc.update(
                """
                UPDATE credit_assessments
                SET analyst_recommendation = :recommendation,
                    analyst_justification = :justification,
                    recommended_by = :recommendedBy,
                    recommended_at = :recommendedAt,
                    status = :status,
                    decision = :decision,
                    revision = revision + 1,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("recommendation", recommendation.name())
                        .addValue("justification", justification)
                        .addValue("recommendedBy", recommendedBy)
                        .addValue("recommendedAt", JdbcTimes.offset(recommendedAt))
                        .addValue("status", status.name())
                        .addValue("decision", decision == null ? null : decision.name())
                        .addValue("updatedBy", updatedBy));
    }

    public void insertResult(CreditResultRecord result, String calculationsJson) {
        jdbc.update(
                """
                INSERT INTO credit_assessment_results (
                    id, assessment_id, model_id, model_version_id, policy_version, score, risk_category,
                    decision, explanation, calculations)
                VALUES (
                    :id, :assessmentId, :modelId, :modelVersionId, :policyVersion, :score, :riskCategory,
                    :decision, :explanation, CAST(:calculations AS jsonb))
                """,
                new MapSqlParameterSource()
                        .addValue("id", result.id())
                        .addValue("assessmentId", result.assessmentId())
                        .addValue("modelId", result.modelId())
                        .addValue("modelVersionId", result.modelVersionId())
                        .addValue("policyVersion", result.policyVersion())
                        .addValue("score", result.score())
                        .addValue("riskCategory", result.riskCategory())
                        .addValue("decision", result.decision().name())
                        .addValue("explanation", result.explanation())
                        .addValue("calculations", calculationsJson));
    }

    public void insertComponent(UUID id, UUID assessmentId, UUID resultId, ScoreComponentResult component) {
        jdbc.update(
                """
                INSERT INTO credit_score_components (
                    id, assessment_id, result_id, component_code, metric, raw_value, raw_score, weight,
                    weighted_score, explanation)
                VALUES (
                    :id, :assessmentId, :resultId, :code, :metric, :rawValue, :rawScore, :weight,
                    :weightedScore, :explanation)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("assessmentId", assessmentId)
                        .addValue("resultId", resultId)
                        .addValue("code", component.code())
                        .addValue("metric", component.metric())
                        .addValue("rawValue", component.rawValue())
                        .addValue("rawScore", component.rawScore())
                        .addValue("weight", component.weight())
                        .addValue("weightedScore", component.weightedScore())
                        .addValue("explanation", component.explanation()));
    }

    public void insertReason(UUID id, UUID assessmentId, UUID resultId, DecisionReason reason) {
        jdbc.update(
                """
                INSERT INTO credit_decision_reasons (
                    id, assessment_id, result_id, code, severity, message, blocks_approval)
                VALUES (:id, :assessmentId, :resultId, :code, :severity, :message, :blocksApproval)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("assessmentId", assessmentId)
                        .addValue("resultId", resultId)
                        .addValue("code", reason.code())
                        .addValue("severity", reason.severity().name())
                        .addValue("message", reason.message())
                        .addValue("blocksApproval", reason.blocksApproval()));
    }

    public void insertBureau(CreditBureauCheck check) {
        jdbc.update(
                """
                INSERT INTO credit_bureau_checks (
                    id, assessment_id, customer_id, provider_code, status, identification_matched, bureau_score,
                    existing_exposure, mapped_response, failure_reason, requested_by, completed_at)
                VALUES (
                    :id, :assessmentId, :customerId, :providerCode, :status, :matched, :score,
                    :exposure, CAST(:mapped AS jsonb), :failureReason, :requestedBy, :completedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", check.id())
                        .addValue("assessmentId", check.assessmentId())
                        .addValue("customerId", check.customerId())
                        .addValue("providerCode", check.providerCode())
                        .addValue("status", check.status().name())
                        .addValue("matched", check.identificationMatched())
                        .addValue("score", check.bureauScore())
                        .addValue("exposure", check.existingExposure())
                        .addValue("mapped", check.mappedResponseJson())
                        .addValue("failureReason", check.failureReason())
                        .addValue("requestedBy", check.requestedBy())
                        .addValue("completedAt", JdbcTimes.offset(check.completedAt())));
    }

    public void insertComment(CreditComment comment) {
        jdbc.update(
                """
                INSERT INTO credit_assessment_comments (id, assessment_id, author_id, body)
                VALUES (:id, :assessmentId, :authorId, :body)
                """,
                new MapSqlParameterSource()
                        .addValue("id", comment.id())
                        .addValue("assessmentId", comment.assessmentId())
                        .addValue("authorId", comment.authorId())
                        .addValue("body", comment.body()));
    }

    public void insertException(CreditPolicyException exception) {
        jdbc.update(
                """
                INSERT INTO credit_policy_exceptions (
                    id, assessment_id, rule_code, justification, status, requested_by)
                VALUES (:id, :assessmentId, :ruleCode, :justification, :status, :requestedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", exception.id())
                        .addValue("assessmentId", exception.assessmentId())
                        .addValue("ruleCode", exception.ruleCode())
                        .addValue("justification", exception.justification())
                        .addValue("status", exception.status())
                        .addValue("requestedBy", exception.requestedBy()));
    }

    public Optional<CreditPolicyException> lockException(UUID id) {
        return one(
                "SELECT " + EXCEPTION_COLUMNS + " FROM credit_policy_exceptions WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id),
                EXCEPTION_MAPPER);
    }

    public int approveException(UUID id, UUID decidedBy, Instant decidedAt, String note) {
        return jdbc.update(
                """
                UPDATE credit_policy_exceptions
                SET status = 'APPROVED', decided_by = :decidedBy, decided_at = :decidedAt, decision_note = :note
                WHERE id = :id AND status = 'PENDING'
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("decidedBy", decidedBy)
                        .addValue("decidedAt", JdbcTimes.offset(decidedAt))
                        .addValue("note", note));
    }

    public Optional<CreditResultRecord> latestResult(UUID assessmentId) {
        return one(
                """
                SELECT id, assessment_id, model_id, model_version_id, policy_version, score, risk_category,
                       decision, explanation, evaluated_at
                FROM credit_assessment_results
                WHERE assessment_id = :assessmentId
                ORDER BY evaluated_at DESC
                LIMIT 1
                """,
                new MapSqlParameterSource("assessmentId", assessmentId),
                RESULT_MAPPER);
    }

    public List<CreditResultRecord> listResults(UUID assessmentId) {
        return jdbc.query(
                """
                SELECT id, assessment_id, model_id, model_version_id, policy_version, score, risk_category,
                       decision, explanation, evaluated_at
                FROM credit_assessment_results
                WHERE assessment_id = :assessmentId
                ORDER BY evaluated_at
                """,
                new MapSqlParameterSource("assessmentId", assessmentId),
                RESULT_MAPPER);
    }

    public List<ScoreComponentResult> listComponents(UUID resultId) {
        return jdbc.query(
                """
                SELECT component_code, metric, raw_value, raw_score, weight, weighted_score, explanation
                FROM credit_score_components
                WHERE result_id = :resultId
                ORDER BY component_code
                """,
                new MapSqlParameterSource("resultId", resultId),
                (rs, rowNum) -> new ScoreComponentResult(
                        rs.getString("component_code"),
                        rs.getString("metric"),
                        rs.getBigDecimal("raw_value"),
                        rs.getBigDecimal("raw_score"),
                        rs.getBigDecimal("weight"),
                        rs.getBigDecimal("weighted_score"),
                        rs.getString("explanation")));
    }

    public List<DecisionReason> listReasons(UUID resultId) {
        return jdbc.query(
                """
                SELECT code, severity, message, blocks_approval
                FROM credit_decision_reasons
                WHERE result_id = :resultId
                ORDER BY blocks_approval DESC, code
                """,
                new MapSqlParameterSource("resultId", resultId),
                (rs, rowNum) -> new DecisionReason(
                        rs.getString("code"),
                        FindingSeverity.valueOf(rs.getString("severity")),
                        rs.getString("message"),
                        rs.getBoolean("blocks_approval")));
    }

    public Optional<CreditBureauCheck> latestBureau(UUID assessmentId) {
        return one(
                "SELECT " + BUREAU_COLUMNS + """
                 FROM credit_bureau_checks
                 WHERE assessment_id = :assessmentId
                 ORDER BY requested_at DESC
                 LIMIT 1
                """,
                new MapSqlParameterSource("assessmentId", assessmentId),
                BUREAU_MAPPER);
    }

    public List<CreditBureauCheck> listBureau(UUID assessmentId) {
        return jdbc.query(
                "SELECT " + BUREAU_COLUMNS + """
                 FROM credit_bureau_checks
                 WHERE assessment_id = :assessmentId
                 ORDER BY requested_at
                """,
                new MapSqlParameterSource("assessmentId", assessmentId),
                BUREAU_MAPPER);
    }

    public List<CreditComment> listComments(UUID assessmentId) {
        return jdbc.query(
                """
                SELECT id, assessment_id, author_id, body, created_at
                FROM credit_assessment_comments
                WHERE assessment_id = :assessmentId
                ORDER BY created_at
                """,
                new MapSqlParameterSource("assessmentId", assessmentId),
                (rs, rowNum) -> new CreditComment(
                        rs.getObject("id", UUID.class),
                        rs.getObject("assessment_id", UUID.class),
                        rs.getObject("author_id", UUID.class),
                        rs.getString("body"),
                        JdbcTimes.instant(rs, "created_at")));
    }

    public List<CreditPolicyException> listExceptions(UUID assessmentId) {
        return jdbc.query(
                "SELECT " + EXCEPTION_COLUMNS + """
                 FROM credit_policy_exceptions
                 WHERE assessment_id = :assessmentId
                 ORDER BY requested_at
                """,
                new MapSqlParameterSource("assessmentId", assessmentId),
                EXCEPTION_MAPPER);
    }

    public List<String> approvedExceptionCodes(UUID assessmentId) {
        return jdbc.query(
                """
                SELECT rule_code FROM credit_policy_exceptions
                WHERE assessment_id = :assessmentId AND status = 'APPROVED'
                """,
                new MapSqlParameterSource("assessmentId", assessmentId),
                (rs, rowNum) -> rs.getString("rule_code"));
    }

    public List<CreditQueueItem> queue(
            UUID actorId,
            boolean includeUnassigned,
            String decision,
            boolean restrictBranches,
            List<UUID> branchIds,
            int limit,
            int offset) {
        Filter filter = queueFilter(actorId, includeUnassigned, decision, restrictBranches, branchIds);
        filter.params().addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(
                """
                SELECT a.id, a.reference, a.application_id, a.customer_id, c.display_name, a.segment, a.status,
                       a.decision, a.score, a.risk_category, a.assigned_to, a.updated_at
                FROM credit_assessments a
                JOIN customers c ON c.id = a.customer_id
                """ + filter.where() + """
                ORDER BY a.updated_at DESC
                LIMIT :limit OFFSET :offset
                """,
                filter.params(),
                QUEUE_MAPPER);
    }

    public long queueCount(
            UUID actorId,
            boolean includeUnassigned,
            String decision,
            boolean restrictBranches,
            List<UUID> branchIds) {
        Filter filter = queueFilter(actorId, includeUnassigned, decision, restrictBranches, branchIds);
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_assessments a " + filter.where(),
                filter.params(),
                Long.class);
        return count == null ? 0 : count;
    }

    private Filter queueFilter(
            UUID actorId,
            boolean includeUnassigned,
            String decision,
            boolean restrictBranches,
            List<UUID> branchIds) {
        StringBuilder where = new StringBuilder(" WHERE a.status <> 'CLOSED' ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (decision != null) {
            where.append(" AND a.decision = :decision ");
            params.addValue("decision", decision);
        } else {
            where.append("""
                     AND (a.decision IN ('REFER', 'PENDING_DATA', 'COMPLIANCE_HOLD') OR a.status = 'OPEN')
                    """);
        }
        if (includeUnassigned) {
            where.append(" AND (a.assigned_to = :actorId OR a.assigned_to IS NULL) ");
        } else {
            where.append(" AND a.assigned_to = :actorId ");
        }
        params.addValue("actorId", actorId);
        if (restrictBranches) {
            where.append(" AND a.branch_id IN (:branchIds) ");
            params.addValue("branchIds", branchIds);
        }
        return new Filter(where.toString(), params);
    }

    private static final RowMapper<CreditAssessmentRecord> ASSESSMENT_MAPPER = (rs, rowNum) -> mapAssessment(rs);

    private static CreditAssessmentRecord mapAssessment(ResultSet rs) throws SQLException {
        String decision = rs.getString("decision");
        String recommendation = rs.getString("analyst_recommendation");
        return new CreditAssessmentRecord(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getObject("application_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getObject("product_version_id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("segment"),
                AssessmentStatus.valueOf(rs.getString("status")),
                decision == null ? null : DecisionOutcome.valueOf(decision),
                rs.getBigDecimal("score"),
                rs.getString("risk_category"),
                rs.getObject("scoring_model_id", UUID.class),
                rs.getObject("scoring_model_version_id", UUID.class),
                rs.getString("policy_version"),
                rs.getString("inputs_json"),
                rs.getString("calculations_json"),
                recommendation == null ? null : AnalystRecommendation.valueOf(recommendation),
                rs.getString("analyst_justification"),
                rs.getObject("recommended_by", UUID.class),
                JdbcTimes.instant(rs, "recommended_at"),
                rs.getObject("assigned_to", UUID.class),
                rs.getInt("revision"),
                JdbcTimes.instant(rs, "created_at"),
                JdbcTimes.instant(rs, "updated_at"),
                rs.getString("created_by"),
                rs.getString("updated_by"));
    }

    private static final RowMapper<CreditResultRecord> RESULT_MAPPER = (rs, rowNum) -> new CreditResultRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("assessment_id", UUID.class),
            rs.getObject("model_id", UUID.class),
            rs.getObject("model_version_id", UUID.class),
            rs.getString("policy_version"),
            rs.getBigDecimal("score"),
            rs.getString("risk_category"),
            DecisionOutcome.valueOf(rs.getString("decision")),
            rs.getString("explanation"),
            JdbcTimes.instant(rs, "evaluated_at"));

    private static final String BUREAU_COLUMNS = """
            id, assessment_id, customer_id, provider_code, status, identification_matched, bureau_score,
            existing_exposure, mapped_response::text AS mapped_json, failure_reason, requested_by, requested_at, completed_at
            """;

    private static final RowMapper<CreditBureauCheck> BUREAU_MAPPER = (rs, rowNum) -> {
        boolean matched = rs.getBoolean("identification_matched");
        Boolean identification = rs.wasNull() ? null : matched;
        int score = rs.getInt("bureau_score");
        Integer bureauScore = rs.wasNull() ? null : score;
        return new CreditBureauCheck(
                rs.getObject("id", UUID.class),
                rs.getObject("assessment_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("provider_code"),
                BureauCheckStatus.valueOf(rs.getString("status")),
                identification,
                bureauScore,
                rs.getBigDecimal("existing_exposure"),
                rs.getString("mapped_json"),
                rs.getString("failure_reason"),
                rs.getObject("requested_by", UUID.class),
                JdbcTimes.instant(rs, "requested_at"),
                JdbcTimes.instant(rs, "completed_at"));
    };

    private static final String EXCEPTION_COLUMNS = """
            id, assessment_id, rule_code, justification, status, requested_by, requested_at,
            decided_by, decided_at, decision_note
            """;

    private static final RowMapper<CreditPolicyException> EXCEPTION_MAPPER = (rs, rowNum) -> new CreditPolicyException(
            rs.getObject("id", UUID.class),
            rs.getObject("assessment_id", UUID.class),
            rs.getString("rule_code"),
            rs.getString("justification"),
            rs.getString("status"),
            rs.getObject("requested_by", UUID.class),
            JdbcTimes.instant(rs, "requested_at"),
            rs.getObject("decided_by", UUID.class),
            JdbcTimes.instant(rs, "decided_at"),
            rs.getString("decision_note"));

    private static final RowMapper<CreditQueueItem> QUEUE_MAPPER = (rs, rowNum) -> {
        String decision = rs.getString("decision");
        return new CreditQueueItem(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getObject("application_id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                rs.getString("display_name"),
                rs.getString("segment"),
                AssessmentStatus.valueOf(rs.getString("status")),
                decision == null ? null : DecisionOutcome.valueOf(decision),
                rs.getBigDecimal("score"),
                rs.getString("risk_category"),
                rs.getObject("assigned_to", UUID.class),
                JdbcTimes.instant(rs, "updated_at"));
    };

    private <T> Optional<T> one(String sql, MapSqlParameterSource params, RowMapper<T> mapper) {
        return jdbc.query(sql, params, mapper).stream().findFirst();
    }

    private record Filter(String where, MapSqlParameterSource params) {
    }
}
