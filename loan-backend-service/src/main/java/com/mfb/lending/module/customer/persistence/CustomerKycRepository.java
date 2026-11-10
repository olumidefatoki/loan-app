package com.mfb.lending.module.customer.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.module.customer.domain.CheckOutcome;
import com.mfb.lending.module.customer.domain.CheckType;
import com.mfb.lending.module.customer.domain.KycCheckRecord;
import com.mfb.lending.module.customer.domain.ReviewCaseRecord;
import com.mfb.lending.module.customer.domain.ReviewCaseType;
import com.mfb.lending.module.customer.domain.RiskClassification;
import com.mfb.lending.module.customer.domain.RiskProfileRecord;
import com.mfb.lending.module.customer.domain.ScreeningOutcome;
import com.mfb.lending.module.customer.domain.ScreeningRecord;
import com.mfb.lending.module.customer.domain.ScreeningType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerKycRepository {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public CustomerKycRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insertCheck(KycCheckRecord check) {
        jdbc.update(
                """
                INSERT INTO customer_kyc (id, customer_id, check_type, outcome, provider, reference, detail, completed_at)
                VALUES (:id, :customerId, :checkType, :outcome, :provider, :reference, :detail, :completedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", check.id())
                        .addValue("customerId", check.customerId())
                        .addValue("checkType", check.type().name())
                        .addValue("outcome", check.outcome().name())
                        .addValue("provider", check.provider())
                        .addValue("reference", check.reference())
                        .addValue("detail", check.detail())
                        .addValue("completedAt", JdbcTimes.offset(check.completedAt())));
    }

    public List<KycCheckRecord> checks(UUID customerId) {
        return jdbc.query(
                """
                SELECT id, customer_id, check_type, outcome, provider, reference, detail, completed_at
                FROM customer_kyc WHERE customer_id = :customerId ORDER BY completed_at DESC
                """,
                new MapSqlParameterSource("customerId", customerId),
                (rs, rowNum) -> new KycCheckRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        CheckType.valueOf(rs.getString("check_type")),
                        CheckOutcome.valueOf(rs.getString("outcome")),
                        rs.getString("provider"),
                        rs.getString("reference"),
                        rs.getString("detail"),
                        JdbcTimes.instant(rs, "completed_at")));
    }

    public void insertScreening(ScreeningRecord screening) {
        jdbc.update(
                """
                INSERT INTO customer_screening_results (id, customer_id, screening_type, outcome, provider, detail, screened_at)
                VALUES (:id, :customerId, :screeningType, :outcome, :provider, :detail, :screenedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", screening.id())
                        .addValue("customerId", screening.customerId())
                        .addValue("screeningType", screening.type().name())
                        .addValue("outcome", screening.outcome().name())
                        .addValue("provider", screening.provider())
                        .addValue("detail", screening.detail())
                        .addValue("screenedAt", JdbcTimes.offset(screening.screenedAt())));
    }

    public List<ScreeningRecord> screenings(UUID customerId) {
        return jdbc.query(
                """
                SELECT id, customer_id, screening_type, outcome, provider, detail, screened_at
                FROM customer_screening_results WHERE customer_id = :customerId ORDER BY screened_at DESC
                """,
                new MapSqlParameterSource("customerId", customerId),
                (rs, rowNum) -> new ScreeningRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        ScreeningType.valueOf(rs.getString("screening_type")),
                        ScreeningOutcome.valueOf(rs.getString("outcome")),
                        rs.getString("provider"),
                        rs.getString("detail"),
                        JdbcTimes.instant(rs, "screened_at")));
    }

    public void upsertRisk(UUID customerId, RiskClassification classification, int score, List<String> factors, String assessedBy) {
        jdbc.update(
                """
                INSERT INTO customer_risk_profiles (customer_id, classification, score, factors, assessed_by)
                VALUES (:customerId, :classification, :score, CAST(:factors AS JSONB), :assessedBy)
                ON CONFLICT (customer_id) DO UPDATE SET
                    classification = EXCLUDED.classification,
                    score = EXCLUDED.score,
                    factors = EXCLUDED.factors,
                    assessed_at = NOW(),
                    assessed_by = EXCLUDED.assessed_by
                """,
                new MapSqlParameterSource()
                        .addValue("customerId", customerId)
                        .addValue("classification", classification.name())
                        .addValue("score", score)
                        .addValue("factors", json(factors))
                        .addValue("assessedBy", assessedBy));
    }

    public Optional<RiskProfileRecord> risk(UUID customerId) {
        return jdbc.query(
                "SELECT customer_id, classification, score, assessed_at FROM customer_risk_profiles WHERE customer_id = :customerId",
                new MapSqlParameterSource("customerId", customerId),
                (rs, rowNum) -> new RiskProfileRecord(
                        rs.getObject("customer_id", UUID.class),
                        RiskClassification.valueOf(rs.getString("classification")),
                        rs.getInt("score"),
                        JdbcTimes.instant(rs, "assessed_at")))
                .stream().findFirst();
    }

    public void insertReview(ReviewCaseRecord review, UUID kycId) {
        jdbc.update(
                """
                INSERT INTO customer_review_cases (id, customer_id, kyc_id, case_type, status, mandatory_hold, summary, opened_at)
                VALUES (:id, :customerId, :kycId, :caseType, :status, :mandatoryHold, :summary, :openedAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", review.id())
                        .addValue("customerId", review.customerId())
                        .addValue("kycId", kycId)
                        .addValue("caseType", review.type().name())
                        .addValue("status", review.status())
                        .addValue("mandatoryHold", review.mandatoryHold())
                        .addValue("summary", review.summary())
                        .addValue("openedAt", JdbcTimes.offset(review.openedAt())));
    }

    public List<ReviewCaseRecord> reviews(UUID customerId) {
        return jdbc.query(
                """
                SELECT id, customer_id, case_type, status, mandatory_hold, summary, opened_at
                FROM customer_review_cases WHERE customer_id = :customerId ORDER BY opened_at DESC
                """,
                new MapSqlParameterSource("customerId", customerId),
                (rs, rowNum) -> new ReviewCaseRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        ReviewCaseType.valueOf(rs.getString("case_type")),
                        rs.getString("status"),
                        rs.getBoolean("mandatory_hold"),
                        rs.getString("summary"),
                        JdbcTimes.instant(rs, "opened_at")));
    }

    public int openMandatoryCount(UUID customerId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM customer_review_cases
                WHERE customer_id = :customerId AND status = 'OPEN' AND mandatory_hold = TRUE
                """,
                new MapSqlParameterSource("customerId", customerId),
                Integer.class);
        return count == null ? 0 : count;
    }

    private String json(List<String> factors) {
        try {
            return objectMapper.writeValueAsString(factors);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Risk factors could not be stored", ex);
        }
    }
}
