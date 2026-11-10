package com.mfb.lending.module.customer.dto;

import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        String bankCode,
        String bankName,
        String accountNumberMasked,
        String accountName,
        String currencyCode,
        String verificationStatus,
        Instant verifiedAt
) {
}
