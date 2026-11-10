package com.mfb.lending.module.customer.application;

import java.util.ArrayList;
import java.util.List;

import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.CustomerStatus;
import com.mfb.lending.module.customer.domain.KycStatus;

public final class DisbursementPolicy {

    private DisbursementPolicy() {
    }

    public static List<String> blockedReasons(CustomerRecord customer, boolean openMandatoryReview, boolean verifiedAccount) {
        List<String> reasons = new ArrayList<>();
        if (customer.status() != CustomerStatus.ACTIVE) {
            reasons.add("NOT_ACTIVE");
        }
        if (customer.blacklisted()) {
            reasons.add("BLACKLISTED");
        }
        if (customer.complianceHold() || openMandatoryReview) {
            reasons.add("COMPLIANCE_HOLD");
        }
        if (customer.kycStatus() != KycStatus.VERIFIED) {
            reasons.add("KYC_NOT_VERIFIED");
        }
        if (!verifiedAccount) {
            reasons.add("NO_VERIFIED_ACCOUNT");
        }
        return List.copyOf(reasons);
    }
}
