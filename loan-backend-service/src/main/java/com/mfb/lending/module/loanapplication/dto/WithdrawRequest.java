package com.mfb.lending.module.loanapplication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WithdrawRequest(
        @NotBlank @Size(max = 500) String reason
) {
}
