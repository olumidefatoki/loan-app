package com.mfb.lending.module.loanproduct.domain;

import java.util.UUID;

public record ApprovalRule(
        UUID id,
        UUID versionId,
        int stepOrder,
        String roleCode,
        String description
) {
}
