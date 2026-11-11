package com.mfb.lending.module.loanoffer.application;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine;
import com.mfb.lending.module.loanoffer.domain.AgreementKind;
import com.mfb.lending.module.loanoffer.domain.AgreementTemplate;
import com.mfb.lending.module.loanoffer.dto.AgreementTemplateResponse;
import com.mfb.lending.module.loanoffer.dto.SaveAgreementTemplateRequest;
import com.mfb.lending.module.loanoffer.persistence.LoanOfferRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AgreementTemplateService implements ApplicationRunner {

    private static final String LOAN_BODY = """
            LOAN AGREEMENT

            This agreement is between the lender and {{customerName}} ({{customerReference}}) for application {{applicationReference}}.

            Product: {{productName}} ({{productCode}})
            Approved principal: {{currency}} {{principal}}
            Interest rate: {{interestRate}} percent {{interestMethod}} ({{interestPeriod}})
            Tenure: {{tenure}} {{tenureUnit}}
            Repayment frequency: {{repaymentFrequency}}
            Instalment: {{currency}} {{instalmentAmount}}
            Processing fees: {{currency}} {{processingFees}}
            Other fees: {{currency}} {{otherFees}}
            Taxes: {{currency}} {{taxes}}
            Total interest: {{currency}} {{totalInterest}}
            Total repayment: {{currency}} {{totalRepayment}}
            Net amount available for disbursement: {{currency}} {{netDisbursement}}
            Disbursement date used for the schedule: {{disbursementDate}}
            Offer expiry: {{expiresAt}}
            Calculation engine: {{engineVersion}}

            Repayment schedule
            {{schedule}}

            Disbursement conditions
            {{disbursementConditions}}

            Collateral
            {{collateralDetails}}

            Guarantors
            {{guarantorDetails}}

            Default
            {{defaultProvisions}}

            The customer must read this version before accepting it. A recorded acceptance is an electronic record of that reading and of the verified KYC identity. It is not a qualified electronic signature unless an approved provider is configured and the required legal approval is in place.

            Template {{templateCode}} version {{templateVersion}}.
            """;

    private static final String GUARANTOR_BODY = """
            GUARANTOR AGREEMENT

            This guarantee relates to application {{applicationReference}} for {{customerName}}.
            Guarantors
            {{guarantorDetails}}

            The guarantor agrees to answer for the total repayment of {{currency}} {{totalRepayment}} on the terms of loan agreement template {{templateCode}} version {{templateVersion}}, subject to legal review of this template.

            Default
            {{defaultProvisions}}
            """;

    private static final String COLLATERAL_BODY = """
            COLLATERAL REFERENCE

            Application {{applicationReference}} for {{customerName}}.
            Collateral
            {{collateralDetails}}

            This document records the collateral referred to by the loan agreement. It does not by itself create a legal charge unless the approved collateral template and the institution's legal process say so.

            Template {{templateCode}} version {{templateVersion}}.
            """;

    private final LoanOfferRepository offers;
    private final OfferViews views;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public AgreementTemplateService(
            LoanOfferRepository offers,
            OfferViews views,
            AuditService audit,
            TransactionTemplate transactions) {
        this.offers = offers;
        this.views = views;
        this.audit = audit;
        this.transactions = transactions;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (offers.findActive(AgreementKind.LOAN).isPresent()) {
            return;
        }
        transactions.executeWithoutResult(status -> {
            if (offers.findActive(AgreementKind.LOAN).isPresent()) {
                return;
            }
            insertDefault("LOAN_STANDARD", "Standard loan agreement", AgreementKind.LOAN, LOAN_BODY);
            insertDefault("GUARANTOR_STANDARD", "Standard guarantor agreement", AgreementKind.GUARANTOR, GUARANTOR_BODY);
            insertDefault("COLLATERAL_REFERENCE", "Collateral reference", AgreementKind.COLLATERAL, COLLATERAL_BODY);
        });
    }

    public List<AgreementTemplateResponse> list() {
        return offers.listTemplates().stream().map(views::template).toList();
    }

    public AgreementTemplateResponse get(UUID id) {
        return views.template(offers.findTemplate(id).orElseThrow(() -> new ResourceNotFoundException("Agreement template")));
    }

    public AgreementTemplateResponse create(SaveAgreementTemplateRequest request, AuthenticatedUser actor, ClientContext client) {
        AgreementTemplateResponse response = transactions.execute(status -> store(null, request, actor));
        audit.record("AGREEMENT_TEMPLATE_CREATED", "loan_agreement_template", response.id().toString(), actor.getUsername(), client,
                java.util.Map.of("code", response.code(), "version", response.versionNumber()));
        return response;
    }

    public AgreementTemplateResponse revise(UUID id, SaveAgreementTemplateRequest request, AuthenticatedUser actor, ClientContext client) {
        AgreementTemplateResponse response = transactions.execute(status -> store(id, request, actor));
        audit.record("AGREEMENT_TEMPLATE_REVISED", "loan_agreement_template", response.id().toString(), actor.getUsername(), client,
                java.util.Map.of("code", response.code(), "version", response.versionNumber()));
        return response;
    }

    public void delete(UUID id, AuthenticatedUser actor, ClientContext client) {
        transactions.executeWithoutResult(status -> {
            AgreementTemplate template = offers.findTemplate(id).orElseThrow(() -> new ResourceNotFoundException("Agreement template"));
            if (offers.templateInUse(id)) {
                throw new ConflictException("An agreement template that has been used cannot be deleted");
            }
            if (!offers.deleteTemplate(id)) {
                throw new ResourceNotFoundException("Agreement template");
            }
            audit.record("AGREEMENT_TEMPLATE_DELETED", "loan_agreement_template", id.toString(), actor.getUsername(), client,
                    java.util.Map.of("code", template.code(), "version", template.versionNumber()));
        });
    }

    private AgreementTemplateResponse store(UUID existingId, SaveAgreementTemplateRequest request, AuthenticatedUser actor) {
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z0-9_]{2,40}")) {
            throw ValidationException.field("code", "Template code must be 2 to 40 letters, digits, or underscores");
        }
        AgreementKind kind = kind(request.kind());
        int versionNumber = 1;
        if (existingId != null) {
            AgreementTemplate current = offers.findTemplate(existingId).orElseThrow(() -> new ResourceNotFoundException("Agreement template"));
            if (!current.code().equals(code) || current.kind() != kind) {
                throw ValidationException.field("code", "A new version must keep the template code and kind");
            }
            versionNumber = offers.nextTemplateVersion(code);
        } else if (offers.nextTemplateVersion(code) > 1) {
            throw new ConflictException("Use the template update to publish a new version");
        }
        AgreementTemplate template = new AgreementTemplate(
                UUID.randomUUID(),
                code,
                request.name().trim(),
                kind,
                versionNumber,
                request.body().trim(),
                true,
                LoanCalculationEngine.sha256(request.body().trim()),
                null,
                actor.getUsername());
        offers.deactivateKind(kind.name(), template.id());
        offers.deactivateCode(code, template.id());
        offers.insertTemplate(template);
        return views.template(offers.findTemplate(template.id()).orElseThrow());
    }

    private void insertDefault(String code, String name, AgreementKind kind, String body) {
        String text = body.trim();
        offers.insertTemplate(new AgreementTemplate(
                UUID.randomUUID(), code, name, kind, 1, text, true, LoanCalculationEngine.sha256(text), null, "system"));
    }

    private static AgreementKind kind(String value) {
        try {
            return AgreementKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw ValidationException.field("kind", "kind must be LOAN, GUARANTOR, or COLLATERAL");
        }
    }
}
