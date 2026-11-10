package com.mfb.lending.module.auth.domain;

import java.util.UUID;

public record RoleRecord(
        UUID id,
        String code,
        String name,
        String description,
        boolean requiresMfa,
        boolean unrestrictedBranches,
        boolean systemRole
) {
}
