package com.mfb.lending.module.loanoffer.api;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.loanoffer.application.LoanOfferService;
import com.mfb.lending.module.loanoffer.dto.LoanOfferSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/customers/me/loan-offers")
@Tag(name = "Loan offers", description = "Offers belonging to the signed-in customer.")
@SecurityRequirement(name = "bearerAuth")
public class CustomerLoanOfferController {

    private final LoanOfferService offers;

    public CustomerLoanOfferController(LoanOfferService offers) {
        this.offers = offers;
    }

    @GetMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "List my loan offers", description = "Returns the signed-in customer's offers. Elapsed expiries are applied before the page is returned.")
    public ApiResponse<PageResponse<LoanOfferSummaryResponse>> mine(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(offers.mine(actor, page, size));
    }
}
