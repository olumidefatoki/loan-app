package com.mfb.lending.module.collateral.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.collateral.domain.AllocationRecord;
import com.mfb.lending.module.collateral.domain.CheckStatus;
import com.mfb.lending.module.collateral.domain.CollateralDocumentCode;
import com.mfb.lending.module.collateral.domain.CollateralDocumentRecord;
import com.mfb.lending.module.collateral.domain.CollateralRecord;
import com.mfb.lending.module.collateral.domain.CollateralType;
import com.mfb.lending.module.collateral.domain.EncumbranceRecord;
import com.mfb.lending.module.collateral.domain.EncumbranceSource;
import com.mfb.lending.module.collateral.domain.EncumbranceStatus;
import com.mfb.lending.module.collateral.domain.InsuranceCheckStatus;
import com.mfb.lending.module.collateral.domain.InsuranceRecord;
import com.mfb.lending.module.collateral.domain.PledgePosition;
import com.mfb.lending.module.collateral.domain.PledgeStatus;
import com.mfb.lending.module.collateral.domain.ReleaseRecord;
import com.mfb.lending.module.collateral.domain.ValuationKind;
import com.mfb.lending.module.collateral.domain.ValuationRecord;
import com.mfb.lending.module.collateral.domain.ValuationStatus;
import com.mfb.lending.module.collateral.domain.VerificationStatus;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CollateralRepository {

    private static final String COLLATERAL_COLUMNS = """
            c.id, c.reference, c.customer_id, c.collateral_type, c.description, c.owner_name, c.owner_identifier,
            c.identification_number, c.location, c.market_value, c.forced_sale_value, c.eligible_lending_value,
            c.valuation_date, c.valuation_expires_on, c.valuer_name, c.valuer_reference, c.lending_ratio,
            c.verification_status, c.ownership_status, c.document_status, c.legal_status, c.insurance_status,
            c.encumbrance_status, c.approved_by, c.approved_at, c.rejected_by, c.rejected_at, c.rejection_reason,
            c.released_at, c.created_at, c.updated_at, c.created_by, c.updated_by
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public CollateralRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextReference() {
        Long value = jdbc.queryForObject(
                "SELECT nextval('collateral_reference_seq')",
                new MapSqlParameterSource(),
                Long.class);
        if (value == null || value < 1 || value > 999_999_999_999L) {
            throw new IllegalStateException("Collateral reference sequence is exhausted");
        }
        return "CL" + String.format("%012d", value);
    }

    public void insert(CollateralRecord collateral) {
        jdbc.update(
                """
                INSERT INTO collaterals (
                    id, reference, customer_id, collateral_type, description, owner_name, owner_identifier,
                    identification_number, location, market_value, forced_sale_value, eligible_lending_value,
                    valuation_date, valuation_expires_on, valuer_name, valuer_reference, lending_ratio,
                    verification_status, ownership_status, document_status, legal_status, insurance_status,
                    encumbrance_status, created_by, updated_by)
                VALUES (
                    :id, :reference, :customerId, :type, :description, :ownerName, :ownerIdentifier,
                    :identificationNumber, :location, :marketValue, :forcedSaleValue, :eligibleLendingValue,
                    :valuationDate, :valuationExpiresOn, :valuerName, :valuerReference, :lendingRatio,
                    :verificationStatus, :ownershipStatus, :documentStatus, :legalStatus, :insuranceStatus,
                    :encumbranceStatus, :createdBy, :updatedBy)
                """,
                collateralParams(collateral));
    }

    public Optional<CollateralRecord> find(UUID id) {
        return one(COLLATERAL_COLUMNS + " FROM collaterals c WHERE c.id = :id", new MapSqlParameterSource("id", id));
    }

    public Optional<CollateralRecord> lock(UUID id) {
        return one(COLLATERAL_COLUMNS + " FROM collaterals c WHERE c.id = :id FOR UPDATE", new MapSqlParameterSource("id", id));
    }

    public List<CollateralRecord> search(
            UUID customerId,
            CollateralType type,
            VerificationStatus status,
            String query,
            Collection<UUID> branchIds,
            boolean unrestricted,
            int offset,
            int limit) {
        Filter filter = filter(customerId, type, status, query, branchIds, unrestricted);
        filter.params().addValue("offset", offset).addValue("limit", limit);
        return jdbc.query(
                "SELECT " + COLLATERAL_COLUMNS + filter.sql() + " ORDER BY c.created_at DESC LIMIT :limit OFFSET :offset",
                filter.params(),
                (rs, rowNum) -> mapCollateral(rs));
    }

    public long count(
            UUID customerId,
            CollateralType type,
            VerificationStatus status,
            String query,
            Collection<UUID> branchIds,
            boolean unrestricted) {
        Filter filter = filter(customerId, type, status, query, branchIds, unrestricted);
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + filter.sql(), filter.params(), Long.class);
        return total == null ? 0 : total;
    }

    public void updateDetails(
            UUID id,
            String description,
            String ownerName,
            String ownerIdentifier,
            String identificationNumber,
            String location,
            String updatedBy) {
        jdbc.update(
                """
                UPDATE collaterals
                SET description = :description,
                    owner_name = :ownerName,
                    owner_identifier = :ownerIdentifier,
                    identification_number = :identificationNumber,
                    location = :location,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("description", description)
                        .addValue("ownerName", ownerName)
                        .addValue("ownerIdentifier", ownerIdentifier)
                        .addValue("identificationNumber", identificationNumber)
                        .addValue("location", location)
                        .addValue("updatedBy", updatedBy));
    }

    public void applyValuation(
            UUID id,
            BigDecimal marketValue,
            BigDecimal forcedSaleValue,
            BigDecimal eligibleLendingValue,
            BigDecimal lendingRatio,
            LocalDate valuationDate,
            LocalDate expiryDate,
            String valuerName,
            String valuerReference,
            String updatedBy) {
        jdbc.update(
                """
                UPDATE collaterals
                SET market_value = :marketValue,
                    forced_sale_value = :forcedSaleValue,
                    eligible_lending_value = :eligibleLendingValue,
                    lending_ratio = :lendingRatio,
                    valuation_date = :valuationDate,
                    valuation_expires_on = :expiryDate,
                    valuer_name = :valuerName,
                    valuer_reference = :valuerReference,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("marketValue", marketValue)
                        .addValue("forcedSaleValue", forcedSaleValue)
                        .addValue("eligibleLendingValue", eligibleLendingValue)
                        .addValue("lendingRatio", lendingRatio)
                        .addValue("valuationDate", valuationDate)
                        .addValue("expiryDate", expiryDate)
                        .addValue("valuerName", valuerName)
                        .addValue("valuerReference", valuerReference)
                        .addValue("updatedBy", updatedBy));
    }

    public void updateVerification(
            UUID id,
            VerificationStatus verificationStatus,
            CheckStatus ownershipStatus,
            CheckStatus documentStatus,
            CheckStatus legalStatus,
            InsuranceCheckStatus insuranceStatus,
            EncumbranceStatus encumbranceStatus,
            UUID approvedBy,
            boolean approved,
            UUID rejectedBy,
            String rejectionReason,
            boolean rejected,
            String updatedBy) {
        jdbc.update(
                """
                UPDATE collaterals
                SET verification_status = :verificationStatus,
                    ownership_status = :ownershipStatus,
                    document_status = :documentStatus,
                    legal_status = :legalStatus,
                    insurance_status = :insuranceStatus,
                    encumbrance_status = :encumbranceStatus,
                    approved_by = :approvedBy,
                    approved_at = CASE WHEN :approved THEN NOW() ELSE NULL END,
                    rejected_by = :rejectedBy,
                    rejected_at = CASE WHEN :rejected THEN NOW() ELSE NULL END,
                    rejection_reason = :rejectionReason,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("verificationStatus", verificationStatus.name())
                        .addValue("ownershipStatus", ownershipStatus.name())
                        .addValue("documentStatus", documentStatus.name())
                        .addValue("legalStatus", legalStatus.name())
                        .addValue("insuranceStatus", insuranceStatus.name())
                        .addValue("encumbranceStatus", encumbranceStatus.name())
                        .addValue("approvedBy", approvedBy)
                        .addValue("approved", approved)
                        .addValue("rejectedBy", rejectedBy)
                        .addValue("rejected", rejected)
                        .addValue("rejectionReason", rejectionReason)
                        .addValue("updatedBy", updatedBy));
    }

    public void markReleased(UUID id, String updatedBy) {
        jdbc.update(
                """
                UPDATE collaterals
                SET verification_status = 'RELEASED', released_at = NOW(), updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource().addValue("id", id).addValue("updatedBy", updatedBy));
    }

    public void insertDocument(CollateralDocumentRecord document, String uploadedBy) {
        jdbc.update(
                """
                INSERT INTO collateral_documents (
                    id, collateral_id, document_code, file_name, content_type, size_bytes, checksum_sha256, storage_key, uploaded_by)
                VALUES (
                    :id, :collateralId, :documentCode, :fileName, :contentType, :sizeBytes, :checksum, :storageKey, :uploadedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", document.id())
                        .addValue("collateralId", document.collateralId())
                        .addValue("documentCode", document.code().name())
                        .addValue("fileName", document.fileName())
                        .addValue("contentType", document.contentType())
                        .addValue("sizeBytes", document.sizeBytes())
                        .addValue("checksum", document.checksumSha256())
                        .addValue("storageKey", document.storageKey())
                        .addValue("uploadedBy", uploadedBy));
    }

    public List<CollateralDocumentRecord> listDocuments(UUID collateralId) {
        return jdbc.query(
                """
                SELECT id, collateral_id, document_code, file_name, content_type, size_bytes, checksum_sha256, storage_key, created_at
                FROM collateral_documents
                WHERE collateral_id = :collateralId
                ORDER BY created_at
                """,
                new MapSqlParameterSource("collateralId", collateralId),
                (rs, rowNum) -> new CollateralDocumentRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("collateral_id", UUID.class),
                        CollateralDocumentCode.valueOf(rs.getString("document_code")),
                        rs.getString("file_name"),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("checksum_sha256"),
                        rs.getString("storage_key"),
                        JdbcTimes.instant(rs, "created_at")));
    }

    public Optional<CollateralDocumentRecord> findDocument(UUID collateralId, UUID documentId) {
        return jdbc.query(
                """
                SELECT id, collateral_id, document_code, file_name, content_type, size_bytes, checksum_sha256, storage_key, created_at
                FROM collateral_documents
                WHERE collateral_id = :collateralId AND id = :id
                """,
                new MapSqlParameterSource().addValue("collateralId", collateralId).addValue("id", documentId),
                (rs, rowNum) -> new CollateralDocumentRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("collateral_id", UUID.class),
                        CollateralDocumentCode.valueOf(rs.getString("document_code")),
                        rs.getString("file_name"),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("checksum_sha256"),
                        rs.getString("storage_key"),
                        JdbcTimes.instant(rs, "created_at"))).stream().findFirst();
    }

    public boolean hasDocument(UUID collateralId) {
        return countDocuments(collateralId, null) > 0;
    }

    public boolean hasDocumentCode(UUID collateralId, CollateralDocumentCode... codes) {
        return countDocuments(collateralId, List.of(codes)) > 0;
    }

    public void insertValuation(ValuationRecord valuation) {
        jdbc.update(
                """
                INSERT INTO collateral_valuations (
                    id, collateral_id, kind, status, market_value, forced_sale_value, lending_ratio,
                    calculated_eligible_value, accepted_eligible_value, valuation_date, expiry_date,
                    valuer_name, valuer_reference, submitted_by, decided_by, decided_at, decision_reason)
                VALUES (
                    :id, :collateralId, :kind, :status, :marketValue, :forcedSaleValue, :lendingRatio,
                    :calculatedEligibleValue, :acceptedEligibleValue, :valuationDate, :expiryDate,
                    :valuerName, :valuerReference, :submittedBy, :decidedBy, :decidedAt, :decisionReason)
                """,
                new MapSqlParameterSource()
                        .addValue("id", valuation.id())
                        .addValue("collateralId", valuation.collateralId())
                        .addValue("kind", valuation.kind().name())
                        .addValue("status", valuation.status().name())
                        .addValue("marketValue", valuation.marketValue())
                        .addValue("forcedSaleValue", valuation.forcedSaleValue())
                        .addValue("lendingRatio", valuation.lendingRatio())
                        .addValue("calculatedEligibleValue", valuation.calculatedEligibleValue())
                        .addValue("acceptedEligibleValue", valuation.acceptedEligibleValue())
                        .addValue("valuationDate", valuation.valuationDate())
                        .addValue("expiryDate", valuation.expiryDate())
                        .addValue("valuerName", valuation.valuerName())
                        .addValue("valuerReference", valuation.valuerReference())
                        .addValue("submittedBy", valuation.submittedBy())
                        .addValue("decidedBy", valuation.decidedBy())
                        .addValue("decidedAt", JdbcTimes.offset(valuation.decidedAt()))
                        .addValue("decisionReason", valuation.decisionReason()));
    }

    public List<ValuationRecord> listValuations(UUID collateralId) {
        return jdbc.query(
                valuationSelect() + " WHERE collateral_id = :collateralId ORDER BY created_at",
                new MapSqlParameterSource("collateralId", collateralId),
                (rs, rowNum) -> mapValuation(rs));
    }

    public Optional<ValuationRecord> findValuation(UUID collateralId, UUID valuationId) {
        return jdbc.query(
                valuationSelect() + " WHERE collateral_id = :collateralId AND id = :id",
                new MapSqlParameterSource().addValue("collateralId", collateralId).addValue("id", valuationId),
                (rs, rowNum) -> mapValuation(rs)).stream().findFirst();
    }

    public Optional<ValuationRecord> lockValuation(UUID collateralId, UUID valuationId) {
        return jdbc.query(
                valuationSelect() + " WHERE collateral_id = :collateralId AND id = :id FOR UPDATE",
                new MapSqlParameterSource().addValue("collateralId", collateralId).addValue("id", valuationId),
                (rs, rowNum) -> mapValuation(rs)).stream().findFirst();
    }

    public boolean hasPendingValuation(UUID collateralId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM collateral_valuations
                WHERE collateral_id = :id AND status = 'PENDING_APPROVAL'
                """,
                new MapSqlParameterSource("id", collateralId),
                Integer.class);
        return count != null && count > 0;
    }

    public boolean hasInitialValuation(UUID collateralId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM collateral_valuations
                WHERE collateral_id = :id AND kind = 'INITIAL'
                """,
                new MapSqlParameterSource("id", collateralId),
                Integer.class);
        return count != null && count > 0;
    }

    public Optional<ValuationRecord> latestApproved(UUID collateralId) {
        return jdbc.query(
                valuationSelect() + """
                 WHERE collateral_id = :collateralId AND status = 'APPROVED'
                 ORDER BY decided_at DESC, created_at DESC
                 LIMIT 1
                """,
                new MapSqlParameterSource("collateralId", collateralId),
                (rs, rowNum) -> mapValuation(rs)).stream().findFirst();
    }

    public void decideValuation(UUID id, ValuationStatus status, UUID decidedBy, String reason) {
        jdbc.update(
                """
                UPDATE collateral_valuations
                SET status = :status, decided_by = :decidedBy, decided_at = NOW(), decision_reason = :reason
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("status", status.name())
                        .addValue("decidedBy", decidedBy)
                        .addValue("reason", reason));
    }

    public void insertInsurance(InsuranceRecord insurance) {
        jdbc.update(
                """
                INSERT INTO collateral_insurance (
                    id, collateral_id, insurer_name, policy_number, cover_amount, start_date, expiry_date, created_by)
                VALUES (
                    :id, :collateralId, :insurerName, :policyNumber, :coverAmount, :startDate, :expiryDate, :createdBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", insurance.id())
                        .addValue("collateralId", insurance.collateralId())
                        .addValue("insurerName", insurance.insurerName())
                        .addValue("policyNumber", insurance.policyNumber())
                        .addValue("coverAmount", insurance.coverAmount())
                        .addValue("startDate", insurance.startDate())
                        .addValue("expiryDate", insurance.expiryDate())
                        .addValue("createdBy", insurance.createdBy()));
    }

    public Optional<InsuranceRecord> latestInsurance(UUID collateralId) {
        return jdbc.query(
                """
                SELECT id, collateral_id, insurer_name, policy_number, cover_amount, start_date, expiry_date, created_at, created_by
                FROM collateral_insurance
                WHERE collateral_id = :collateralId
                ORDER BY created_at DESC
                LIMIT 1
                """,
                new MapSqlParameterSource("collateralId", collateralId),
                (rs, rowNum) -> new InsuranceRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("collateral_id", UUID.class),
                        rs.getString("insurer_name"),
                        rs.getString("policy_number"),
                        rs.getBigDecimal("cover_amount"),
                        rs.getObject("start_date", LocalDate.class),
                        rs.getObject("expiry_date", LocalDate.class),
                        JdbcTimes.instant(rs, "created_at"),
                        rs.getString("created_by"))).stream().findFirst();
    }

    public void insertEncumbrance(EncumbranceRecord encumbrance) {
        jdbc.update(
                """
                INSERT INTO collateral_encumbrances (
                    id, collateral_id, loan_collateral_id, source, holder_name, reference_number, amount, status, created_by)
                VALUES (
                    :id, :collateralId, :loanCollateralId, :source, :holderName, :referenceNumber, :amount, :status, :createdBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", encumbrance.id())
                        .addValue("collateralId", encumbrance.collateralId())
                        .addValue("loanCollateralId", encumbrance.loanCollateralId())
                        .addValue("source", encumbrance.source().name())
                        .addValue("holderName", encumbrance.holderName())
                        .addValue("referenceNumber", encumbrance.referenceNumber())
                        .addValue("amount", encumbrance.amount())
                        .addValue("status", encumbrance.status().name())
                        .addValue("createdBy", encumbrance.createdBy()));
    }

    public List<EncumbranceRecord> listEncumbrances(UUID collateralId) {
        return jdbc.query(
                """
                SELECT id, collateral_id, loan_collateral_id, source, holder_name, reference_number, amount, status,
                       noted_at, released_at, created_by
                FROM collateral_encumbrances
                WHERE collateral_id = :collateralId
                ORDER BY noted_at
                """,
                new MapSqlParameterSource("collateralId", collateralId),
                (rs, rowNum) -> mapEncumbrance(rs));
    }

    public boolean hasActiveExternalEncumbrance(UUID collateralId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM collateral_encumbrances
                WHERE collateral_id = :id AND source = 'EXTERNAL' AND status = 'ACTIVE'
                """,
                new MapSqlParameterSource("id", collateralId),
                Integer.class);
        return count != null && count > 0;
    }

    public void releaseExternalEncumbrances(UUID collateralId) {
        jdbc.update(
                """
                UPDATE collateral_encumbrances
                SET status = 'RELEASED', released_at = NOW()
                WHERE collateral_id = :id AND source = 'EXTERNAL' AND status = 'ACTIVE'
                """,
                new MapSqlParameterSource("id", collateralId));
    }

    public void releaseEncumbrances(UUID collateralId) {
        jdbc.update(
                """
                UPDATE collateral_encumbrances
                SET status = 'RELEASED', released_at = NOW()
                WHERE collateral_id = :id AND status = 'ACTIVE'
                """,
                new MapSqlParameterSource("id", collateralId));
    }

    public void insertAllocation(AllocationRecord allocation) {
        jdbc.update(
                """
                INSERT INTO loan_collaterals (
                    id, application_id, collateral_id, allocated_amount, shared_security, status, allocated_by)
                VALUES (
                    :id, :applicationId, :collateralId, :allocatedAmount, :sharedSecurity, :status, :allocatedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", allocation.id())
                        .addValue("applicationId", allocation.applicationId())
                        .addValue("collateralId", allocation.collateralId())
                        .addValue("allocatedAmount", allocation.allocatedAmount())
                        .addValue("sharedSecurity", allocation.sharedSecurity())
                        .addValue("status", allocation.status().name())
                        .addValue("allocatedBy", allocation.allocatedBy()));
    }

    public PledgePosition position(UUID collateralId) {
        return jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(allocated_amount), 0) AS allocated,
                       COUNT(*) FILTER (WHERE shared_security = FALSE) AS exclusive_count,
                       COUNT(*) FILTER (WHERE shared_security = TRUE) AS shared_count
                FROM loan_collaterals
                WHERE collateral_id = :id AND status = 'ACTIVE'
                """,
                new MapSqlParameterSource("id", collateralId),
                (rs, rowNum) -> new PledgePosition(
                        money(rs.getBigDecimal("allocated")),
                        rs.getInt("exclusive_count"),
                        rs.getInt("shared_count")));
    }

    public boolean hasActivePair(UUID applicationId, UUID collateralId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM loan_collaterals
                WHERE application_id = :applicationId AND collateral_id = :collateralId AND status = 'ACTIVE'
                """,
                new MapSqlParameterSource().addValue("applicationId", applicationId).addValue("collateralId", collateralId),
                Integer.class);
        return count != null && count > 0;
    }

    public List<AllocationRecord> listAllocations(UUID collateralId) {
        return jdbc.query(
                allocationSelect() + " WHERE collateral_id = :collateralId ORDER BY allocated_at",
                new MapSqlParameterSource("collateralId", collateralId),
                (rs, rowNum) -> mapAllocation(rs));
    }

    public List<AllocationRecord> listForApplication(UUID applicationId) {
        return jdbc.query(
                allocationSelect() + " WHERE application_id = :applicationId ORDER BY allocated_at",
                new MapSqlParameterSource("applicationId", applicationId),
                (rs, rowNum) -> mapAllocation(rs));
    }

    public List<ApplicationStatus> activeApplicationStatuses(UUID collateralId) {
        return jdbc.query(
                """
                SELECT application.status
                FROM loan_collaterals pledged
                JOIN loan_applications application ON application.id = pledged.application_id
                WHERE pledged.collateral_id = :id AND pledged.status = 'ACTIVE'
                """,
                new MapSqlParameterSource("id", collateralId),
                (rs, rowNum) -> ApplicationStatus.valueOf(rs.getString("status")));
    }

    public BigDecimal activeAllocatedForApplication(UUID applicationId) {
        BigDecimal value = jdbc.queryForObject(
                """
                SELECT COALESCE(SUM(allocated_amount), 0)
                FROM loan_collaterals
                WHERE application_id = :id AND status = 'ACTIVE'
                """,
                new MapSqlParameterSource("id", applicationId),
                BigDecimal.class);
        return money(value);
    }

    public void releaseAllocations(UUID collateralId) {
        jdbc.update(
                """
                UPDATE loan_collaterals
                SET status = 'RELEASED', released_at = NOW()
                WHERE collateral_id = :id AND status = 'ACTIVE'
                """,
                new MapSqlParameterSource("id", collateralId));
    }

    public void insertRelease(ReleaseRecord release) {
        jdbc.update(
                """
                INSERT INTO collateral_release_history (
                    id, collateral_id, settlement_reference, discharge_reference, reason, released_by)
                VALUES (
                    :id, :collateralId, :settlementReference, :dischargeReference, :reason, :releasedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", release.id())
                        .addValue("collateralId", release.collateralId())
                        .addValue("settlementReference", release.settlementReference())
                        .addValue("dischargeReference", release.dischargeReference())
                        .addValue("reason", release.reason())
                        .addValue("releasedBy", release.releasedBy()));
    }

    public List<ReleaseRecord> listReleases(UUID collateralId) {
        return jdbc.query(
                """
                SELECT id, collateral_id, settlement_reference, discharge_reference, reason, released_by, released_at
                FROM collateral_release_history
                WHERE collateral_id = :collateralId
                ORDER BY released_at
                """,
                new MapSqlParameterSource("collateralId", collateralId),
                (rs, rowNum) -> new ReleaseRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("collateral_id", UUID.class),
                        rs.getString("settlement_reference"),
                        rs.getString("discharge_reference"),
                        rs.getString("reason"),
                        rs.getObject("released_by", UUID.class),
                        JdbcTimes.instant(rs, "released_at")));
    }

    private int countDocuments(UUID collateralId, List<CollateralDocumentCode> codes) {
        MapSqlParameterSource params = new MapSqlParameterSource("collateralId", collateralId);
        String sql = "SELECT COUNT(*) FROM collateral_documents WHERE collateral_id = :collateralId";
        if (codes != null && !codes.isEmpty()) {
            sql += " AND document_code IN (:codes)";
            params.addValue("codes", codes.stream().map(Enum::name).toList());
        }
        Integer count = jdbc.queryForObject(sql, params, Integer.class);
        return count == null ? 0 : count;
    }

    private Optional<CollateralRecord> one(String sql, MapSqlParameterSource params) {
        return jdbc.query("SELECT " + sql, params, (rs, rowNum) -> mapCollateral(rs)).stream().findFirst();
    }

    private Filter filter(
            UUID customerId,
            CollateralType type,
            VerificationStatus status,
            String query,
            Collection<UUID> branchIds,
            boolean unrestricted) {
        StringBuilder sql = new StringBuilder(" FROM collaterals c JOIN customers cu ON cu.id = c.customer_id WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (customerId != null) {
            sql.append(" AND c.customer_id = :customerId");
            params.addValue("customerId", customerId);
        }
        if (type != null) {
            sql.append(" AND c.collateral_type = :type");
            params.addValue("type", type.name());
        }
        if (status != null) {
            sql.append(" AND c.verification_status = :status");
            params.addValue("status", status.name());
        }
        if (query != null && !query.isBlank()) {
            sql.append("""
                     AND (lower(c.reference) LIKE :query ESCAPE '\\'
                       OR lower(c.description) LIKE :query ESCAPE '\\'
                       OR lower(coalesce(c.identification_number, '')) LIKE :query ESCAPE '\\')
                    """);
            params.addValue("query", like(query));
        }
        if (!unrestricted) {
            sql.append(" AND cu.branch_id IN (:branchIds)");
            params.addValue("branchIds", branchIds);
        }
        return new Filter(sql.toString(), params);
    }

    private static String like(String query) {
        String trimmed = query.trim().toLowerCase(Locale.ROOT);
        if (trimmed.length() > 80) {
            trimmed = trimmed.substring(0, 80);
        }
        return "%" + trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static MapSqlParameterSource collateralParams(CollateralRecord collateral) {
        return new MapSqlParameterSource()
                .addValue("id", collateral.id())
                .addValue("reference", collateral.reference())
                .addValue("customerId", collateral.customerId())
                .addValue("type", collateral.type().name())
                .addValue("description", collateral.description())
                .addValue("ownerName", collateral.ownerName())
                .addValue("ownerIdentifier", collateral.ownerIdentifier())
                .addValue("identificationNumber", collateral.identificationNumber())
                .addValue("location", collateral.location())
                .addValue("marketValue", collateral.marketValue())
                .addValue("forcedSaleValue", collateral.forcedSaleValue())
                .addValue("eligibleLendingValue", collateral.eligibleLendingValue())
                .addValue("valuationDate", collateral.valuationDate())
                .addValue("valuationExpiresOn", collateral.valuationExpiresOn())
                .addValue("valuerName", collateral.valuerName())
                .addValue("valuerReference", collateral.valuerReference())
                .addValue("lendingRatio", collateral.lendingRatio())
                .addValue("verificationStatus", collateral.verificationStatus().name())
                .addValue("ownershipStatus", collateral.ownershipStatus().name())
                .addValue("documentStatus", collateral.documentStatus().name())
                .addValue("legalStatus", collateral.legalStatus().name())
                .addValue("insuranceStatus", collateral.insuranceStatus().name())
                .addValue("encumbranceStatus", collateral.encumbranceStatus().name())
                .addValue("createdBy", collateral.createdBy())
                .addValue("updatedBy", collateral.updatedBy());
    }

    private static CollateralRecord mapCollateral(ResultSet rs) throws SQLException {
        return new CollateralRecord(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getObject("customer_id", UUID.class),
                CollateralType.valueOf(rs.getString("collateral_type")),
                rs.getString("description"),
                rs.getString("owner_name"),
                rs.getString("owner_identifier"),
                rs.getString("identification_number"),
                rs.getString("location"),
                rs.getBigDecimal("market_value"),
                rs.getBigDecimal("forced_sale_value"),
                rs.getBigDecimal("eligible_lending_value"),
                rs.getObject("valuation_date", LocalDate.class),
                rs.getObject("valuation_expires_on", LocalDate.class),
                rs.getString("valuer_name"),
                rs.getString("valuer_reference"),
                rs.getBigDecimal("lending_ratio"),
                VerificationStatus.valueOf(rs.getString("verification_status")),
                CheckStatus.valueOf(rs.getString("ownership_status")),
                CheckStatus.valueOf(rs.getString("document_status")),
                CheckStatus.valueOf(rs.getString("legal_status")),
                InsuranceCheckStatus.valueOf(rs.getString("insurance_status")),
                EncumbranceStatus.valueOf(rs.getString("encumbrance_status")),
                rs.getObject("approved_by", UUID.class),
                JdbcTimes.instant(rs, "approved_at"),
                rs.getObject("rejected_by", UUID.class),
                JdbcTimes.instant(rs, "rejected_at"),
                rs.getString("rejection_reason"),
                JdbcTimes.instant(rs, "released_at"),
                JdbcTimes.instant(rs, "created_at"),
                JdbcTimes.instant(rs, "updated_at"),
                rs.getString("created_by"),
                rs.getString("updated_by"));
    }

    private static String valuationSelect() {
        return """
                SELECT id, collateral_id, kind, status, market_value, forced_sale_value, lending_ratio,
                       calculated_eligible_value, accepted_eligible_value, valuation_date, expiry_date,
                       valuer_name, valuer_reference, submitted_by, decided_by, decided_at, decision_reason, created_at
                FROM collateral_valuations
                """;
    }

    private static ValuationRecord mapValuation(ResultSet rs) throws SQLException {
        return new ValuationRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("collateral_id", UUID.class),
                ValuationKind.valueOf(rs.getString("kind")),
                ValuationStatus.valueOf(rs.getString("status")),
                rs.getBigDecimal("market_value"),
                rs.getBigDecimal("forced_sale_value"),
                rs.getBigDecimal("lending_ratio"),
                rs.getBigDecimal("calculated_eligible_value"),
                rs.getBigDecimal("accepted_eligible_value"),
                rs.getObject("valuation_date", LocalDate.class),
                rs.getObject("expiry_date", LocalDate.class),
                rs.getString("valuer_name"),
                rs.getString("valuer_reference"),
                rs.getObject("submitted_by", UUID.class),
                rs.getObject("decided_by", UUID.class),
                JdbcTimes.instant(rs, "decided_at"),
                rs.getString("decision_reason"),
                JdbcTimes.instant(rs, "created_at"));
    }

    private static String allocationSelect() {
        return """
                SELECT id, application_id, collateral_id, allocated_amount, shared_security, status,
                       allocated_at, released_at, allocated_by
                FROM loan_collaterals
                """;
    }

    private static AllocationRecord mapAllocation(ResultSet rs) throws SQLException {
        return new AllocationRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("application_id", UUID.class),
                rs.getObject("collateral_id", UUID.class),
                rs.getBigDecimal("allocated_amount"),
                rs.getBoolean("shared_security"),
                PledgeStatus.valueOf(rs.getString("status")),
                JdbcTimes.instant(rs, "allocated_at"),
                JdbcTimes.instant(rs, "released_at"),
                rs.getObject("allocated_by", UUID.class));
    }

    private static EncumbranceRecord mapEncumbrance(ResultSet rs) throws SQLException {
        return new EncumbranceRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("collateral_id", UUID.class),
                rs.getObject("loan_collateral_id", UUID.class),
                EncumbranceSource.valueOf(rs.getString("source")),
                rs.getString("holder_name"),
                rs.getString("reference_number"),
                rs.getBigDecimal("amount"),
                PledgeStatus.valueOf(rs.getString("status")),
                JdbcTimes.instant(rs, "noted_at"),
                JdbcTimes.instant(rs, "released_at"),
                rs.getObject("created_by", UUID.class));
    }

    private static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private record Filter(String sql, MapSqlParameterSource params) {
    }
}
