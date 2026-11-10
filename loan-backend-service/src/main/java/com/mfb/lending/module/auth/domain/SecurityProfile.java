package com.mfb.lending.module.auth.domain;

import java.util.List;
import java.util.UUID;

public record SecurityProfile(
        List<String> roles,
        List<String> permissions,
        List<UUID> branchIds,
        boolean requiresMfa,
        boolean unrestrictedBranches
) {
}
