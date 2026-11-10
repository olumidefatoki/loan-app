package com.mfb.lending.module.customer.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SmeProfileRequest(
        @NotBlank @Size(max = 255) String registeredBusinessName,
        @Size(max = 255) String tradingName,
        @NotBlank @Size(max = 20) String cacRegistrationNumber,
        @NotBlank @Pattern(regexp = "SOLE_PROPRIETORSHIP|PARTNERSHIP|LIMITED_LIABILITY|PUBLIC_LIMITED|INCORPORATED_TRUSTEE|COOPERATIVE") String businessType,
        @NotBlank @Size(max = 128) String businessSector,
        LocalDate incorporationDate,
        @NotNull @Valid AddressRequest businessAddress,
        @NotNull @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal annualTurnover,
        @NotNull @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal monthlyRevenue,
        @NotNull @DecimalMin("0.00") @Digits(integer = 16, fraction = 2) BigDecimal operatingExpenses,
        @NotBlank @Size(max = 32) String taxIdentificationNumber,
        @NotEmpty @Valid List<RepresentativeRequest> authorisedRepresentatives,
        @Valid List<ConsentRequest> consents
) {
}
