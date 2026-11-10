package com.mfb.lending.module.loanproduct.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.loanproduct.domain.CustomerSegment;
import com.mfb.lending.module.loanproduct.domain.DayCountConvention;
import com.mfb.lending.module.loanproduct.domain.EarlyRepaymentRule;
import com.mfb.lending.module.loanproduct.domain.InterestMethod;
import com.mfb.lending.module.loanproduct.domain.InterestPeriod;
import com.mfb.lending.module.loanproduct.domain.LoanProductRecord;
import com.mfb.lending.module.loanproduct.domain.LoanProductVersionRecord;
import com.mfb.lending.module.loanproduct.domain.PenaltyBasis;
import com.mfb.lending.module.loanproduct.domain.ProductCategory;
import com.mfb.lending.module.loanproduct.domain.ProductStatus;
import com.mfb.lending.module.loanproduct.domain.ProductSummary;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.RoundingMethod;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LoanProductRepository {

    private static final String PRODUCT_COLUMNS = """
            id, product_code, category, status, current_version_id, created_at, updated_at, created_by, updated_by
            """;

    private static final String VERSION_COLUMNS = """
            id, product_id, version_number, status, product_name, description, customer_segment, currency_code,
            minimum_amount, maximum_amount, minimum_tenure, maximum_tenure, tenure_unit,
            interest_rate, interest_method, interest_period, repayment_frequency, day_count_convention,
            rounding_precision, rounding_method, grace_period_days, early_repayment_rule, early_repayment_fee_rate,
            guarantor_required, minimum_guarantors, guarantor_notes, credit_bureau_required, minimum_bureau_score,
            penalty_basis, penalty_rate, penalty_amount, penalty_grace_days, change_reason,
            effective_from, effective_to, submitted_by, submitted_at, approved_by, approved_at,
            activated_by, activated_at, suspended_by, suspended_at, deactivated_by, deactivated_at,
            created_at, updated_at, created_by, updated_by
            """;

    private static final RowMapper<LoanProductRecord> PRODUCT_MAPPER = (rs, rowNum) -> new LoanProductRecord(
            rs.getObject("id", UUID.class),
            rs.getString("product_code"),
            ProductCategory.valueOf(rs.getString("category")),
            ProductStatus.valueOf(rs.getString("status")),
            rs.getObject("current_version_id", UUID.class),
            JdbcTimes.instant(rs, "created_at"),
            JdbcTimes.instant(rs, "updated_at"),
            rs.getString("created_by"),
            rs.getString("updated_by"));

    private static final RowMapper<LoanProductVersionRecord> VERSION_MAPPER = (rs, rowNum) -> mapVersion(rs);

    private final NamedParameterJdbcTemplate jdbc;

    public LoanProductRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insertProduct(LoanProductRecord product) {
        jdbc.update(
                """
                INSERT INTO loan_products (id, product_code, category, status, current_version_id, created_by, updated_by)
                VALUES (:id, :productCode, :category, :status, :currentVersionId, :createdBy, :updatedBy)
                """,
                productParams(product));
    }

    public void updateProduct(UUID id, ProductStatus status, UUID currentVersionId, String updatedBy) {
        jdbc.update(
                """
                UPDATE loan_products
                SET status = :status, current_version_id = :currentVersionId, updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("status", status.name())
                        .addValue("currentVersionId", currentVersionId)
                        .addValue("updatedBy", updatedBy));
    }

    public Optional<LoanProductRecord> lockProduct(UUID id) {
        return one("SELECT " + PRODUCT_COLUMNS + " FROM loan_products WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), PRODUCT_MAPPER);
    }

    public Optional<LoanProductRecord> findProduct(UUID id) {
        return one("SELECT " + PRODUCT_COLUMNS + " FROM loan_products WHERE id = :id",
                new MapSqlParameterSource("id", id), PRODUCT_MAPPER);
    }

    public void insertVersion(LoanProductVersionRecord version) {
        jdbc.update(
                """
                INSERT INTO loan_product_versions (
                    id, product_id, version_number, status, product_name, description, customer_segment, currency_code,
                    minimum_amount, maximum_amount, minimum_tenure, maximum_tenure, tenure_unit,
                    interest_rate, interest_method, interest_period, repayment_frequency, day_count_convention,
                    rounding_precision, rounding_method, grace_period_days, early_repayment_rule, early_repayment_fee_rate,
                    guarantor_required, minimum_guarantors, guarantor_notes, credit_bureau_required, minimum_bureau_score,
                    penalty_basis, penalty_rate, penalty_amount, penalty_grace_days, change_reason,
                    effective_from, effective_to, submitted_by, submitted_at, approved_by, approved_at,
                    activated_by, activated_at, suspended_by, suspended_at, deactivated_by, deactivated_at,
                    created_by, updated_by)
                VALUES (
                    :id, :productId, :versionNumber, :status, :productName, :description, :customerSegment, :currencyCode,
                    :minimumAmount, :maximumAmount, :minimumTenure, :maximumTenure, :tenureUnit,
                    :interestRate, :interestMethod, :interestPeriod, :repaymentFrequency, :dayCountConvention,
                    :roundingPrecision, :roundingMethod, :gracePeriodDays, :earlyRepaymentRule, :earlyRepaymentFeeRate,
                    :guarantorRequired, :minimumGuarantors, :guarantorNotes, :creditBureauRequired, :minimumBureauScore,
                    :penaltyBasis, :penaltyRate, :penaltyAmount, :penaltyGraceDays, :changeReason,
                    :effectiveFrom, :effectiveTo, :submittedBy, :submittedAt, :approvedBy, :approvedAt,
                    :activatedBy, :activatedAt, :suspendedBy, :suspendedAt, :deactivatedBy, :deactivatedAt,
                    :createdBy, :updatedBy)
                """,
                versionParams(version));
    }

    public int updateDraft(LoanProductVersionRecord version) {
        return jdbc.update(
                """
                UPDATE loan_product_versions
                SET product_name = :productName,
                    description = :description,
                    customer_segment = :customerSegment,
                    minimum_amount = :minimumAmount,
                    maximum_amount = :maximumAmount,
                    minimum_tenure = :minimumTenure,
                    maximum_tenure = :maximumTenure,
                    tenure_unit = :tenureUnit,
                    interest_rate = :interestRate,
                    interest_method = :interestMethod,
                    interest_period = :interestPeriod,
                    repayment_frequency = :repaymentFrequency,
                    day_count_convention = :dayCountConvention,
                    rounding_precision = :roundingPrecision,
                    rounding_method = :roundingMethod,
                    grace_period_days = :gracePeriodDays,
                    early_repayment_rule = :earlyRepaymentRule,
                    early_repayment_fee_rate = :earlyRepaymentFeeRate,
                    guarantor_required = :guarantorRequired,
                    minimum_guarantors = :minimumGuarantors,
                    guarantor_notes = :guarantorNotes,
                    credit_bureau_required = :creditBureauRequired,
                    minimum_bureau_score = :minimumBureauScore,
                    penalty_basis = :penaltyBasis,
                    penalty_rate = :penaltyRate,
                    penalty_amount = :penaltyAmount,
                    penalty_grace_days = :penaltyGraceDays,
                    updated_by = :updatedBy
                WHERE id = :id AND status = 'DRAFT'
                """,
                versionParams(version));
    }

    public void markSubmitted(UUID id, String actorId, Instant at) {
        jdbc.update(
                """
                UPDATE loan_product_versions
                SET status = 'PENDING_APPROVAL', submitted_by = :actorId, submitted_at = :at, updated_by = :actorId
                WHERE id = :id
                """,
                actorStamp(id, actorId, at));
    }

    public void reopenDraft(UUID id, String actorId) {
        jdbc.update(
                """
                UPDATE loan_product_versions
                SET status = 'DRAFT', submitted_by = NULL, submitted_at = NULL, updated_by = :actorId
                WHERE id = :id AND status = 'PENDING_APPROVAL'
                """,
                new MapSqlParameterSource().addValue("id", id).addValue("actorId", actorId));
    }

    public void markApproved(UUID id, String actorId, Instant at) {
        jdbc.update(
                """
                UPDATE loan_product_versions
                SET status = 'APPROVED', approved_by = :actorId, approved_at = :at, updated_by = :actorId
                WHERE id = :id
                """,
                actorStamp(id, actorId, at));
    }

    public void markActivated(UUID id, String actorId, Instant effectiveFrom, Instant at) {
        jdbc.update(
                """
                UPDATE loan_product_versions
                SET status = 'ACTIVE',
                    effective_from = :effectiveFrom,
                    effective_to = NULL,
                    activated_by = :actorId,
                    activated_at = :at,
                    suspended_by = NULL,
                    suspended_at = NULL,
                    updated_by = :actorId
                WHERE id = :id
                """,
                actorStamp(id, actorId, at).addValue("effectiveFrom", JdbcTimes.offset(effectiveFrom)));
    }

    public void markSuperseded(UUID id, Instant effectiveTo, String actorId) {
        jdbc.update(
                """
                UPDATE loan_product_versions
                SET status = 'SUPERSEDED', effective_to = :effectiveTo, updated_by = :actorId
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("effectiveTo", JdbcTimes.offset(effectiveTo))
                        .addValue("actorId", actorId));
    }

    public void markSuspended(UUID id, String actorId, Instant at) {
        jdbc.update(
                """
                UPDATE loan_product_versions
                SET status = 'SUSPENDED', suspended_by = :actorId, suspended_at = :at, updated_by = :actorId
                WHERE id = :id
                """,
                actorStamp(id, actorId, at));
    }

    public void markDeactivated(UUID id, String actorId, Instant at) {
        jdbc.update(
                """
                UPDATE loan_product_versions
                SET status = 'DEACTIVATED',
                    deactivated_by = :actorId,
                    deactivated_at = :at,
                    effective_to = CASE
                        WHEN effective_to IS NOT NULL THEN effective_to
                        WHEN effective_from IS NULL OR effective_from <= :at THEN :at
                        ELSE effective_from
                    END,
                    updated_by = :actorId
                WHERE id = :id
                """,
                actorStamp(id, actorId, at));
    }

    public Optional<LoanProductVersionRecord> findVersion(UUID id) {
        return one("SELECT " + VERSION_COLUMNS + " FROM loan_product_versions WHERE id = :id",
                new MapSqlParameterSource("id", id), VERSION_MAPPER);
    }

    public List<LoanProductVersionRecord> listVersions(UUID productId) {
        return jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM loan_product_versions WHERE product_id = :productId ORDER BY version_number DESC",
                new MapSqlParameterSource("productId", productId),
                VERSION_MAPPER);
    }

    public Optional<LoanProductVersionRecord> findUnpublished(UUID productId) {
        return one(
                "SELECT " + VERSION_COLUMNS + """
                 FROM loan_product_versions
                 WHERE product_id = :productId AND status IN ('DRAFT', 'PENDING_APPROVAL', 'APPROVED')
                 """,
                new MapSqlParameterSource("productId", productId),
                VERSION_MAPPER);
    }

    public int nextVersionNumber(UUID productId) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) FROM loan_product_versions WHERE product_id = :productId",
                new MapSqlParameterSource("productId", productId),
                Integer.class);
        return value == null ? 1 : value + 1;
    }

    public List<ProductSummary> search(ProductSearch search) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(search, params);
        params.addValue("limit", search.limit());
        params.addValue("offset", search.offset());
        return jdbc.query(
                """
                SELECT p.id, p.product_code, p.category, p.status,
                       v.product_name, v.customer_segment, v.currency_code,
                       v.minimum_amount, v.maximum_amount, v.minimum_tenure, v.maximum_tenure, v.tenure_unit,
                       v.interest_rate, v.interest_method, v.repayment_frequency, v.version_number
                FROM loan_products p
                JOIN loan_product_versions v ON v.id = p.current_version_id
                """ + where + " ORDER BY p.product_code LIMIT :limit OFFSET :offset",
                params,
                (rs, rowNum) -> new ProductSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("product_code"),
                        ProductCategory.valueOf(rs.getString("category")),
                        ProductStatus.valueOf(rs.getString("status")),
                        rs.getString("product_name"),
                        CustomerSegment.valueOf(rs.getString("customer_segment")),
                        rs.getString("currency_code"),
                        rs.getBigDecimal("minimum_amount"),
                        rs.getBigDecimal("maximum_amount"),
                        rs.getInt("minimum_tenure"),
                        rs.getInt("maximum_tenure"),
                        TenureUnit.valueOf(rs.getString("tenure_unit")),
                        rs.getBigDecimal("interest_rate"),
                        InterestMethod.valueOf(rs.getString("interest_method")),
                        RepaymentFrequency.valueOf(rs.getString("repayment_frequency")),
                        rs.getInt("version_number")));
    }

    public long count(ProductSearch search) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(search, params);
        Long total = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM loan_products p
                JOIN loan_product_versions v ON v.id = p.current_version_id
                """ + where,
                params,
                Long.class);
        return total == null ? 0 : total;
    }

    private static String where(ProductSearch search, MapSqlParameterSource params) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (search.offeredOnly()) {
            where.append("""
                     AND p.status = 'ACTIVE'
                     AND v.status = 'ACTIVE'
                     AND v.effective_from <= :now
                     AND (v.effective_to IS NULL OR v.effective_to > :now)
                    """);
            params.addValue("now", JdbcTimes.offset(search.now()));
        } else if (search.status() != null) {
            where.append(" AND p.status = :status");
            params.addValue("status", search.status());
        }
        if (search.category() != null) {
            where.append(" AND p.category = :category");
            params.addValue("category", search.category());
        }
        if (search.segment() != null) {
            where.append(" AND v.customer_segment = :segment");
            params.addValue("segment", search.segment());
        }
        return where.toString();
    }

    private static MapSqlParameterSource productParams(LoanProductRecord product) {
        return new MapSqlParameterSource()
                .addValue("id", product.id())
                .addValue("productCode", product.productCode())
                .addValue("category", product.category().name())
                .addValue("status", product.status().name())
                .addValue("currentVersionId", product.currentVersionId())
                .addValue("createdBy", product.createdBy())
                .addValue("updatedBy", product.updatedBy());
    }

    private static MapSqlParameterSource versionParams(LoanProductVersionRecord version) {
        return new MapSqlParameterSource()
                .addValue("id", version.id())
                .addValue("productId", version.productId())
                .addValue("versionNumber", version.versionNumber())
                .addValue("status", version.status().name())
                .addValue("productName", version.productName())
                .addValue("description", version.description())
                .addValue("customerSegment", version.customerSegment().name())
                .addValue("currencyCode", version.currencyCode())
                .addValue("minimumAmount", version.minimumAmount())
                .addValue("maximumAmount", version.maximumAmount())
                .addValue("minimumTenure", version.minimumTenure())
                .addValue("maximumTenure", version.maximumTenure())
                .addValue("tenureUnit", version.tenureUnit().name())
                .addValue("interestRate", version.interestRate())
                .addValue("interestMethod", version.interestMethod().name())
                .addValue("interestPeriod", version.interestPeriod().name())
                .addValue("repaymentFrequency", version.repaymentFrequency().name())
                .addValue("dayCountConvention", version.dayCountConvention().name())
                .addValue("roundingPrecision", version.roundingPrecision())
                .addValue("roundingMethod", version.roundingMethod().name())
                .addValue("gracePeriodDays", version.gracePeriodDays())
                .addValue("earlyRepaymentRule", version.earlyRepaymentRule().name())
                .addValue("earlyRepaymentFeeRate", version.earlyRepaymentFeeRate())
                .addValue("guarantorRequired", version.guarantorRequired())
                .addValue("minimumGuarantors", version.minimumGuarantors())
                .addValue("guarantorNotes", version.guarantorNotes())
                .addValue("creditBureauRequired", version.creditBureauRequired())
                .addValue("minimumBureauScore", version.minimumBureauScore())
                .addValue("penaltyBasis", version.penaltyBasis().name())
                .addValue("penaltyRate", version.penaltyRate())
                .addValue("penaltyAmount", version.penaltyAmount())
                .addValue("penaltyGraceDays", version.penaltyGraceDays())
                .addValue("changeReason", version.changeReason())
                .addValue("effectiveFrom", JdbcTimes.offset(version.effectiveFrom()))
                .addValue("effectiveTo", JdbcTimes.offset(version.effectiveTo()))
                .addValue("submittedBy", version.submittedBy())
                .addValue("submittedAt", JdbcTimes.offset(version.submittedAt()))
                .addValue("approvedBy", version.approvedBy())
                .addValue("approvedAt", JdbcTimes.offset(version.approvedAt()))
                .addValue("activatedBy", version.activatedBy())
                .addValue("activatedAt", JdbcTimes.offset(version.activatedAt()))
                .addValue("suspendedBy", version.suspendedBy())
                .addValue("suspendedAt", JdbcTimes.offset(version.suspendedAt()))
                .addValue("deactivatedBy", version.deactivatedBy())
                .addValue("deactivatedAt", JdbcTimes.offset(version.deactivatedAt()))
                .addValue("createdBy", version.createdBy())
                .addValue("updatedBy", version.updatedBy());
    }

    private static MapSqlParameterSource actorStamp(UUID id, String actorId, Instant at) {
        return new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("actorId", actorId)
                .addValue("at", JdbcTimes.offset(at));
    }

    private <T> Optional<T> one(String sql, MapSqlParameterSource params, RowMapper<T> mapper) {
        return jdbc.query(sql, params, mapper).stream().findFirst();
    }

    private static LoanProductVersionRecord mapVersion(ResultSet rs) throws SQLException {
        return new LoanProductVersionRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getInt("version_number"),
                ProductStatus.valueOf(rs.getString("status")),
                rs.getString("product_name"),
                rs.getString("description"),
                CustomerSegment.valueOf(rs.getString("customer_segment")),
                rs.getString("currency_code"),
                rs.getBigDecimal("minimum_amount"),
                rs.getBigDecimal("maximum_amount"),
                rs.getInt("minimum_tenure"),
                rs.getInt("maximum_tenure"),
                TenureUnit.valueOf(rs.getString("tenure_unit")),
                rs.getBigDecimal("interest_rate"),
                InterestMethod.valueOf(rs.getString("interest_method")),
                InterestPeriod.valueOf(rs.getString("interest_period")),
                RepaymentFrequency.valueOf(rs.getString("repayment_frequency")),
                DayCountConvention.valueOf(rs.getString("day_count_convention")),
                rs.getInt("rounding_precision"),
                RoundingMethod.valueOf(rs.getString("rounding_method")),
                rs.getInt("grace_period_days"),
                EarlyRepaymentRule.valueOf(rs.getString("early_repayment_rule")),
                rs.getBigDecimal("early_repayment_fee_rate"),
                rs.getBoolean("guarantor_required"),
                rs.getInt("minimum_guarantors"),
                rs.getString("guarantor_notes"),
                rs.getBoolean("credit_bureau_required"),
                rs.getObject("minimum_bureau_score", Integer.class),
                PenaltyBasis.valueOf(rs.getString("penalty_basis")),
                rs.getBigDecimal("penalty_rate"),
                rs.getBigDecimal("penalty_amount"),
                rs.getInt("penalty_grace_days"),
                rs.getString("change_reason"),
                JdbcTimes.instant(rs, "effective_from"),
                JdbcTimes.instant(rs, "effective_to"),
                rs.getString("submitted_by"),
                JdbcTimes.instant(rs, "submitted_at"),
                rs.getString("approved_by"),
                JdbcTimes.instant(rs, "approved_at"),
                rs.getString("activated_by"),
                JdbcTimes.instant(rs, "activated_at"),
                rs.getString("suspended_by"),
                JdbcTimes.instant(rs, "suspended_at"),
                rs.getString("deactivated_by"),
                JdbcTimes.instant(rs, "deactivated_at"),
                JdbcTimes.instant(rs, "created_at"),
                JdbcTimes.instant(rs, "updated_at"),
                rs.getString("created_by"),
                rs.getString("updated_by"));
    }
}
