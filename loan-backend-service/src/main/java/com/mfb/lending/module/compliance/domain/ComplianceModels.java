package com.mfb.lending.module.compliance.domain;

import java.time.Instant;
import java.util.UUID;

public final class ComplianceModels {

    private ComplianceModels() {
    }

    public record ComplianceAlert(
            UUID id,
            UUID customerId,
            UUID screeningResultId,
            String alertType,
            String severity,
            String status,
            String summary,
            String riskClassification,
            Instant openedAt) {
    }

    public record ComplianceCase(
            UUID id,
            String reference,
            UUID customerId,
            UUID alertId,
            String caseType,
            String status,
            String riskClassification,
            UUID assigneeId,
            String summary,
            String decision,
            String decisionReason,
            UUID openedBy,
            UUID decidedBy,
            Instant decidedAt,
            Instant escalatedAt,
            Instant evidenceRetainedUntil,
            Instant openedAt) {
    }

    public record CaseActivity(
            UUID id,
            UUID caseId,
            String activityType,
            String note,
            UUID actorId,
            String actorName,
            Instant createdAt) {
    }
}
