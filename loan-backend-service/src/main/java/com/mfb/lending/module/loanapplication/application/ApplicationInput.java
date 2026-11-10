package com.mfb.lending.module.loanapplication.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.module.loanapplication.domain.IndividualFinancials;
import com.mfb.lending.module.loanapplication.domain.Obligation;
import com.mfb.lending.module.loanapplication.domain.SmeFinancials;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;

public record ApplicationInput(
        BigDecimal amount,
        String purpose,
        Integer tenure,
        RepaymentFrequency repaymentFrequency,
        UUID disbursementAccountId,
        boolean disbursementAccountVerified,
        IndividualFinancials individualFinancials,
        SmeFinancials smeFinancials,
        List<Obligation> obligations,
        List<String> guarantorPhones,
        List<CollateralPosition> collaterals,
        Set<String> uploadedDocumentCodes
) {

    public ApplicationInput {
        obligations = obligations == null ? List.of() : List.copyOf(obligations);
        guarantorPhones = guarantorPhones == null ? List.of() : List.copyOf(guarantorPhones);
        collaterals = collaterals == null ? List.of() : List.copyOf(collaterals);
        uploadedDocumentCodes = uploadedDocumentCodes == null ? Set.of() : Set.copyOf(uploadedDocumentCodes);
    }

    public record CollateralPosition(String type, BigDecimal estimatedValue) {
    }
}
