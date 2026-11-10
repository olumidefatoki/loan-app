package com.mfb.lending.module.auth.domain;

import java.util.UUID;

public record PermissionRecord(
        UUID id,
        String code,
        String description,
        boolean systemPermission
) {
}
