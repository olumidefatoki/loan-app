package com.mfb.lending.module.loanapplication.domain;

import java.util.UUID;

public record GuarantorRecord(
        UUID id,
        UUID applicationId,
        String fullName,
        String phoneNumber,
        String email,
        String relationship,
        String occupation,
        String addressLine
) {
}
