package com.mfb.lending.module.loanproduct.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.module.loanproduct.domain.ApprovalRule;
import com.mfb.lending.module.loanproduct.domain.CollateralRule;
import com.mfb.lending.module.loanproduct.domain.EligibilityRule;
import com.mfb.lending.module.loanproduct.domain.FeeCalculation;
import com.mfb.lending.module.loanproduct.domain.FeeType;
import com.mfb.lending.module.loanproduct.domain.ProductComponents;
import com.mfb.lending.module.loanproduct.domain.ProductConfiguration;
import com.mfb.lending.module.loanproduct.domain.ProductDocumentRequirement;
import com.mfb.lending.module.loanproduct.domain.ProductFee;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LoanProductComponentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public LoanProductComponentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void replace(UUID versionId, ProductConfiguration configuration) {
        MapSqlParameterSource version = new MapSqlParameterSource("versionId", versionId);
        jdbc.update("DELETE FROM loan_product_fees WHERE version_id = :versionId", version);
        jdbc.update("DELETE FROM loan_product_documents WHERE version_id = :versionId", version);
        jdbc.update("DELETE FROM loan_product_eligibility_rules WHERE version_id = :versionId", version);
        jdbc.update("DELETE FROM loan_product_collateral_rules WHERE version_id = :versionId", version);
        jdbc.update("DELETE FROM loan_product_approval_rules WHERE version_id = :versionId", version);
        insertFees(versionId, configuration.fees());
        insertDocuments(versionId, configuration.documents());
        insertEligibility(versionId, configuration.eligibilityRules());
        insertCollateral(versionId, configuration.collateralRules());
        insertApprovals(versionId, configuration.approvalRules());
    }

    public ProductComponents load(UUID versionId) {
        MapSqlParameterSource params = new MapSqlParameterSource("versionId", versionId);
        List<ProductFee> fees = jdbc.query(
                """
                SELECT id, version_id, fee_type, name, calculation, amount, rate
                FROM loan_product_fees
                WHERE version_id = :versionId
                ORDER BY name
                """,
                params,
                (rs, rowNum) -> new ProductFee(
                        rs.getObject("id", UUID.class),
                        rs.getObject("version_id", UUID.class),
                        FeeType.valueOf(rs.getString("fee_type")),
                        rs.getString("name"),
                        FeeCalculation.valueOf(rs.getString("calculation")),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("rate")));
        List<ProductDocumentRequirement> documents = jdbc.query(
                """
                SELECT id, version_id, document_code, document_name, required
                FROM loan_product_documents
                WHERE version_id = :versionId
                ORDER BY document_code
                """,
                params,
                (rs, rowNum) -> new ProductDocumentRequirement(
                        rs.getObject("id", UUID.class),
                        rs.getObject("version_id", UUID.class),
                        rs.getString("document_code"),
                        rs.getString("document_name"),
                        rs.getBoolean("required")));
        List<EligibilityRule> eligibility = jdbc.query(
                """
                SELECT id, version_id, rule_code, description
                FROM loan_product_eligibility_rules
                WHERE version_id = :versionId
                ORDER BY rule_code
                """,
                params,
                (rs, rowNum) -> new EligibilityRule(
                        rs.getObject("id", UUID.class),
                        rs.getObject("version_id", UUID.class),
                        rs.getString("rule_code"),
                        rs.getString("description")));
        List<CollateralRule> collateral = jdbc.query(
                """
                SELECT id, version_id, collateral_type, description, mandatory, minimum_coverage_percent
                FROM loan_product_collateral_rules
                WHERE version_id = :versionId
                ORDER BY collateral_type
                """,
                params,
                (rs, rowNum) -> new CollateralRule(
                        rs.getObject("id", UUID.class),
                        rs.getObject("version_id", UUID.class),
                        rs.getString("collateral_type"),
                        rs.getString("description"),
                        rs.getBoolean("mandatory"),
                        rs.getBigDecimal("minimum_coverage_percent")));
        List<ApprovalRule> approvals = jdbc.query(
                """
                SELECT id, version_id, step_order, role_code, description
                FROM loan_product_approval_rules
                WHERE version_id = :versionId
                ORDER BY step_order
                """,
                params,
                (rs, rowNum) -> new ApprovalRule(
                        rs.getObject("id", UUID.class),
                        rs.getObject("version_id", UUID.class),
                        rs.getInt("step_order"),
                        rs.getString("role_code"),
                        rs.getString("description")));
        return new ProductComponents(fees, documents, eligibility, collateral, approvals);
    }

    private void insertFees(UUID versionId, List<ProductFee> fees) {
        if (fees.isEmpty()) {
            return;
        }
        List<MapSqlParameterSource> batch = new ArrayList<>();
        for (ProductFee fee : fees) {
            batch.add(new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("versionId", versionId)
                    .addValue("feeType", fee.feeType().name())
                    .addValue("name", fee.name())
                    .addValue("calculation", fee.calculation().name())
                    .addValue("amount", fee.amount())
                    .addValue("rate", fee.rate()));
        }
        jdbc.batchUpdate(
                """
                INSERT INTO loan_product_fees (id, version_id, fee_type, name, calculation, amount, rate)
                VALUES (:id, :versionId, :feeType, :name, :calculation, :amount, :rate)
                """,
                batch.toArray(MapSqlParameterSource[]::new));
    }

    private void insertDocuments(UUID versionId, List<ProductDocumentRequirement> documents) {
        if (documents.isEmpty()) {
            return;
        }
        List<MapSqlParameterSource> batch = new ArrayList<>();
        for (ProductDocumentRequirement document : documents) {
            batch.add(new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("versionId", versionId)
                    .addValue("documentCode", document.documentCode())
                    .addValue("documentName", document.documentName())
                    .addValue("required", document.required()));
        }
        jdbc.batchUpdate(
                """
                INSERT INTO loan_product_documents (id, version_id, document_code, document_name, required)
                VALUES (:id, :versionId, :documentCode, :documentName, :required)
                """,
                batch.toArray(MapSqlParameterSource[]::new));
    }

    private void insertEligibility(UUID versionId, List<EligibilityRule> rules) {
        if (rules.isEmpty()) {
            return;
        }
        List<MapSqlParameterSource> batch = new ArrayList<>();
        for (EligibilityRule rule : rules) {
            batch.add(new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("versionId", versionId)
                    .addValue("ruleCode", rule.ruleCode())
                    .addValue("description", rule.description()));
        }
        jdbc.batchUpdate(
                """
                INSERT INTO loan_product_eligibility_rules (id, version_id, rule_code, description)
                VALUES (:id, :versionId, :ruleCode, :description)
                """,
                batch.toArray(MapSqlParameterSource[]::new));
    }

    private void insertCollateral(UUID versionId, List<CollateralRule> rules) {
        if (rules.isEmpty()) {
            return;
        }
        List<MapSqlParameterSource> batch = new ArrayList<>();
        for (CollateralRule rule : rules) {
            batch.add(new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("versionId", versionId)
                    .addValue("collateralType", rule.collateralType())
                    .addValue("description", rule.description())
                    .addValue("mandatory", rule.mandatory())
                    .addValue("coverage", rule.minimumCoveragePercent()));
        }
        jdbc.batchUpdate(
                """
                INSERT INTO loan_product_collateral_rules (
                    id, version_id, collateral_type, description, mandatory, minimum_coverage_percent)
                VALUES (:id, :versionId, :collateralType, :description, :mandatory, :coverage)
                """,
                batch.toArray(MapSqlParameterSource[]::new));
    }

    private void insertApprovals(UUID versionId, List<ApprovalRule> rules) {
        if (rules.isEmpty()) {
            return;
        }
        List<MapSqlParameterSource> batch = new ArrayList<>();
        for (ApprovalRule rule : rules) {
            batch.add(new MapSqlParameterSource()
                    .addValue("id", UUID.randomUUID())
                    .addValue("versionId", versionId)
                    .addValue("stepOrder", rule.stepOrder())
                    .addValue("roleCode", rule.roleCode())
                    .addValue("description", rule.description()));
        }
        jdbc.batchUpdate(
                """
                INSERT INTO loan_product_approval_rules (id, version_id, step_order, role_code, description)
                VALUES (:id, :versionId, :stepOrder, :roleCode, :description)
                """,
                batch.toArray(MapSqlParameterSource[]::new));
    }
}
