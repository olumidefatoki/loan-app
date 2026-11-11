package com.mfb.lending.module.approval.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ApprovalMatrixResponse(
        UUID id,
        String code,
        String name,
        String description,
        String status,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        String updatedBy,
        List<MatrixVersionResponse> versions
) {
}
