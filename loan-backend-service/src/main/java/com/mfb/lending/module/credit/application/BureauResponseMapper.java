package com.mfb.lending.module.credit.application;

import com.mfb.lending.module.credit.bureau.BureauEnquiryResult;
import com.mfb.lending.module.credit.bureau.BureauExposureAssessment;
import com.mfb.lending.module.credit.bureau.BureauIdentification;
import com.mfb.lending.module.credit.bureau.BureauReport;
import com.mfb.lending.module.credit.bureau.MappedBureauResponse;
import java.util.Locale;

import com.mfb.lending.module.credit.domain.BureauCheckStatus;

public final class BureauResponseMapper {

    private BureauResponseMapper() {
    }

    public static MappedBureauResponse map(
            String providerCode,
            BureauIdentification identification,
            BureauEnquiryResult enquiry,
            BureauReport report,
            BureauExposureAssessment exposure) {
        BureauCheckStatus status = enquiry == null || enquiry.status() == null
                ? BureauCheckStatus.UNAVAILABLE
                : enquiry.status();
        Integer score = report != null && report.score() != null ? report.score() : enquiry == null ? null : enquiry.score();
        String failure = enquiry == null ? "The bureau enquiry did not return a result." : enquiry.failureReason();
        if ((status == BureauCheckStatus.FAILED || status == BureauCheckStatus.UNAVAILABLE) && (failure == null || failure.isBlank())) {
            failure = "The credit bureau enquiry could not be completed.";
        }
        return new MappedBureauResponse(
                providerCode == null ? "UNCONFIGURED" : providerCode.trim().toUpperCase(Locale.ROOT),
                status,
                identification == null ? null : identification.matched(),
                status == BureauCheckStatus.COMPLETED ? score : null,
                exposure == null || status != BureauCheckStatus.COMPLETED ? null : exposure.existingExposure(),
                report == null || status != BureauCheckStatus.COMPLETED ? null : report.repaymentHistory(),
                status == BureauCheckStatus.COMPLETED ? null : failure);
    }
}
