package com.mfb.lending.module.reporting.api;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.reporting.application.ReportingService;
import com.mfb.lending.module.reporting.dto.ReportResponses.CustomerDashboard;
import com.mfb.lending.module.reporting.dto.ReportResponses.CustomerLoanPage;
import com.mfb.lending.module.reporting.dto.ReportResponses.CustomerRepaymentPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard/customer")
@Tag(name = "Customer dashboard")
@SecurityRequirement(name = "bearerAuth")
public class CustomerDashboardController {

    private final ReportingService reports;

    public CustomerDashboardController(ReportingService reports) {
        this.reports = reports;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Customer position, next repayment, statement, and application history")
    public ApiResponse<CustomerDashboard> dashboard(@AuthenticationPrincipal AuthenticatedUser actor) {
        return ApiResponse.success(reports.customer(actor));
    }

    @GetMapping("/loans")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The customer's own loans")
    public ApiResponse<CustomerLoanPage> loans(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.customerLoans(actor, query));
    }

    @GetMapping("/repayments")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "The customer's own repayment history")
    public ApiResponse<CustomerRepaymentPage> repayments(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.customerRepayments(actor, query));
    }
}
