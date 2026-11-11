package com.mfb.lending.module.servicing.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import com.mfb.lending.module.servicing.calculation.HolidayRule;
import com.mfb.lending.module.servicing.domain.RepaymentChannel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public final class ServicingRequests {

    private ServicingRequests() {
    }

    public record RepaymentRequest(
            @NotNull UUID loanAccountId,
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @NotNull RepaymentChannel channel,
            @NotBlank @Size(min = 8, max = 64) @Pattern(regexp = "[A-Za-z0-9._:-]{8,64}") String idempotencyKey,
            LocalDate valueDate,
            @Size(max = 80) String externalReference
    ) {
    }

    public record SettlementQuoteRequest(LocalDate valueDate) {
    }

    public record SettlementRequest(
            @NotNull @DecimalMin(value = "0.01") BigDecimal amount,
            @NotNull RepaymentChannel channel,
            @NotBlank @Size(min = 8, max = 64) @Pattern(regexp = "[A-Za-z0-9._:-]{8,64}") String idempotencyKey,
            LocalDate valueDate,
            @Size(max = 80) String externalReference
    ) {
    }

    public record ReversalRequest(
            @NotBlank @Size(min = 8, max = 64) @Pattern(regexp = "[A-Za-z0-9._:-]{8,64}") String idempotencyKey
    ) {
    }

    public record InterestAdjustmentRequest(
            @NotNull BigDecimal amount,
            @NotBlank @Size(max = 500) String reason
    ) {
    }

    public record RestructureRequest(
            @Positive int tenure,
            @NotNull BigDecimal interestRate,
            boolean capitalizeInterest,
            Integer repaymentDayOfMonth,
            HolidayRule holidayRule,
            @NotBlank @Size(max = 500) String reason,
            LocalDate valueDate
    ) {
    }
}
