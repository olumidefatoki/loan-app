package com.mfb.lending.module.loanapplication.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@Schema(description = """
        Creates or updates a loan application draft. React and Flutter send the same JSON body. \
        On update, omit a section to leave it unchanged. Send an empty array to clear obligations, guarantors, or collaterals. \
        Individual and SME financial sections are separate: send only the section that matches the customer.""")
public record SaveLoanApplicationRequest(
        @Schema(description = "Required when staff originate an application. Customers omit this and apply as themselves.")
        UUID customerId,
        @Schema(description = "Active loan product. Required when creating a draft. It cannot be changed later.", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
        UUID productId,
        @DecimalMin("0.01")
        @Digits(integer = 17, fraction = 2)
        @Schema(example = "250000.00")
        BigDecimal amount,
        @Size(max = 500)
        @Schema(example = "Working capital for inventory")
        String purpose,
        @Min(1)
        @Max(6000)
        @Schema(example = "12")
        Integer tenure,
        @Schema(description = "DAILY, WEEKLY, or MONTHLY. Must match the product.", example = "MONTHLY")
        String repaymentFrequency,
        UUID disbursementAccountId,
        @Valid IndividualFinancialsRequest individualFinancials,
        @Valid SmeFinancialsRequest smeFinancials,
        @Valid @Size(max = 20) List<ObligationRequest> obligations,
        @Valid @Size(max = 20) List<GuarantorRequest> guarantors,
        @Valid @Size(max = 20) List<CollateralRequest> collaterals
) {

    @Schema(description = "Personal income, expenses, and employment. Use this for individual customers only.")
    public record IndividualFinancialsRequest(
            @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal monthlyIncome,
            @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal otherIncome,
            @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal monthlyExpenses,
            @Pattern(regexp = "EMPLOYED|SELF_EMPLOYED|UNEMPLOYED|RETIRED|STUDENT") String employmentStatus,
            @Size(max = 255) String employerName,
            @Size(max = 160) String jobTitle,
            LocalDate employmentStartDate
    ) {
    }

    @Schema(description = "Business turnover, operating expenses, cash flow, liabilities, and borrowing authority. Use this for SME customers only.")
    public record SmeFinancialsRequest(
            @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal annualTurnover,
            @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal monthlyTurnover,
            @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal operatingExpenses,
            @Digits(integer = 17, fraction = 2) BigDecimal monthlyCashFlow,
            @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal businessLiabilities,
            Boolean authorisedToBorrow,
            @Size(max = 255) String authorisedBorrowerName,
            @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal authorisedBorrowingLimit
    ) {
    }

    @Schema(description = "An existing loan or credit obligation.")
    public record ObligationRequest(
            @NotBlank @Size(max = 160) String lender,
            @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal outstandingBalance,
            @NotNull @DecimalMin("0.00") @Digits(integer = 17, fraction = 2) BigDecimal monthlyRepayment
    ) {
    }

    public record GuarantorRequest(
            @NotBlank @Size(max = 255) String fullName,
            @NotBlank @Pattern(regexp = "^\\+[1-9]\\d{7,14}$") String phoneNumber,
            @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 80) String relationship,
            @Size(max = 160) String occupation,
            @Size(max = 500) String addressLine
    ) {
    }

    public record CollateralRequest(
            @NotBlank @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{0,39}$") String collateralType,
            @Size(max = 500) String description,
            @NotNull @DecimalMin("0.01") @Digits(integer = 17, fraction = 2) BigDecimal estimatedValue,
            @NotBlank @Size(max = 255) String ownerName,
            @Size(max = 500) String location
    ) {
    }
}
