package com.mfb.lending.module.loanapplication.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.module.customer.domain.AccountRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationDocumentRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationEventRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationSummary;
import com.mfb.lending.module.loanapplication.domain.CollateralRecord;
import com.mfb.lending.module.loanapplication.domain.FinancialProfile;
import com.mfb.lending.module.loanapplication.domain.GuarantorRecord;
import com.mfb.lending.module.loanapplication.domain.IndividualFinancials;
import com.mfb.lending.module.loanapplication.domain.Obligation;
import com.mfb.lending.module.loanapplication.domain.SmeFinancials;
import com.mfb.lending.module.loanapplication.domain.StatusCount;
import com.mfb.lending.module.loanapplication.domain.StatusHistoryRecord;
import com.mfb.lending.module.loanapplication.dto.ApplicationDashboardResponse;
import com.mfb.lending.module.loanapplication.dto.ApplicationHistoryResponse;
import com.mfb.lending.module.loanapplication.dto.LoanApplicationResponse;
import com.mfb.lending.module.loanapplication.dto.LoanApplicationSummaryResponse;
import com.mfb.lending.module.loanproduct.domain.LoanProductRecord;
import com.mfb.lending.module.loanproduct.domain.LoanProductVersionRecord;
import com.mfb.lending.module.loanproduct.domain.ProductDocumentRequirement;
import org.springframework.stereotype.Component;

@Component
class LoanApplicationViews {

    private final ObjectMapper objectMapper;

