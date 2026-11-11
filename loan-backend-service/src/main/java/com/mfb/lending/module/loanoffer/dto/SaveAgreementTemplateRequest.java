package com.mfb.lending.module.loanoffer.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SaveAgreementTemplateRequest(
        @NotBlank @Size(min = 2, max = 40) String code,
        @NotBlank @Size(max = 160) String name,
        @NotBlank @Size(max = 16) String kind,
        @NotBlank @Size(max = 20000) String body
) {
}
