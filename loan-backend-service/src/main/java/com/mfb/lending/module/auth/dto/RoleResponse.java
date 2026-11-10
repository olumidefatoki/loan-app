package com.mfb.lending.module.auth.dto;

import java.util.List;
import java.util.UUID;

public record RoleResponse(
        UUID id,
        String code,
        String name,
        String description,
        boolean requiresMfa,
        boolean unrestrictedBranches,
        boolean systemRole,
        List<String> permissions
) {
}
