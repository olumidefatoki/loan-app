package com.mfb.lending.module.collateral.domain;

import java.time.Instant;
import java.util.UUID;

public record LoanGuarantorRecord(
        UUID id,
        UUID applicationId,
        UUID guarantorId,
        String relationship,
        boolean approvedSecurity,
        Instant linkedAt,
        UUID linkedBy
) {
}
