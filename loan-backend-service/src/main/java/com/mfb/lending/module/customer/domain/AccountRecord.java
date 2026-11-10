package com.mfb.lending.module.customer.domain;

import java.time.Instant;
import java.util.UUID;

public record AccountRecord(
        UUID id,
        UUID customerId,
        String bankCode,
        String bankName,
        String maskedNumber,
        String accountName,
        AccountVerificationStatus verificationStatus,
        Instant verifiedAt
) {
}
