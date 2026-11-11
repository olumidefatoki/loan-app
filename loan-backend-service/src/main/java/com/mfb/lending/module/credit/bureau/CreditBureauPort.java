package com.mfb.lending.module.credit.bureau;

public interface CreditBureauPort {

    String providerCode();

    BureauIdentification identify(BureauSubject subject);

    BureauEnquiryResult enquire(BureauSubject subject);

    BureauReport retrieve(BureauSubject subject, BureauEnquiryResult enquiry);

    BureauExposureAssessment assessExposure(BureauEnquiryResult enquiry, BureauReport report);
}
