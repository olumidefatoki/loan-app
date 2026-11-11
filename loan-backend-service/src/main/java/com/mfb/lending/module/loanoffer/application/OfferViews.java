package com.mfb.lending.module.loanoffer.application;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.module.loanoffer.domain.AgreementAcceptance;
import com.mfb.lending.module.loanoffer.domain.AgreementTemplate;
import com.mfb.lending.module.loanoffer.domain.LoanOffer;
import com.mfb.lending.module.loanoffer.domain.OfferTerms;
import com.mfb.lending.module.loanoffer.domain.OfferVersion;
import com.mfb.lending.module.loanoffer.domain.ScheduleItem;
import com.mfb.lending.module.loanoffer.domain.StoredAgreement;
import com.mfb.lending.module.loanoffer.dto.AgreementResponse;
import com.mfb.lending.module.loanoffer.dto.AgreementTemplateResponse;
import com.mfb.lending.module.loanoffer.dto.LoanOfferResponse;
import com.mfb.lending.module.loanoffer.dto.LoanOfferSummaryResponse;
import com.mfb.lending.module.loanoffer.dto.ScheduleItemResponse;
import org.springframework.stereotype.Component;

@Component
public class OfferViews {

    private final ObjectMapper objectMapper;

    public OfferViews(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public LoanOfferResponse offer(LoanOffer offer, OfferVersion version, OfferTerms terms, StoredAgreement agreement) {
        return new LoanOfferResponse(
                offer.id(),
                offer.reference(),
                offer.applicationId(),
                terms.applicationReference(),
                offer.customerId(),
                terms.customerName(),
                terms.customerReference(),
                offer.status().name(),
                offer.expiresAt(),
                offer.acceptedAt(),
                offer.version(),
                version.versionNumber(),
                version.engineVersion(),
                version.fingerprint(),
                terms(terms),
                agreement == null ? null : agreement.id(),
                agreement == null ? null : agreement.checksumSha256(),
                SignatureStatements.RECORDED_ACCEPTANCE);
    }

    public LoanOfferSummaryResponse summary(LoanOffer offer, OfferTerms terms) {
        return new LoanOfferSummaryResponse(
                offer.id(),
                offer.reference(),
                offer.applicationId(),
                terms.applicationReference(),
                terms.productName(),
                offer.status().name(),
                terms.principal(),
                terms.currencyCode(),
                terms.instalmentAmount(),
                offer.expiresAt(),
                offer.version());
    }

    public List<ScheduleItemResponse> schedule(List<ScheduleItem> items) {
        return items.stream().map(item -> new ScheduleItemResponse(
                item.instalmentNumber(),
                item.dueDate(),
                item.openingBalance(),
                item.principalDue(),
                item.interestDue(),
                item.instalmentAmount(),
                item.closingBalance())).toList();
    }

    public AgreementResponse agreement(
            LoanOffer offer,
            OfferVersion version,
            StoredAgreement agreement,
            AgreementAcceptance acceptance,
            List<StoredAgreement> related) {
        return new AgreementResponse(
                agreement.id(),
                offer.id(),
                version.versionNumber(),
                agreement.kind().name(),
                agreement.templateId(),
                agreement.templateVersion(),
                agreement.checksumSha256(),
                agreement.sizeBytes(),
                agreement.contentType(),
                agreement.fileName(),
                agreement.frozenAt(),
                agreement.supersededAt(),
                agreement.renderedText(),
                "SHA-256",
                acceptance == null ? null : new AgreementResponse.AcceptanceResponse(
                        acceptance.id(),
                        acceptance.acceptedAt(),
                        acceptance.identityVerificationReference(),
                        acceptance.kycStatus(),
                        acceptance.signatureProvider(),
                        acceptance.signatureAssurance(),
                        acceptance.signatureReference(),
                        acceptance.disclaimer()),
                related.stream()
                        .filter(item -> !item.id().equals(agreement.id()))
                        .map(item -> new AgreementResponse.RelatedAgreement(
                                item.id(), item.kind().name(), item.checksumSha256(), item.supersededAt() != null))
                        .toList(),
                SignatureStatements.RECORDED_ACCEPTANCE);
    }

    public AgreementTemplateResponse template(AgreementTemplate template) {
        return new AgreementTemplateResponse(
                template.id(),
                template.code(),
                template.name(),
                template.kind().name(),
                template.versionNumber(),
                template.body(),
                template.active(),
                template.checksumSha256(),
                template.createdAt());
    }

    private LoanOfferResponse.TermsResponse terms(OfferTerms terms) {
        return new LoanOfferResponse.TermsResponse(
                terms.productId(),
                terms.productCode(),
                terms.productName(),
                terms.productVersionId(),
                terms.principal(),
                terms.currencyCode(),
                terms.interestRate(),
                terms.interestMethod(),
                terms.interestPeriod(),
                terms.tenure(),
                terms.tenureUnit(),
                terms.repaymentFrequency(),
                terms.dayCountConvention(),
                terms.roundingPrecision(),
                terms.roundingMethod(),
                terms.gracePeriodDays(),
                terms.instalmentAmount(),
                terms.processingFees(),
                terms.otherFees(),
                terms.taxes(),
                terms.taxRate(),
                terms.totalInterest(),
                terms.totalRepayment(),
                terms.netDisbursement(),
                terms.disbursementDate(),
                fees(terms.feeBreakdown()),
                terms.disbursementConditions(),
                terms.collateralDetails(),
                terms.guarantorDetails(),
                terms.defaultProvisions());
    }

    private List<LoanOfferResponse.FeeResponse> fees(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            List<LoanOfferResponse.FeeResponse> fees = new ArrayList<>();
            if (node.isArray()) {
                for (JsonNode fee : node) {
                    fees.add(new LoanOfferResponse.FeeResponse(
                            text(fee, "type"),
                            text(fee, "name"),
                            text(fee, "calculation"),
                            fee.path("amount").isMissingNode() || fee.path("amount").isNull() ? null : fee.path("amount").decimalValue(),
                            fee.path("rate").isMissingNode() || fee.path("rate").isNull() ? null : fee.path("rate").decimalValue(),
                            fee.path("charged").decimalValue()));
                }
            }
            return fees;
        } catch (Exception ex) {
            throw new IllegalStateException("Offer fee breakdown could not be read", ex);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
