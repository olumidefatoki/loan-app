package com.mfb.lending.module.collateral.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.collateral.domain.GuaranteeAcceptanceStatus;
import com.mfb.lending.module.collateral.domain.GuarantorDocumentCode;
import com.mfb.lending.module.collateral.domain.GuarantorDocumentRecord;
import com.mfb.lending.module.collateral.domain.GuarantorEmployment;
import com.mfb.lending.module.collateral.domain.GuarantorRecord;
import com.mfb.lending.module.collateral.domain.GuarantorVerificationStatus;
import com.mfb.lending.module.collateral.domain.LoanGuarantorRecord;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class GuarantorRepository {

    private static final String COLUMNS = """
            id, reference, full_name, phone_number, email, address_line, bvn_last4, nin_last4,
            bvn_verification_reference, nin_verification_reference, employment_status, employer_name, job_title,
            monthly_income, monthly_obligations, verification_status, acceptance_status, approved_security,
            consent_recorded, verified_by, verified_at, accepted_by, accepted_at, rejection_reason,
            created_at, updated_at, created_by, updated_by
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public GuarantorRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextReference() {
        Long value = jdbc.queryForObject(
                "SELECT nextval('guarantor_reference_seq')",
                new MapSqlParameterSource(),
                Long.class);
        if (value == null || value < 1 || value > 999_999_999_999L) {
            throw new IllegalStateException("Guarantor reference sequence is exhausted");
        }
        return "GR" + String.format("%012d", value);
    }

    public boolean identityExists(String bvnHash, String ninHash) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM guarantors WHERE bvn_hash = :bvnHash OR nin_hash = :ninHash",
                new MapSqlParameterSource().addValue("bvnHash", bvnHash).addValue("ninHash", ninHash),
                Integer.class);
        return count != null && count > 0;
    }

    public void insert(GuarantorRecord guarantor, String bvnCiphertext, String bvnHash, String ninCiphertext, String ninHash) {
        jdbc.update(
                """
                INSERT INTO guarantors (
                    id, reference, full_name, phone_number, email, address_line,
                    bvn_encrypted, bvn_hash, bvn_last4, nin_encrypted, nin_hash, nin_last4,
                    bvn_verification_reference, nin_verification_reference, employment_status, employer_name, job_title,
                    monthly_income, monthly_obligations, verification_status, acceptance_status, approved_security,
                    consent_recorded, created_by, updated_by)
                VALUES (
                    :id, :reference, :fullName, :phoneNumber, :email, :addressLine,
                    :bvnEncrypted, :bvnHash, :bvnLast4, :ninEncrypted, :ninHash, :ninLast4,
                    :bvnVerificationReference, :ninVerificationReference, :employmentStatus, :employerName, :jobTitle,
                    :monthlyIncome, :monthlyObligations, :verificationStatus, :acceptanceStatus, :approvedSecurity,
                    :consentRecorded, :createdBy, :updatedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", guarantor.id())
                        .addValue("reference", guarantor.reference())
                        .addValue("fullName", guarantor.fullName())
                        .addValue("phoneNumber", guarantor.phoneNumber())
                        .addValue("email", guarantor.email())
                        .addValue("addressLine", guarantor.addressLine())
                        .addValue("bvnEncrypted", bvnCiphertext)
                        .addValue("bvnHash", bvnHash)
                        .addValue("bvnLast4", guarantor.bvnLast4())
                        .addValue("ninEncrypted", ninCiphertext)
                        .addValue("ninHash", ninHash)
                        .addValue("ninLast4", guarantor.ninLast4())
                        .addValue("bvnVerificationReference", guarantor.bvnVerificationReference())
                        .addValue("ninVerificationReference", guarantor.ninVerificationReference())
                        .addValue("employmentStatus", guarantor.employmentStatus().name())
                        .addValue("employerName", guarantor.employerName())
                        .addValue("jobTitle", guarantor.jobTitle())
                        .addValue("monthlyIncome", guarantor.monthlyIncome())
                        .addValue("monthlyObligations", guarantor.monthlyObligations())
                        .addValue("verificationStatus", guarantor.verificationStatus().name())
                        .addValue("acceptanceStatus", guarantor.acceptanceStatus().name())
                        .addValue("approvedSecurity", guarantor.approvedSecurity())
                        .addValue("consentRecorded", guarantor.consentRecorded())
                        .addValue("createdBy", guarantor.createdBy())
                        .addValue("updatedBy", guarantor.updatedBy()));
    }

    public void insertConsent(UUID id, UUID guarantorId, String statement, UUID recordedBy) {
        jdbc.update(
                """
                INSERT INTO guarantor_consents (id, guarantor_id, consent_statement, consented_at, recorded_by)
                VALUES (:id, :guarantorId, :statement, NOW(), :recordedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("guarantorId", guarantorId)
                        .addValue("statement", statement)
                        .addValue("recordedBy", recordedBy));
    }

    public Optional<GuarantorRecord> find(UUID id) {
        return one("SELECT " + COLUMNS + " FROM guarantors WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public Optional<GuarantorRecord> lock(UUID id) {
        return one("SELECT " + COLUMNS + " FROM guarantors WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", id));
    }

    public void markVerified(UUID id, UUID actorId, String updatedBy) {
        jdbc.update(
                """
                UPDATE guarantors
                SET verification_status = 'VERIFIED',
                    verified_by = :actorId,
                    verified_at = NOW(),
                    rejection_reason = NULL,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                actorParams(id, actorId, updatedBy).addValue("reason", null));
    }

    public void markVerificationRejected(UUID id, UUID actorId, String reason, String updatedBy) {
        jdbc.update(
                """
                UPDATE guarantors
                SET verification_status = 'REJECTED',
                    verified_by = NULL,
                    verified_at = NULL,
                    approved_security = FALSE,
                    rejection_reason = :reason,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                actorParams(id, actorId, updatedBy).addValue("reason", reason));
    }

    public void markAccepted(UUID id, UUID actorId, String updatedBy) {
        jdbc.update(
                """
                UPDATE guarantors
                SET acceptance_status = 'ACCEPTED',
                    accepted_by = :actorId,
                    accepted_at = NOW(),
                    approved_security = TRUE,
                    rejection_reason = NULL,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                actorParams(id, actorId, updatedBy));
    }

    public void markAcceptanceRejected(UUID id, UUID actorId, String reason, String updatedBy) {
        jdbc.update(
                """
                UPDATE guarantors
                SET acceptance_status = 'REJECTED',
                    accepted_by = NULL,
                    accepted_at = NULL,
                    approved_security = FALSE,
                    rejection_reason = :reason,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                actorParams(id, actorId, updatedBy).addValue("reason", reason));
    }

    public void syncLinks(UUID guarantorId, boolean approvedSecurity) {
        jdbc.update(
                """
                UPDATE loan_guarantors
                SET approved_security = :approvedSecurity
                WHERE guarantor_id = :guarantorId
                """,
                new MapSqlParameterSource()
                        .addValue("guarantorId", guarantorId)
                        .addValue("approvedSecurity", approvedSecurity));
    }

    public void insertDocument(GuarantorDocumentRecord document, String uploadedBy) {
        jdbc.update(
                """
                INSERT INTO guarantor_documents (
                    id, guarantor_id, document_code, file_name, content_type, size_bytes, checksum_sha256, storage_key, uploaded_by)
                VALUES (
                    :id, :guarantorId, :documentCode, :fileName, :contentType, :sizeBytes, :checksum, :storageKey, :uploadedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", document.id())
                        .addValue("guarantorId", document.guarantorId())
                        .addValue("documentCode", document.code().name())
                        .addValue("fileName", document.fileName())
                        .addValue("contentType", document.contentType())
                        .addValue("sizeBytes", document.sizeBytes())
                        .addValue("checksum", document.checksumSha256())
                        .addValue("storageKey", document.storageKey())
                        .addValue("uploadedBy", uploadedBy));
    }

    public List<GuarantorDocumentRecord> listDocuments(UUID guarantorId) {
        return jdbc.query(
                documentSelect() + " WHERE guarantor_id = :guarantorId ORDER BY created_at",
                new MapSqlParameterSource("guarantorId", guarantorId),
                (rs, rowNum) -> mapDocument(rs));
    }

    public Optional<GuarantorDocumentRecord> findDocument(UUID guarantorId, UUID documentId) {
        return jdbc.query(
                documentSelect() + " WHERE guarantor_id = :guarantorId AND id = :id",
                new MapSqlParameterSource().addValue("guarantorId", guarantorId).addValue("id", documentId),
                (rs, rowNum) -> mapDocument(rs)).stream().findFirst();
    }

    public boolean hasAgreement(UUID guarantorId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM guarantor_documents
                WHERE guarantor_id = :id AND document_code = 'GUARANTEE_AGREEMENT'
                """,
                new MapSqlParameterSource("id", guarantorId),
                Integer.class);
        return count != null && count > 0;
    }

    public void insertLink(LoanGuarantorRecord link) {
        jdbc.update(
                """
                INSERT INTO loan_guarantors (
                    id, application_id, guarantor_id, relationship, approved_security, linked_by)
                VALUES (
                    :id, :applicationId, :guarantorId, :relationship, :approvedSecurity, :linkedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", link.id())
                        .addValue("applicationId", link.applicationId())
                        .addValue("guarantorId", link.guarantorId())
                        .addValue("relationship", link.relationship())
                        .addValue("approvedSecurity", link.approvedSecurity())
                        .addValue("linkedBy", link.linkedBy()));
    }

    public List<LoanGuarantorRecord> listLinks(UUID applicationId) {
        return jdbc.query(
                """
                SELECT id, application_id, guarantor_id, relationship, approved_security, linked_at, linked_by
                FROM loan_guarantors
                WHERE application_id = :applicationId
                ORDER BY linked_at
                """,
                new MapSqlParameterSource("applicationId", applicationId),
                (rs, rowNum) -> mapLink(rs));
    }

    private Optional<GuarantorRecord> one(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, (rs, rowNum) -> mapGuarantor(rs)).stream().findFirst();
    }

    private static MapSqlParameterSource actorParams(UUID id, UUID actorId, String updatedBy) {
        return new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("actorId", actorId)
                .addValue("updatedBy", updatedBy);
    }

    private static String documentSelect() {
        return """
                SELECT id, guarantor_id, document_code, file_name, content_type, size_bytes, checksum_sha256, storage_key, created_at
                FROM guarantor_documents
                """;
    }

    private static GuarantorRecord mapGuarantor(ResultSet rs) throws SQLException {
        return new GuarantorRecord(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getString("full_name"),
                rs.getString("phone_number"),
                rs.getString("email"),
                rs.getString("address_line"),
                rs.getString("bvn_last4"),
                rs.getString("nin_last4"),
                rs.getString("bvn_verification_reference"),
                rs.getString("nin_verification_reference"),
                GuarantorEmployment.valueOf(rs.getString("employment_status")),
                rs.getString("employer_name"),
                rs.getString("job_title"),
                rs.getBigDecimal("monthly_income"),
                rs.getBigDecimal("monthly_obligations"),
                GuarantorVerificationStatus.valueOf(rs.getString("verification_status")),
                GuaranteeAcceptanceStatus.valueOf(rs.getString("acceptance_status")),
                rs.getBoolean("approved_security"),
                rs.getBoolean("consent_recorded"),
                rs.getObject("verified_by", UUID.class),
                JdbcTimes.instant(rs, "verified_at"),
                rs.getObject("accepted_by", UUID.class),
                JdbcTimes.instant(rs, "accepted_at"),
                rs.getString("rejection_reason"),
                JdbcTimes.instant(rs, "created_at"),
                JdbcTimes.instant(rs, "updated_at"),
                rs.getString("created_by"),
                rs.getString("updated_by"));
    }

    private static GuarantorDocumentRecord mapDocument(ResultSet rs) throws SQLException {
        return new GuarantorDocumentRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("guarantor_id", UUID.class),
                GuarantorDocumentCode.valueOf(rs.getString("document_code")),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("checksum_sha256"),
                rs.getString("storage_key"),
                JdbcTimes.instant(rs, "created_at"));
    }

    private static LoanGuarantorRecord mapLink(ResultSet rs) throws SQLException {
        return new LoanGuarantorRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("application_id", UUID.class),
                rs.getObject("guarantor_id", UUID.class),
                rs.getString("relationship"),
                rs.getBoolean("approved_security"),
                JdbcTimes.instant(rs, "linked_at"),
                rs.getObject("linked_by", UUID.class));
    }
}
