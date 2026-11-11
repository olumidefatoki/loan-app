package com.mfb.lending.module.credit.bureau;

import com.mfb.lending.module.credit.domain.BureauCheckStatus;
import org.springframework.stereotype.Component;

@Component
public class UnconfiguredCreditBureau implements CreditBureauPort {

    public static final String PROVIDER_CODE = "UNCONFIGURED";
    private static final String UNAVAILABLE = "No credit bureau provider is configured. The enquiry was recorded and was not sent to a bureau.";

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public BureauIdentification identify(BureauSubject subject) {
        return new BureauIdentification(false, null, UNAVAILABLE);
    }

    @Override
    public BureauEnquiryResult enquire(BureauSubject subject) {
        return new BureauEnquiryResult(BureauCheckStatus.UNAVAILABLE, null, UNAVAILABLE);
    }

    @Override
    public BureauReport retrieve(BureauSubject subject, BureauEnquiryResult enquiry) {
        return new BureauReport(BureauCheckStatus.UNAVAILABLE, null, null, UNAVAILABLE);
    }

    @Override
    public BureauExposureAssessment assessExposure(BureauEnquiryResult enquiry, BureauReport report) {
        return new BureauExposureAssessment(null);
    }
}
