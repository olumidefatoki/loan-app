package com.mfb.lending.module.credit.bureau;

import com.mfb.lending.module.credit.domain.BureauCheckStatus;

public record BureauEnquiryResult(BureauCheckStatus status, Integer score, String failureReason) {
}
