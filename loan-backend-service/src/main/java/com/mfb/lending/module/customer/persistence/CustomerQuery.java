package com.mfb.lending.module.customer.persistence;

import java.util.List;
import java.util.UUID;

public record CustomerQuery(
        String customerType,
        String status,
        String category,
        UUID branchId,
        String reference,
        String email,
        String phoneNumber,
        String bvnHash,
        String ninHash,
        String cacNumber,
        String text,
        String textLike,
        boolean unrestricted,
        List<UUID> branchIds,
        int limit,
        int offset
) {
}
