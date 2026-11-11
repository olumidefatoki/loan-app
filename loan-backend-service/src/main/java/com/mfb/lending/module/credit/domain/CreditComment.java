package com.mfb.lending.module.credit.domain;

import java.time.Instant;
import java.util.UUID;

public record CreditComment(UUID id, UUID assessmentId, UUID authorId, String body, Instant createdAt) {
}
