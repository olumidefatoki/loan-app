package com.mfb.lending.module.loanoffer.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.loanoffer.domain.AgreementAcceptance;
import com.mfb.lending.module.loanoffer.domain.AgreementKind;
import com.mfb.lending.module.loanoffer.domain.AgreementTemplate;
import com.mfb.lending.module.loanoffer.domain.LoanOffer;
import com.mfb.lending.module.loanoffer.domain.OfferStatus;
import com.mfb.lending.module.loanoffer.domain.OfferTerms;
import com.mfb.lending.module.loanoffer.domain.OfferVersion;
import com.mfb.lending.module.loanoffer.domain.ScheduleItem;
import com.mfb.lending.module.loanoffer.domain.StoredAgreement;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LoanOfferRepository {

    private static final String OFFER_COLUMNS = """
            id, reference, application_id, customer_id, status, current_version_id, expires_at,
            accepted_at, declined_at, cancelled_at, decision_reason, version, created_at, updated_at,
            created_by, updated_by
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public LoanOfferRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextReference() {
        Long value = jdbc.queryForObject("SELECT nextval('loan_offer_reference_seq')", new MapSqlParameterSource(), Long.class);
        if (value == null || value < 1 || value > 999_999_999_999L) {
            throw new IllegalStateException("Loan offer reference sequence is exhausted");
        }
        return "OF" + String.format("%012d", value);
    }

    public Optional<BigDecimalApproval> approvedAmount(UUID applicationId, int revision) {
        List<BigDecimalApproval> rows = jdbc.query(
                """
                SELECT amount
                FROM loan_approvals
                WHERE application_id = :applicationId
                  AND application_revision = :revision
                  AND status = 'APPROVED'
                ORDER BY decided_at DESC
                LIMIT 1
                """,
                new MapSqlParameterSource().addValue("applicationId", applicationId).addValue("revision", revision),
                (rs, rowNum) -> new BigDecimalApproval(rs.getBigDecimal("amount")));
        return rows.stream().findFirst();
    }

    public void insertOffer(LoanOffer offer) {
        jdbc.update(
                """
                INSERT INTO loan_offers (
                    id, reference, application_id, customer_id, status, current_version_id, expires_at,
                    accepted_at, declined_at, cancelled_at, decision_reason, version, created_by, updated_by)
                VALUES (
                    :id, :reference, :applicationId, :customerId, :status, :currentVersionId, :expiresAt,
                    :acceptedAt, :declinedAt, :cancelledAt, :decisionReason, :version, :createdBy, :updatedBy)
                """,
                offerParams(offer));
    }

    public boolean save(LoanOffer offer, int expectedVersion) {
        int updated = jdbc.update(
                """
                UPDATE loan_offers
                SET status = :status,
                    current_version_id = :currentVersionId,
                    expires_at = :expiresAt,
                    accepted_at = :acceptedAt,
                    declined_at = :declinedAt,
                    cancelled_at = :cancelledAt,
                    decision_reason = :decisionReason,
                    version = :version,
                    updated_by = :updatedBy
                WHERE id = :id AND version = :expectedVersion
                """,
                offerParams(offer).addValue("expectedVersion", expectedVersion));
        return updated == 1;
    }

    public Optional<LoanOffer> lock(UUID id) {
        return one("SELECT " + OFFER_COLUMNS + " FROM loan_offers WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), OFFER);
    }

    public Optional<LoanOffer> lockByApplication(UUID applicationId) {
        return one("SELECT " + OFFER_COLUMNS + " FROM loan_offers WHERE application_id = :id FOR UPDATE",
                new MapSqlParameterSource("id", applicationId), OFFER);
    }

    public Optional<LoanOffer> find(UUID id) {
        return one("SELECT " + OFFER_COLUMNS + " FROM loan_offers WHERE id = :id",
                new MapSqlParameterSource("id", id), OFFER);
    }

    public List<LoanOffer> listForCustomer(UUID customerId, int limit, int offset) {
        return jdbc.query(
                "SELECT " + OFFER_COLUMNS + """
                 FROM loan_offers
                 WHERE customer_id = :customerId
                 ORDER BY created_at DESC
                 LIMIT :limit OFFSET :offset
                """,
                new MapSqlParameterSource()
                        .addValue("customerId", customerId)
                        .addValue("limit", limit)
                        .addValue("offset", offset),
                OFFER);
    }

    public long countForCustomer(UUID customerId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM loan_offers WHERE customer_id = :customerId",
                new MapSqlParameterSource("customerId", customerId),
                Long.class);
        return count == null ? 0 : count;
    }

    public void insertVersion(OfferVersion version) {
        jdbc.update(
                """
                INSERT INTO loan_offer_versions (
                    id, offer_id, version_number, fingerprint, engine_version, superseded_at, created_by)
                VALUES (
                    :id, :offerId, :versionNumber, :fingerprint, :engineVersion, :supersededAt, :createdBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", version.id())
                        .addValue("offerId", version.offerId())
                        .addValue("versionNumber", version.versionNumber())
                        .addValue("fingerprint", version.fingerprint())
                        .addValue("engineVersion", version.engineVersion())
                        .addValue("supersededAt", offset(version.supersededAt()))
                        .addValue("createdBy", version.createdBy()));
    }

    public void supersedeVersion(UUID versionId, Instant supersededAt) {
        jdbc.update(
                "UPDATE loan_offer_versions SET superseded_at = :supersededAt WHERE id = :id AND superseded_at IS NULL",
                new MapSqlParameterSource().addValue("id", versionId).addValue("supersededAt", offset(supersededAt)));
    }

    public Optional<OfferVersion> findVersion(UUID id) {
        return one(
                """
                SELECT id, offer_id, version_number, fingerprint, engine_version, superseded_at, created_at, created_by
                FROM loan_offer_versions WHERE id = :id
                """,
                new MapSqlParameterSource("id", id), VERSION);
    }

    public Optional<OfferVersion> findVersion(UUID offerId, int versionNumber) {
        return one(
                """
                SELECT id, offer_id, version_number, fingerprint, engine_version, superseded_at, created_at, created_by
                FROM loan_offer_versions
                WHERE offer_id = :offerId AND version_number = :versionNumber
                """,
                new MapSqlParameterSource().addValue("offerId", offerId).addValue("versionNumber", versionNumber),
                VERSION);
    }

    public void insertTerms(OfferTerms terms) {
        jdbc.update(
                """
                INSERT INTO loan_offer_terms (
                    id, offer_version_id, customer_id, customer_name, customer_reference, application_reference,
                    product_id, product_code, product_name, product_version_id, principal, currency_code,
                    interest_rate, interest_method, interest_period, tenure, tenure_unit, repayment_frequency,
                    day_count_convention, rounding_precision, rounding_method, grace_period_days, instalment_amount,
                    processing_fees, other_fees, taxes, tax_rate, total_interest, total_repayment, net_disbursement,
                    disbursement_date, engine_version, fee_breakdown, disbursement_conditions, collateral_details,
                    guarantor_details, default_provisions)
                VALUES (
                    :id, :offerVersionId, :customerId, :customerName, :customerReference, :applicationReference,
                    :productId, :productCode, :productName, :productVersionId, :principal, :currencyCode,
                    :interestRate, :interestMethod, :interestPeriod, :tenure, :tenureUnit, :repaymentFrequency,
                    :dayCountConvention, :roundingPrecision, :roundingMethod, :gracePeriodDays, :instalmentAmount,
                    :processingFees, :otherFees, :taxes, :taxRate, :totalInterest, :totalRepayment, :netDisbursement,
                    :disbursementDate, :engineVersion, CAST(:feeBreakdown AS jsonb), :disbursementConditions,
                    :collateralDetails, :guarantorDetails, :defaultProvisions)
                """,
                termsParams(terms));
    }

    public Optional<OfferTerms> findTerms(UUID offerVersionId) {
        return one("SELECT * FROM loan_offer_terms WHERE offer_version_id = :id",
                new MapSqlParameterSource("id", offerVersionId), TERMS);
    }

    public void insertSchedule(List<ScheduleItem> items) {
        if (items.isEmpty()) {
            return;
        }
        MapSqlParameterSource[] batch = items.stream().map(item -> new MapSqlParameterSource()
                .addValue("id", item.id())
                .addValue("offerVersionId", item.offerVersionId())
                .addValue("instalmentNumber", item.instalmentNumber())
                .addValue("dueDate", item.dueDate())
                .addValue("openingBalance", item.openingBalance())
                .addValue("principalDue", item.principalDue())
                .addValue("interestDue", item.interestDue())
                .addValue("instalmentAmount", item.instalmentAmount())
                .addValue("closingBalance", item.closingBalance()))
                .toArray(MapSqlParameterSource[]::new);
        jdbc.batchUpdate(
                """
                INSERT INTO loan_offer_schedule_items (
                    id, offer_version_id, instalment_number, due_date, opening_balance, principal_due,
                    interest_due, instalment_amount, closing_balance)
                VALUES (
                    :id, :offerVersionId, :instalmentNumber, :dueDate, :openingBalance, :principalDue,
                    :interestDue, :instalmentAmount, :closingBalance)
                """,
                batch);
    }

    public List<ScheduleItem> listSchedule(UUID offerVersionId) {
        return jdbc.query(
                """
                SELECT id, offer_version_id, instalment_number, due_date, opening_balance, principal_due,
                       interest_due, instalment_amount, closing_balance
                FROM loan_offer_schedule_items
                WHERE offer_version_id = :id
                ORDER BY instalment_number
                """,
                new MapSqlParameterSource("id", offerVersionId),
                (rs, rowNum) -> new ScheduleItem(
                        rs.getObject("id", UUID.class),
                        rs.getObject("offer_version_id", UUID.class),
                        rs.getInt("instalment_number"),
                        rs.getObject("due_date", LocalDate.class),
                        rs.getBigDecimal("opening_balance"),
                        rs.getBigDecimal("principal_due"),
                        rs.getBigDecimal("interest_due"),
                        rs.getBigDecimal("instalment_amount"),
                        rs.getBigDecimal("closing_balance")));
    }

    public void insertHistory(UUID id, UUID offerId, OfferStatus from, OfferStatus to, int versionNumber, String reason, UUID actorId, Instant occurredAt) {
        jdbc.update(
                """
                INSERT INTO loan_offer_history (
                    id, offer_id, from_status, to_status, version_number, reason, actor_id, occurred_at)
                VALUES (
                    :id, :offerId, :fromStatus, :toStatus, :versionNumber, :reason, :actorId, :occurredAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("offerId", offerId)
                        .addValue("fromStatus", from == null ? null : from.name())
                        .addValue("toStatus", to.name())
                        .addValue("versionNumber", versionNumber)
                        .addValue("reason", reason)
                        .addValue("actorId", actorId)
                        .addValue("occurredAt", offset(occurredAt)));
    }

    public void insertAgreement(StoredAgreement agreement, UUID productId, UUID productVersionId, String termsSnapshot) {
        jdbc.update(
                """
                INSERT INTO loan_agreements (
                    id, product_id, product_version_id, terms_snapshot, booked_at,
                    offer_version_id, agreement_kind, template_id, template_version, document_version,
                    title, file_name, content_type, storage_key, checksum_sha256, size_bytes, rendered_text, created_by)
                VALUES (
                    :id, :productId, :productVersionId, CAST(:termsSnapshot AS jsonb), :bookedAt,
                    :offerVersionId, :kind, :templateId, :templateVersion, :documentVersion,
                    :title, :fileName, :contentType, :storageKey, :checksum, :sizeBytes, :renderedText, :createdBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", agreement.id())
                        .addValue("productId", productId)
                        .addValue("productVersionId", productVersionId)
                        .addValue("termsSnapshot", termsSnapshot)
                        .addValue("bookedAt", offset(agreement.frozenAt()))
                        .addValue("offerVersionId", agreement.offerVersionId())
                        .addValue("kind", agreement.kind().name())
                        .addValue("templateId", agreement.templateId())
                        .addValue("templateVersion", agreement.templateVersion())
                        .addValue("documentVersion", agreement.documentVersion())
                        .addValue("title", agreement.title())
                        .addValue("fileName", agreement.fileName())
                        .addValue("contentType", agreement.contentType())
                        .addValue("storageKey", agreement.storageKey())
                        .addValue("checksum", agreement.checksumSha256())
                        .addValue("sizeBytes", agreement.sizeBytes())
                        .addValue("renderedText", agreement.renderedText())
                        .addValue("createdBy", agreement.createdBy()));
    }

    public void supersedeAgreements(UUID offerId, UUID keepVersionId, Instant supersededAt) {
        jdbc.update(
                """
                UPDATE loan_agreements agreement
                SET superseded_at = :supersededAt
                FROM loan_offer_versions version
                WHERE agreement.offer_version_id = version.id
                  AND version.offer_id = :offerId
                  AND agreement.offer_version_id <> :keepVersionId
                  AND agreement.superseded_at IS NULL
                """,
                new MapSqlParameterSource()
                        .addValue("offerId", offerId)
                        .addValue("keepVersionId", keepVersionId)
                        .addValue("supersededAt", offset(supersededAt)));
    }

    public Optional<StoredAgreement> findAgreement(UUID offerVersionId, AgreementKind kind) {
        return one(agreementSelect() + " WHERE offer_version_id = :id AND agreement_kind = :kind",
                new MapSqlParameterSource().addValue("id", offerVersionId).addValue("kind", kind.name()),
                AGREEMENT);
    }

    public List<StoredAgreement> listAgreements(UUID offerVersionId) {
        return jdbc.query(agreementSelect() + " WHERE offer_version_id = :id ORDER BY agreement_kind",
                new MapSqlParameterSource("id", offerVersionId), AGREEMENT);
    }

    public void insertAcceptance(AgreementAcceptance acceptance) {
        jdbc.update(
                """
                INSERT INTO loan_agreement_acceptances (
                    id, agreement_id, offer_id, customer_id, accepted_at, identity_verification_reference,
                    kyc_status, signature_provider, signature_assurance, signature_reference, disclaimer,
                    channel, ip_address, user_agent, actor_id)
                VALUES (
                    :id, :agreementId, :offerId, :customerId, :acceptedAt, :identityVerificationReference,
                    :kycStatus, :signatureProvider, :signatureAssurance, :signatureReference, :disclaimer,
                    :channel, :ipAddress, :userAgent, :actorId)
                """,
                new MapSqlParameterSource()
                        .addValue("id", acceptance.id())
                        .addValue("agreementId", acceptance.agreementId())
                        .addValue("offerId", acceptance.offerId())
                        .addValue("customerId", acceptance.customerId())
                        .addValue("acceptedAt", offset(acceptance.acceptedAt()))
                        .addValue("identityVerificationReference", acceptance.identityVerificationReference())
                        .addValue("kycStatus", acceptance.kycStatus())
                        .addValue("signatureProvider", acceptance.signatureProvider())
                        .addValue("signatureAssurance", acceptance.signatureAssurance())
                        .addValue("signatureReference", acceptance.signatureReference())
                        .addValue("disclaimer", acceptance.disclaimer())
                        .addValue("channel", acceptance.channel())
                        .addValue("ipAddress", acceptance.ipAddress())
                        .addValue("userAgent", acceptance.userAgent())
                        .addValue("actorId", acceptance.actorId()));
    }

    public Optional<AgreementAcceptance> findAcceptance(UUID agreementId) {
        return one(
                """
                SELECT id, agreement_id, offer_id, customer_id, accepted_at, identity_verification_reference,
                       kyc_status, signature_provider, signature_assurance, signature_reference, disclaimer,
                       channel, ip_address, user_agent, actor_id
                FROM loan_agreement_acceptances
                WHERE agreement_id = :id
                """,
                new MapSqlParameterSource("id", agreementId),
                (rs, rowNum) -> new AgreementAcceptance(
                        rs.getObject("id", UUID.class),
                        rs.getObject("agreement_id", UUID.class),
                        rs.getObject("offer_id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        instant(rs, "accepted_at"),
                        rs.getString("identity_verification_reference"),
                        rs.getString("kyc_status"),
                        rs.getString("signature_provider"),
                        rs.getString("signature_assurance"),
                        rs.getString("signature_reference"),
                        rs.getString("disclaimer"),
                        rs.getString("channel"),
                        rs.getString("ip_address"),
                        rs.getString("user_agent"),
                        rs.getObject("actor_id", UUID.class)));
    }

    public void insertTemplate(AgreementTemplate template) {
        jdbc.update(
                """
                INSERT INTO loan_agreement_templates (
                    id, code, name, kind, version_number, body, active, checksum_sha256, created_by)
                VALUES (
                    :id, :code, :name, :kind, :versionNumber, :body, :active, :checksum, :createdBy)
                """,
                templateParams(template));
    }

    public void deactivateCode(String code, UUID exceptId) {
        jdbc.update(
                """
                UPDATE loan_agreement_templates
                SET active = FALSE
                WHERE code = :code AND id <> :exceptId AND active = TRUE
                """,
                new MapSqlParameterSource().addValue("code", code).addValue("exceptId", exceptId));
    }

    public void deactivateKind(String kind, UUID exceptId) {
        jdbc.update(
                """
                UPDATE loan_agreement_templates
                SET active = FALSE
                WHERE kind = :kind AND id <> :exceptId AND active = TRUE
                """,
                new MapSqlParameterSource().addValue("kind", kind).addValue("exceptId", exceptId));
    }

    public boolean deleteTemplate(UUID id) {
        return jdbc.update("DELETE FROM loan_agreement_templates WHERE id = :id", new MapSqlParameterSource("id", id)) == 1;
    }

    public int nextTemplateVersion(String code) {
        Integer value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version_number), 0) FROM loan_agreement_templates WHERE code = :code",
                new MapSqlParameterSource("code", code),
                Integer.class);
        return value == null ? 1 : value + 1;
    }

    public Optional<AgreementTemplate> findTemplate(UUID id) {
        return one("SELECT * FROM loan_agreement_templates WHERE id = :id", new MapSqlParameterSource("id", id), TEMPLATE);
    }

    public Optional<AgreementTemplate> findActive(AgreementKind kind) {
        return one(
                """
                SELECT * FROM loan_agreement_templates
                WHERE kind = :kind AND active = TRUE
                ORDER BY created_at DESC
                LIMIT 1
                """,
                new MapSqlParameterSource("kind", kind.name()),
                TEMPLATE);
    }

    public List<AgreementTemplate> listTemplates() {
        return jdbc.query("SELECT * FROM loan_agreement_templates ORDER BY code, version_number", TEMPLATE);
    }

    public boolean templateInUse(UUID id) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM loan_agreements WHERE template_id = :id",
                new MapSqlParameterSource("id", id),
                Long.class);
        return count != null && count > 0;
    }

    private MapSqlParameterSource offerParams(LoanOffer offer) {
        return new MapSqlParameterSource()
                .addValue("id", offer.id())
                .addValue("reference", offer.reference())
                .addValue("applicationId", offer.applicationId())
                .addValue("customerId", offer.customerId())
                .addValue("status", offer.status().name())
                .addValue("currentVersionId", offer.currentVersionId())
                .addValue("expiresAt", offset(offer.expiresAt()))
                .addValue("acceptedAt", offset(offer.acceptedAt()))
                .addValue("declinedAt", offset(offer.declinedAt()))
                .addValue("cancelledAt", offset(offer.cancelledAt()))
                .addValue("decisionReason", offer.decisionReason())
                .addValue("version", offer.version())
                .addValue("createdBy", offer.createdBy())
                .addValue("updatedBy", offer.updatedBy());
    }

    private MapSqlParameterSource termsParams(OfferTerms terms) {
        return new MapSqlParameterSource()
                .addValue("id", terms.id())
                .addValue("offerVersionId", terms.offerVersionId())
                .addValue("customerId", terms.customerId())
                .addValue("customerName", terms.customerName())
                .addValue("customerReference", terms.customerReference())
                .addValue("applicationReference", terms.applicationReference())
                .addValue("productId", terms.productId())
                .addValue("productCode", terms.productCode())
                .addValue("productName", terms.productName())
                .addValue("productVersionId", terms.productVersionId())
                .addValue("principal", terms.principal())
                .addValue("currencyCode", terms.currencyCode())
                .addValue("interestRate", terms.interestRate())
                .addValue("interestMethod", terms.interestMethod())
                .addValue("interestPeriod", terms.interestPeriod())
                .addValue("tenure", terms.tenure())
                .addValue("tenureUnit", terms.tenureUnit())
                .addValue("repaymentFrequency", terms.repaymentFrequency())
                .addValue("dayCountConvention", terms.dayCountConvention())
                .addValue("roundingPrecision", terms.roundingPrecision())
                .addValue("roundingMethod", terms.roundingMethod())
                .addValue("gracePeriodDays", terms.gracePeriodDays())
                .addValue("instalmentAmount", terms.instalmentAmount())
                .addValue("processingFees", terms.processingFees())
                .addValue("otherFees", terms.otherFees())
                .addValue("taxes", terms.taxes())
                .addValue("taxRate", terms.taxRate())
                .addValue("totalInterest", terms.totalInterest())
                .addValue("totalRepayment", terms.totalRepayment())
                .addValue("netDisbursement", terms.netDisbursement())
                .addValue("disbursementDate", terms.disbursementDate())
                .addValue("engineVersion", terms.engineVersion())
                .addValue("feeBreakdown", terms.feeBreakdown())
                .addValue("disbursementConditions", terms.disbursementConditions())
                .addValue("collateralDetails", terms.collateralDetails())
                .addValue("guarantorDetails", terms.guarantorDetails())
                .addValue("defaultProvisions", terms.defaultProvisions());
    }

    private static MapSqlParameterSource templateParams(AgreementTemplate template) {
        return new MapSqlParameterSource()
                .addValue("id", template.id())
                .addValue("code", template.code())
                .addValue("name", template.name())
                .addValue("kind", template.kind().name())
                .addValue("versionNumber", template.versionNumber())
                .addValue("body", template.body())
                .addValue("active", template.active())
                .addValue("checksum", template.checksumSha256())
                .addValue("createdBy", template.createdBy());
    }

    private String agreementSelect() {
        return """
                SELECT id, offer_version_id, agreement_kind, template_id, template_version, document_version,
                       title, file_name, content_type, storage_key, checksum_sha256, size_bytes, rendered_text,
                       superseded_at, booked_at, created_by
                FROM loan_agreements
                """;
    }

    private <T> Optional<T> one(String sql, MapSqlParameterSource params, RowMapper<T> mapper) {
        return jdbc.query(sql, params, mapper).stream().findFirst();
    }

    private static final RowMapper<LoanOffer> OFFER = (rs, rowNum) -> new LoanOffer(
            rs.getObject("id", UUID.class),
            rs.getString("reference"),
            rs.getObject("application_id", UUID.class),
            rs.getObject("customer_id", UUID.class),
            OfferStatus.valueOf(rs.getString("status")),
            rs.getObject("current_version_id", UUID.class),
            instant(rs, "expires_at"),
            instant(rs, "accepted_at"),
            instant(rs, "declined_at"),
            instant(rs, "cancelled_at"),
            rs.getString("decision_reason"),
            rs.getInt("version"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            rs.getString("created_by"),
            rs.getString("updated_by"));

    private static final RowMapper<OfferVersion> VERSION = (rs, rowNum) -> new OfferVersion(
            rs.getObject("id", UUID.class),
            rs.getObject("offer_id", UUID.class),
            rs.getInt("version_number"),
            rs.getString("fingerprint"),
            rs.getString("engine_version"),
            instant(rs, "superseded_at"),
            instant(rs, "created_at"),
            rs.getString("created_by"));

    private static final RowMapper<OfferTerms> TERMS = (rs, rowNum) -> new OfferTerms(
            rs.getObject("id", UUID.class),
            rs.getObject("offer_version_id", UUID.class),
            rs.getObject("customer_id", UUID.class),
            rs.getString("customer_name"),
            rs.getString("customer_reference"),
            rs.getString("application_reference"),
            rs.getObject("product_id", UUID.class),
            rs.getString("product_code"),
            rs.getString("product_name"),
            rs.getObject("product_version_id", UUID.class),
            rs.getBigDecimal("principal"),
            rs.getString("currency_code"),
            rs.getBigDecimal("interest_rate"),
            rs.getString("interest_method"),
            rs.getString("interest_period"),
            rs.getInt("tenure"),
            rs.getString("tenure_unit"),
            rs.getString("repayment_frequency"),
            rs.getString("day_count_convention"),
            rs.getInt("rounding_precision"),
            rs.getString("rounding_method"),
            rs.getInt("grace_period_days"),
            rs.getBigDecimal("instalment_amount"),
            rs.getBigDecimal("processing_fees"),
            rs.getBigDecimal("other_fees"),
            rs.getBigDecimal("taxes"),
            rs.getBigDecimal("tax_rate"),
            rs.getBigDecimal("total_interest"),
            rs.getBigDecimal("total_repayment"),
            rs.getBigDecimal("net_disbursement"),
            rs.getObject("disbursement_date", LocalDate.class),
            rs.getString("engine_version"),
            rs.getString("fee_breakdown"),
            rs.getString("disbursement_conditions"),
            rs.getString("collateral_details"),
            rs.getString("guarantor_details"),
            rs.getString("default_provisions"));

    private static final RowMapper<StoredAgreement> AGREEMENT = (rs, rowNum) -> new StoredAgreement(
            rs.getObject("id", UUID.class),
            rs.getObject("offer_version_id", UUID.class),
            AgreementKind.valueOf(rs.getString("agreement_kind")),
            rs.getObject("template_id", UUID.class),
            rs.getInt("template_version"),
            rs.getInt("document_version"),
            rs.getString("title"),
            rs.getString("file_name"),
            rs.getString("content_type"),
            rs.getString("storage_key"),
            rs.getString("checksum_sha256"),
            rs.getLong("size_bytes"),
            rs.getString("rendered_text"),
            instant(rs, "superseded_at"),
            instant(rs, "booked_at"),
            rs.getString("created_by"));

    private static final RowMapper<AgreementTemplate> TEMPLATE = (rs, rowNum) -> new AgreementTemplate(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            AgreementKind.valueOf(rs.getString("kind")),
            rs.getInt("version_number"),
            rs.getString("body"),
            rs.getBoolean("active"),
            rs.getString("checksum_sha256"),
            instant(rs, "created_at"),
            rs.getString("created_by"));

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    public record BigDecimalApproval(java.math.BigDecimal amount) {
    }
}