    LoanApplicationViews(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    LoanApplicationResponse detail(
            ApplicationRecord application,
            LoanProductRecord product,
            LoanProductVersionRecord version,
            AccountRecord account,
            FinancialProfile financials,
            List<GuarantorRecord> guarantors,
            List<CollateralRecord> collaterals,
            List<ApplicationDocumentRecord> documents,
            List<ProductDocumentRequirement> requirements) {
        return new LoanApplicationResponse(
                application.id(),
                application.reference(),
                application.customerId(),
                application.segment().name(),
                application.productId(),
                product.productCode(),
                version.productName(),
                version.id(),
                version.versionNumber(),
                application.status().name(),
                application.revisionNumber(),
                application.correctionOpen(),
                application.amount(),
                application.currencyCode(),
                application.purpose(),
                application.tenure(),
                application.tenureUnit().name(),
                application.repaymentFrequency() == null ? null : application.repaymentFrequency().name(),
                application.disbursementAccountId(),
                account == null ? null : account.maskedNumber(),
                individual(financials.individual()),
                sme(financials.sme()),
                financials.obligations().stream()
                        .map(item -> new LoanApplicationResponse.ObligationItem(
                                item.lender(), item.outstandingBalance(), item.monthlyRepayment()))
                        .toList(),
                guarantors.stream().map(LoanApplicationViews::guarantor).toList(),
                collaterals.stream().map(LoanApplicationViews::collateral).toList(),
                documentRequirements(requirements, documents),
                application.channel().name(),
                application.submittedAt(),
                application.withdrawnAt(),
                application.decisionReason(),
                application.createdAt(),
                application.updatedAt());
    }

    LoanApplicationSummaryResponse summary(ApplicationSummary application) {
        return new LoanApplicationSummaryResponse(
                application.id(),
                application.reference(),
                application.customerId(),
                application.customerName(),
                application.segment().name(),
                application.productId(),
                application.productCode(),
                application.productName(),
                application.status().name(),
                application.amount(),
                application.currencyCode(),
                application.tenure(),
                application.tenureUnit().name(),
                application.repaymentFrequency() == null ? null : application.repaymentFrequency().name(),
                application.revisionNumber(),
                application.correctionOpen(),
                application.channel().name(),
                application.submittedAt(),
                application.updatedAt());
    }

    ApplicationHistoryResponse history(List<StatusHistoryRecord> statuses, List<ApplicationEventRecord> events) {
        return new ApplicationHistoryResponse(
                statuses.stream().map(item -> new ApplicationHistoryResponse.StatusChange(
                        item.id(),
                        item.fromStatus() == null ? null : item.fromStatus().name(),
                        item.toStatus().name(),
                        item.reason(),
                        item.actorId(),
                        item.occurredAt())).toList(),
                events.stream().map(item -> new ApplicationHistoryResponse.Event(
                        item.id(),
                        item.eventType(),
                        payload(item.payload()),
                        item.actorId(),
                        item.occurredAt())).toList());
    }

    ApplicationDashboardResponse dashboard(List<StatusCount> counts) {
        long drafts = 0;
        long inProgress = 0;
        long offers = 0;
        long approved = 0;
        long disbursed = 0;
        long closed = 0;
        long total = 0;
        BigDecimal requested = BigDecimal.ZERO;
        for (StatusCount count : counts) {
            total += count.count();
            switch (count.status()) {
                case DRAFT -> drafts += count.count();
                case OFFER_ISSUED -> offers += count.count();
                case APPROVED -> approved += count.count();
                case DISBURSED -> disbursed += count.count();
                case REJECTED, WITHDRAWN, CANCELLED, EXPIRED -> closed += count.count();
                default -> inProgress += count.count();
            }
            if (count.status() != com.mfb.lending.module.loanapplication.domain.ApplicationStatus.REJECTED
                    && count.status() != com.mfb.lending.module.loanapplication.domain.ApplicationStatus.WITHDRAWN
                    && count.status() != com.mfb.lending.module.loanapplication.domain.ApplicationStatus.CANCELLED
                    && count.status() != com.mfb.lending.module.loanapplication.domain.ApplicationStatus.EXPIRED
                    && count.amount() != null) {
                requested = requested.add(count.amount());
            }
        }
        return new ApplicationDashboardResponse(total, drafts, inProgress, offers, approved, disbursed, closed, requested);
    }

    String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Application event could not be written", ex);
        }
    }

    private JsonNode payload(String json) {
        if (json == null || json.isBlank()) {
            return objectMapper.createObjectNode();
        }
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException ex) {
            return objectMapper.createObjectNode().put("unreadable", true);
        }
    }

    private static LoanApplicationResponse.IndividualFinancials individual(IndividualFinancials financials) {
        if (financials == null) {
            return null;
        }
        return new LoanApplicationResponse.IndividualFinancials(
                financials.monthlyIncome(),
                financials.otherIncome(),
                financials.monthlyExpenses(),
                financials.employmentStatus() == null ? null : financials.employmentStatus().name(),
                financials.employerName(),
                financials.jobTitle(),
                financials.employmentStartDate());
    }

    private static LoanApplicationResponse.SmeFinancials sme(SmeFinancials financials) {
        if (financials == null) {
            return null;
        }
        return new LoanApplicationResponse.SmeFinancials(
                financials.annualTurnover(),
                financials.monthlyTurnover(),
                financials.operatingExpenses(),
                financials.monthlyCashFlow(),
                financials.businessLiabilities(),
                financials.authorisedToBorrow(),
                financials.authorisedBorrowerName(),
                financials.authorisedBorrowingLimit());
    }

    private static LoanApplicationResponse.GuarantorItem guarantor(GuarantorRecord guarantor) {
        return new LoanApplicationResponse.GuarantorItem(
                guarantor.id(),
                guarantor.fullName(),
                guarantor.phoneNumber(),
                guarantor.email(),
                guarantor.relationship(),
                guarantor.occupation(),
                guarantor.addressLine());
    }

    private static LoanApplicationResponse.CollateralItem collateral(CollateralRecord collateral) {
        return new LoanApplicationResponse.CollateralItem(
                collateral.id(),
                collateral.collateralType(),
                collateral.description(),
                collateral.estimatedValue(),
                collateral.ownerName(),
                collateral.location());
    }

    private static List<LoanApplicationResponse.DocumentRequirement> documentRequirements(
            List<ProductDocumentRequirement> requirements,
            List<ApplicationDocumentRecord> documents) {
        Map<String, ApplicationDocumentRecord> uploaded = new LinkedHashMap<>();
        for (ApplicationDocumentRecord document : documents) {
            uploaded.putIfAbsent(document.documentCode(), document);
        }
        List<LoanApplicationResponse.DocumentRequirement> result = new ArrayList<>();
        for (ProductDocumentRequirement requirement : requirements) {
            ApplicationDocumentRecord file = uploaded.remove(requirement.documentCode());
            result.add(new LoanApplicationResponse.DocumentRequirement(
                    requirement.documentCode(),
                    requirement.documentName(),
                    requirement.required(),
                    file != null,
                    file == null ? null : file.id()));
        }
        for (ApplicationDocumentRecord file : uploaded.values()) {
            result.add(new LoanApplicationResponse.DocumentRequirement(
                    file.documentCode(), file.documentName(), false, true, file.id()));
        }
        return List.copyOf(result);
    }

    static List<String> changedFields(
            ApplicationRecord current,
            FinancialProfile currentFinancials,
            List<GuarantorRecord> currentGuarantors,
            List<CollateralRecord> currentCollaterals,
            DraftState next) {
        List<String> fields = new ArrayList<>();
        if (!sameMoney(current.amount(), next.amount())) {
            fields.add("amount");
        }
        if (!Objects.equals(current.purpose(), next.purpose())) {
            fields.add("purpose");
        }
        if (!Objects.equals(current.tenure(), next.tenure())) {
            fields.add("tenure");
        }
        if (current.repaymentFrequency() != next.frequency()) {
            fields.add("repaymentFrequency");
        }
        if (!Objects.equals(current.disbursementAccountId(), next.accountId())) {
            fields.add("disbursementAccountId");
        }
        if (!sameIndividual(currentFinancials.individual(), next.financials().individual())) {
            fields.add("individualFinancials");
        }
        if (!sameSme(currentFinancials.sme(), next.financials().sme())) {
            fields.add("smeFinancials");
        }
        if (!sameObligations(currentFinancials.obligations(), next.financials().obligations())) {
            fields.add("obligations");
        }
        if (!sameGuarantors(currentGuarantors, next.guarantors())) {
            fields.add("guarantors");
        }
        if (!sameCollaterals(currentCollaterals, next.collaterals())) {
            fields.add("collaterals");
        }
        return fields;
    }

    private static boolean sameIndividual(IndividualFinancials left, IndividualFinancials right) {
        if (left == null || right == null) {
            return left == right;
        }
        return sameMoney(left.monthlyIncome(), right.monthlyIncome())
                && sameMoney(left.otherIncome(), right.otherIncome())
                && sameMoney(left.monthlyExpenses(), right.monthlyExpenses())
                && left.employmentStatus() == right.employmentStatus()
                && Objects.equals(left.employerName(), right.employerName())
                && Objects.equals(left.jobTitle(), right.jobTitle())
                && Objects.equals(left.employmentStartDate(), right.employmentStartDate());
    }

    private static boolean sameSme(SmeFinancials left, SmeFinancials right) {
        if (left == null || right == null) {
            return left == right;
        }
        return sameMoney(left.annualTurnover(), right.annualTurnover())
                && sameMoney(left.monthlyTurnover(), right.monthlyTurnover())
                && sameMoney(left.operatingExpenses(), right.operatingExpenses())
                && sameMoney(left.monthlyCashFlow(), right.monthlyCashFlow())
                && sameMoney(left.businessLiabilities(), right.businessLiabilities())
                && Objects.equals(left.authorisedToBorrow(), right.authorisedToBorrow())
                && Objects.equals(left.authorisedBorrowerName(), right.authorisedBorrowerName())
                && sameMoney(left.authorisedBorrowingLimit(), right.authorisedBorrowingLimit());
    }

    private static boolean sameObligations(List<Obligation> left, List<Obligation> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!Objects.equals(left.get(i).lender(), right.get(i).lender())
                    || !sameMoney(left.get(i).outstandingBalance(), right.get(i).outstandingBalance())
                    || !sameMoney(left.get(i).monthlyRepayment(), right.get(i).monthlyRepayment())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameGuarantors(List<GuarantorRecord> left, List<GuarantorRecord> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            GuarantorRecord a = left.get(i);
            GuarantorRecord b = right.get(i);
            if (!Objects.equals(a.fullName(), b.fullName())
                    || !Objects.equals(a.phoneNumber(), b.phoneNumber())
                    || !Objects.equals(a.email(), b.email())
                    || !Objects.equals(a.relationship(), b.relationship())
                    || !Objects.equals(a.occupation(), b.occupation())
                    || !Objects.equals(a.addressLine(), b.addressLine())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameCollaterals(List<CollateralRecord> left, List<CollateralRecord> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            CollateralRecord a = left.get(i);
            CollateralRecord b = right.get(i);
            if (!Objects.equals(a.collateralType(), b.collateralType())
                    || !Objects.equals(a.description(), b.description())
                    || !sameMoney(a.estimatedValue(), b.estimatedValue())
                    || !Objects.equals(a.ownerName(), b.ownerName())
                    || !Objects.equals(a.location(), b.location())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameMoney(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return left == right;
        }
        return left.compareTo(right) == 0;
    }

    record DraftState(
            BigDecimal amount,
            String purpose,
            Integer tenure,
            com.mfb.lending.module.loanproduct.domain.RepaymentFrequency frequency,
            UUID accountId,
            boolean accountVerified,
            FinancialProfile financials,
            List<GuarantorRecord> guarantors,
            List<CollateralRecord> collaterals
    ) {
    }
}
