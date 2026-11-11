package com.mfb.lending.module.credit.bureau;

import java.util.UUID;

import com.mfb.lending.module.customer.domain.CustomerKind;

public record BureauSubject(
        UUID assessmentId,
        UUID customerId,
        String customerReference,
        CustomerKind kind,
        String displayName
) {
}
