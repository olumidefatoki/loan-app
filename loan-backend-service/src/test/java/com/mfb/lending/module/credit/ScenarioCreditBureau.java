package com.mfb.lending.module.credit;

import java.math.BigDecimal;
import java.util.concurrent.atomic.AtomicReference;

import com.mfb.lending.module.credit.bureau.BureauEnquiryResult;
import com.mfb.lending.module.credit.bureau.BureauExposureAssessment;
import com.mfb.lending.module.credit.bureau.BureauIdentification;
import com.mfb.lending.module.credit.bureau.BureauReport;
import com.mfb.lending.module.credit.bureau.BureauSubject;
import com.mfb.lending.module.credit.bureau.CreditBureauPort;
import com.mfb.lending.module.credit.domain.BureauCheckStatus;

public class ScenarioCreditBureau implements CreditBureauPort {

    private final AtomicReference<BureauEnquiryResult> next = new AtomicReference<>();

    public void reset() {
        next.set(null);
    }

    public void fail(String reason) {
        next.set(new BureauEnquiryResult(BureauCheckStatus.FAILED, null, reason));
    }

    @Override
    public String providerCode() {
        return "SCENARIO";
    }

    @Override
    public BureauIdentification identify(BureauSubject subject) {
        BureauEnquiryResult result = current();
        if (result.status() != BureauCheckStatus.COMPLETED) {
            return new BureauIdentification(true, null, result.failureReason());
        }
        return new BureauIdentification(true, true, "Customer identification matched the supplied identity.");
    }

    @Override
    public BureauEnquiryResult enquire(BureauSubject subject) {
        return current();
    }

    @Override
    public BureauReport retrieve(BureauSubject subject, BureauEnquiryResult enquiry) {
        if (enquiry.status() != BureauCheckStatus.COMPLETED) {
            return new BureauReport(enquiry.status(), null, null, enquiry.failureReason());
        }
        return new BureauReport(BureauCheckStatus.COMPLETED, enquiry.score(), null, "Mapped credit report");
    }

    @Override
    public BureauExposureAssessment assessExposure(BureauEnquiryResult enquiry, BureauReport report) {
        if (enquiry.status() != BureauCheckStatus.COMPLETED) {
            return new BureauExposureAssessment(null);
        }
        return new BureauExposureAssessment(BigDecimal.ZERO);
    }

    private BureauEnquiryResult current() {
        BureauEnquiryResult configured = next.get();
        if (configured != null) {
            return configured;
        }
        return new BureauEnquiryResult(BureauCheckStatus.COMPLETED, 800, null);
    }
}
