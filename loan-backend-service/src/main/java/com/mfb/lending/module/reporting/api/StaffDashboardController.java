package com.mfb.lending.module.reporting.api;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.reporting.application.ReportingService;
import com.mfb.lending.module.reporting.dto.ReportResponses.BranchDashboard;
import com.mfb.lending.module.reporting.dto.ReportResponses.CollectionsDashboard;
import com.mfb.lending.module.reporting.dto.ReportResponses.CreditDashboard;
import com.mfb.lending.module.reporting.dto.ReportResponses.OperationsDashboard;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Staff dashboards")
@SecurityRequirement(name = "bearerAuth")
public class StaffDashboardController {

    private final ReportingService reports;

    public StaffDashboardController(ReportingService reports) {
        this.reports = reports;
    }

    @GetMapping("/operations")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Application pipeline, disbursed loans, outstanding and overdue portfolio, and collection performance")
    public ApiResponse<OperationsDashboard> operations(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.operations(actor, query));
    }

    @GetMapping("/credit")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Approval and rejection rates, turnaround, average loan size, and credit risk distribution")
    public ApiResponse<CreditDashboard> credit(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.credit(actor, query));
    }

    @GetMapping("/collections")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Overdue portfolio, PAR, collection rate, open cases, and recoveries")
    public ApiResponse<CollectionsDashboard> collections(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.collections(actor, query));
    }

    @GetMapping("/branch")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Branch portfolio and application performance within the caller's branch assignment")
    public ApiResponse<BranchDashboard> branch(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.branch(actor, query));
    }
}
