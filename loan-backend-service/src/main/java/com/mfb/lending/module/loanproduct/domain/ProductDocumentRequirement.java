package com.mfb.lending.module.loanproduct.domain;

import java.util.UUID;

public record ProductDocumentRequirement(
        UUID id,
        UUID versionId,
        String documentCode,
        String documentName,
        boolean required
) {
}
