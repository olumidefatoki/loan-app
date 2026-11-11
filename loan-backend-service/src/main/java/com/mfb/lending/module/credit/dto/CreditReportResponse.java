package com.mfb.lending.module.credit.dto;

import java.util.List;

public record CreditReportResponse(
        CreditAssessmentResponse assessment,
        List<com.mfb.lending.module.credit.domain.ScoreComponentResult> components,
        List<CreditResultSummary> results,
        List<BureauCheckResponse> bureauChecks,
        List<CommentResponse> comments,
        List<PolicyExceptionResponse> exceptions
) {
}
