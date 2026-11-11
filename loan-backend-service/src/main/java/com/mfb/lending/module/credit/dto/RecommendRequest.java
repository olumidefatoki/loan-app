package com.mfb.lending.module.credit.dto;

import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RecommendRequest(
        @NotBlank String recommendation,
        @NotBlank @Size(min = 10, max = 2000) String justification,
        UUID exceptionId
) {
}
