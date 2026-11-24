package com.mfb.lending.module.complaint.domain;

import java.time.Instant;
import java.util.UUID;

public final class ComplaintModels {

    private ComplaintModels() {
    }

    public record Complaint(
            UUID id,
            String reference,
            UUID customerId,
            UUID branchId,
            String category,
            String status,
            String subject,
            String description,
            UUID assigneeId,
            Instant slaDueAt,
            Instant escalatedAt,
            Instant resolvedAt,
            String resolution,
            Instant createdAt) {
    }

    public record ComplaintActivity(
            UUID id,
            UUID complaintId,
            String activityType,
            String note,
            UUID actorId,
            String actorName,
            Instant createdAt) {
    }
}
