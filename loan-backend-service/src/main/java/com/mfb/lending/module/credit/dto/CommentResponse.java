package com.mfb.lending.module.credit.dto;

import java.time.Instant;
import java.util.UUID;

public record CommentResponse(UUID id, UUID authorId, String comment, Instant createdAt) {
}
