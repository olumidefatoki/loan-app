package com.mfb.lending.module.collections.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.mfb.lending.module.collections.domain.CollectionCodes.ActivityType;
import com.mfb.lending.module.collections.domain.CollectionCodes.ClosureOutcome;
import com.mfb.lending.module.collections.domain.CollectionCodes.ContactChannel;
import com.mfb.lending.module.collections.domain.CollectionCodes.RestructuringType;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.servicing.calculation.HolidayRule;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class CollectionRequests {

    private CollectionRequests() {
    }

    public record AssignRequest(@NotNull UUID officerId, @NotNull UUID branchId) {
    }

    public record ActivityRequest(
            @NotNull ActivityType activityType,
            @NotNull ContactChannel channel,
            @NotBlank @Size(max = 500) String summary
    ) {
    }

    public record PromiseRequest(
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotNull LocalDate promiseDate,
            @Size(max = 500) String note
    ) {
    }

    public record EscalateRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record RecoveryRequest(
            @NotNull @DecimalMin("0.01") BigDecimal amount,
            @NotNull UUID repaymentId,
            @Size(max = 500) String note
    ) {
    }

    public record CloseRequest(
            @NotNull ClosureOutcome outcome,
            @NotBlank @Size(max = 500) String note,
            @DecimalMin("0.01") BigDecimal recoveryAmount,
            UUID repaymentId
    ) {
    }

    public record RestructuringRequest(
            @NotNull RestructuringType type,
            @NotBlank @Size(min = 15, max = 500) String justification,
            Integer tenure,
            BigDecimal interestRate,
            RepaymentFrequency repaymentFrequency,
            Integer repaymentDayOfMonth,
            HolidayRule holidayRule,
            BigDecimal revisedInstalment,
            Integer moratoriumDays,
            BigDecimal partialSettlementAmount,
            LocalDate revisedMaturity,
            LocalDate valueDate
    ) {
    }

    public record DecisionRequest(@Size(max = 500) String reason) {
    }

    public record RejectRequest(@NotBlank @Size(max = 500) String reason) {
    }
}
