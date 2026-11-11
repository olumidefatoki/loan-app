package com.mfb.lending.module.credit.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.module.credit.domain.CreditRule;
import com.mfb.lending.module.credit.domain.DecisionOutcome;
import com.mfb.lending.module.credit.domain.DecisionThresholds;
import com.mfb.lending.module.credit.domain.ModelSegment;
import com.mfb.lending.module.credit.domain.ModelStatus;
import com.mfb.lending.module.credit.domain.RiskBand;
import com.mfb.lending.module.credit.domain.RuleType;
import com.mfb.lending.module.credit.domain.ScoringModelRecord;
import com.mfb.lending.module.credit.domain.ScoringVersionRecord;
import com.mfb.lending.module.credit.domain.ScoringWeights;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CreditPolicyRepository {

    private static final TypeReference<List<RiskBand>> RISKS = new TypeReference<>() {
    };

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CreditPolicyRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insertModel(ScoringModelRecord model) {
        jdbc.update(
                """
                INSERT INTO credit_scoring_models (
                    id, code, name, segment, product_id, status, current_version_id, created_by, updated_by)
                VALUES (
                    :id, :code, :name, :segment, :productId, :status, :currentVersionId, :createdBy, :updatedBy)
                """,
                modelParams(model));
    }

    public void updateModelHead(UUID id, String name, ModelStatus status, UUID currentVersionId, String updatedBy) {
        jdbc.update(
                """
                UPDATE credit_scoring_models
                SET name = :name, status = :status, current_version_id = :currentVersionId, updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("name", name)
                        .addValue("status", status.name())
                        .addValue("currentVersionId", currentVersionId)
                        .addValue("updatedBy", updatedBy));
    }

    public Optional<ScoringModelRecord> findModel(UUID id) {
        return one(
                """
                SELECT id, code, name, segment, product_id, status, current_version_id,
                       created_at, updated_at, created_by, updated_by
                FROM credit_scoring_models WHERE id = :id
                """,
                new MapSqlParameterSource("id", id),
                MODEL_MAPPER);
    }

    public Optional<ScoringModelRecord> findModelByCode(String code) {
        return one(
                """
                SELECT id, code, name, segment, product_id, status, current_version_id,
                       created_at, updated_at, created_by, updated_by
                FROM credit_scoring_models WHERE code = :code
                """,
                new MapSqlParameterSource("code", code),
                MODEL_MAPPER);
    }

    public List<ScoringModelRecord> listModels() {
        return jdbc.query(
                """
                SELECT id, code, name, segment, product_id, status, current_version_id,
                       created_at, updated_at, created_by, updated_by
                FROM credit_scoring_models
                ORDER BY code
                """,
                MODEL_MAPPER);
    }

    public void insertVersion(ScoringVersionRecord version) {
        jdbc.update(
                """
                INSERT INTO credit_scoring_model_versions (
                    id, model_id, version_number, status, weights, risk_categories, decision_thresholds,
                    policy_version, change_reason, activated_at, created_by)
                VALUES (
                    :id, :modelId, :versionNumber, :status, CAST(:weights AS jsonb), CAST(:riskCategories AS jsonb),
                    CAST(:thresholds AS jsonb), :policyVersion, :changeReason, :activatedAt, :createdBy)
                """,
                versionParams(version));
    }

    public int updateDraft(ScoringVersionRecord version) {
        return jdbc.update(
                """
                UPDATE credit_scoring_model_versions
                SET weights = CAST(:weights AS jsonb),
                    risk_categories = CAST(:riskCategories AS jsonb),
                    decision_thresholds = CAST(:thresholds AS jsonb),
                    policy_version = :policyVersion,
                    change_reason = :changeReason
                WHERE id = :id AND status = 'DRAFT'
                """,
                versionParams(version));
    }

    public Optional<ScoringVersionRecord> findVersion(UUID id) {
        return one(versionSelect("WHERE v.id = :id"), new MapSqlParameterSource("id", id), this::mapVersion);
    }

    public Optional<ScoringVersionRecord> findDraft(UUID modelId) {
        return one(
                versionSelect("WHERE v.model_id = :modelId AND v.status = 'DRAFT'"),
                new MapSqlParameterSource("modelId", modelId),
                this::mapVersion);
    }

    public List<ScoringVersionRecord> listVersions(UUID modelId) {
        return jdbc.query(
                versionSelect("WHERE v.model_id = :modelId ORDER BY v.version_number DESC"),
                new MapSqlParameterSource("modelId", modelId),
                this::mapVersion);
    }

    public int nextVersionNumber(UUID modelId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) + 1 FROM credit_scoring_model_versions WHERE model_id = :modelId",
                new MapSqlParameterSource("modelId", modelId),
                Integer.class);
        return value == null ? 1 : value;
    }

    public void retireActive(UUID modelId) {
        jdbc.update(
                "UPDATE credit_scoring_model_versions SET status = 'RETIRED' WHERE model_id = :modelId AND status = 'ACTIVE'",
                new MapSqlParameterSource("modelId", modelId));
    }

    public int activate(UUID versionId, java.time.Instant activatedAt) {
        return jdbc.update(
                """
                UPDATE credit_scoring_model_versions
                SET status = 'ACTIVE', activated_at = :activatedAt
                WHERE id = :id AND status = 'DRAFT'
                """,
                new MapSqlParameterSource()
                        .addValue("id", versionId)
                        .addValue("activatedAt", JdbcTimes.offset(activatedAt)));
    }

    public void clearCurrentVersion(UUID modelId) {
        jdbc.update(
                "UPDATE credit_scoring_models SET current_version_id = NULL WHERE id = :id",
                new MapSqlParameterSource("id", modelId));
    }

    public void deleteDraftVersions(UUID modelId) {
        jdbc.update(
                "DELETE FROM credit_scoring_model_versions WHERE model_id = :modelId AND status = 'DRAFT'",
                new MapSqlParameterSource("modelId", modelId));
    }

    public int deleteDraftModel(UUID modelId) {
        return jdbc.update(
                "DELETE FROM credit_scoring_models WHERE id = :id AND status = 'DRAFT'",
                new MapSqlParameterSource("id", modelId));
    }

    public long assessmentCount(UUID modelId) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM credit_assessments WHERE scoring_model_id = :id
                """,
                new MapSqlParameterSource("id", modelId),
                Long.class);
        return count == null ? 0 : count;
    }

    public Optional<ScoringVersionRecord> findActiveModel(UUID productId, String segment) {
        return one(
                versionSelect(
                        """
                        WHERE m.status = 'ACTIVE'
                          AND v.status = 'ACTIVE'
                          AND (
                            (m.segment = 'PRODUCT' AND m.product_id = :productId)
                            OR (m.segment = :segment AND m.product_id IS NULL)
                          )
                        ORDER BY CASE WHEN m.segment = 'PRODUCT' THEN 0 ELSE 1 END
                        LIMIT 1
                        """),
                new MapSqlParameterSource().addValue("productId", productId).addValue("segment", segment),
                this::mapVersion);
    }

    public void insertRule(CreditRule rule, String actor) {
        jdbc.update(
                """
                INSERT INTO credit_assessment_rules (
                    id, code, name, description, segment, product_id, rule_type, mandatory, exception_allowed,
                    threshold, failure_outcome, policy_version, active, created_by, updated_by)
                VALUES (
                    :id, :code, :name, :description, :segment, :productId, :ruleType, :mandatory, :exceptionAllowed,
                    :threshold, :failureOutcome, :policyVersion, :active, :actor, :actor)
                """,
                ruleParams(rule, actor));
    }

    public int updateRule(CreditRule rule, String actor) {
        return jdbc.update(
                """
                UPDATE credit_assessment_rules
                SET name = :name,
                    description = :description,
                    segment = :segment,
                    product_id = :productId,
                    rule_type = :ruleType,
                    mandatory = :mandatory,
                    exception_allowed = :exceptionAllowed,
                    threshold = :threshold,
                    failure_outcome = :failureOutcome,
                    policy_version = :policyVersion,
                    active = :active,
                    updated_by = :actor
                WHERE id = :id
                """,
                ruleParams(rule, actor));
    }

    public Optional<CreditRule> findRule(UUID id) {
        return one("SELECT " + RULE_COLUMNS + " FROM credit_assessment_rules WHERE id = :id",
                new MapSqlParameterSource("id", id), RULE_MAPPER);
    }

    public Optional<CreditRule> findRuleByCode(String code) {
        return one("SELECT " + RULE_COLUMNS + " FROM credit_assessment_rules WHERE code = :code",
                new MapSqlParameterSource("code", code), RULE_MAPPER);
    }

    public List<CreditRule> listRules() {
        return jdbc.query(
                "SELECT " + RULE_COLUMNS + " FROM credit_assessment_rules ORDER BY code",
                RULE_MAPPER);
    }

    public List<CreditRule> applicableRules(String segment, UUID productId) {
        return jdbc.query(
                "SELECT " + RULE_COLUMNS + """
                FROM credit_assessment_rules
                WHERE active = TRUE
                  AND (segment = 'ANY' OR segment = :segment)
                  AND (product_id IS NULL OR product_id = :productId)
                ORDER BY code
                """,
                new MapSqlParameterSource().addValue("segment", segment).addValue("productId", productId),
                RULE_MAPPER);
    }

    public int deleteRule(UUID id) {
        return jdbc.update(
                "DELETE FROM credit_assessment_rules WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    public long exceptionCount(String ruleCode) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM credit_policy_exceptions WHERE rule_code = :code",
                new MapSqlParameterSource("code", ruleCode),
                Long.class);
        return count == null ? 0 : count;
    }

    private String versionSelect(String where) {
        return """
                SELECT v.id, v.model_id, v.version_number, v.status, v.weights::text AS weights_json,
                       v.risk_categories::text AS risk_json, v.decision_thresholds::text AS threshold_json,
                       v.policy_version, v.change_reason, v.activated_at, v.created_at, v.created_by
                FROM credit_scoring_model_versions v
                JOIN credit_scoring_models m ON m.id = v.model_id
                """ + where;
    }

    private ScoringVersionRecord mapVersion(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new ScoringVersionRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("model_id", UUID.class),
                rs.getInt("version_number"),
                ModelStatus.valueOf(rs.getString("status")),
                read(rs.getString("weights_json"), ScoringWeights.class),
                read(rs.getString("risk_json"), RISKS),
                read(rs.getString("threshold_json"), DecisionThresholds.class),
                rs.getString("policy_version"),
                rs.getString("change_reason"),
                JdbcTimes.instant(rs, "activated_at"),
                JdbcTimes.instant(rs, "created_at"),
                rs.getString("created_by"));
    }

    private MapSqlParameterSource versionParams(ScoringVersionRecord version) {
        return new MapSqlParameterSource()
                .addValue("id", version.id())
                .addValue("modelId", version.modelId())
                .addValue("versionNumber", version.versionNumber())
                .addValue("status", version.status().name())
                .addValue("weights", json(version.weights()))
                .addValue("riskCategories", json(version.riskCategories()))
                .addValue("thresholds", json(version.thresholds()))
                .addValue("policyVersion", version.policyVersion())
                .addValue("changeReason", version.changeReason())
                .addValue("activatedAt", JdbcTimes.offset(version.activatedAt()))
                .addValue("createdBy", version.createdBy());
    }

    private static final String RULE_COLUMNS = """
            id, code, name, description, segment, product_id, rule_type, mandatory, exception_allowed,
            threshold, failure_outcome, policy_version, active
            """;

    private static final RowMapper<ScoringModelRecord> MODEL_MAPPER = (rs, rowNum) -> new ScoringModelRecord(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            ModelSegment.valueOf(rs.getString("segment")),
            rs.getObject("product_id", UUID.class),
            ModelStatus.valueOf(rs.getString("status")),
            rs.getObject("current_version_id", UUID.class),
            JdbcTimes.instant(rs, "created_at"),
            JdbcTimes.instant(rs, "updated_at"),
            rs.getString("created_by"),
            rs.getString("updated_by"));

    private static final RowMapper<CreditRule> RULE_MAPPER = (rs, rowNum) -> new CreditRule(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("segment"),
            rs.getObject("product_id", UUID.class),
            RuleType.valueOf(rs.getString("rule_type")),
            rs.getBoolean("mandatory"),
            rs.getBoolean("exception_allowed"),
            rs.getBigDecimal("threshold"),
            DecisionOutcome.valueOf(rs.getString("failure_outcome")),
            rs.getString("policy_version"),
            rs.getBoolean("active"));

    private MapSqlParameterSource modelParams(ScoringModelRecord model) {
        return new MapSqlParameterSource()
                .addValue("id", model.id())
                .addValue("code", model.code())
                .addValue("name", model.name())
                .addValue("segment", model.segment().name())
                .addValue("productId", model.productId())
                .addValue("status", model.status().name())
                .addValue("currentVersionId", model.currentVersionId())
                .addValue("createdBy", model.createdBy())
                .addValue("updatedBy", model.updatedBy());
    }

    private static MapSqlParameterSource ruleParams(CreditRule rule, String actor) {
        return new MapSqlParameterSource()
                .addValue("id", rule.id())
                .addValue("code", rule.code())
                .addValue("name", rule.name())
                .addValue("description", rule.description())
                .addValue("segment", rule.segment())
                .addValue("productId", rule.productId())
                .addValue("ruleType", rule.ruleType().name())
                .addValue("mandatory", rule.mandatory())
                .addValue("exceptionAllowed", rule.exceptionAllowed())
                .addValue("threshold", rule.threshold())
                .addValue("failureOutcome", rule.failureOutcome().name())
                .addValue("policyVersion", rule.policyVersion())
                .addValue("active", rule.active())
                .addValue("actor", actor);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Credit policy payload could not be written", ex);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Credit policy payload could not be read", ex);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Credit policy payload could not be read", ex);
        }
    }

    private <T> Optional<T> one(String sql, MapSqlParameterSource params, RowMapper<T> mapper) {
        return jdbc.query(sql, params, mapper).stream().findFirst();
    }
}
