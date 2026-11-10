package com.mfb.lending.module.loanproduct.persistence;

import java.time.Instant;

public record ProductSearch(
        String status,
        String category,
        String segment,
        boolean offeredOnly,
        Instant now,
        int limit,
        int offset
) {
}
