package com.mfb.lending.module.reporting.api;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.reporting.application.ReportingService;
import com.mfb.lending.module.reporting.dto.ReportResponses.DefaultReport;
import com.mfb.lending.module.reporting.dto.ReportResponses.DelinquencyReport;
import com.mfb.lending.module.reporting.dto.ReportResponses.GroupReport;
import com.mfb.lending.module.reporting.dto.ReportResponses.IncomeReport;
import com.mfb.lending.module.reporting.dto.ReportResponses.MoneyReport;
import com.mfb.lending.module.reporting.dto.ReportResponses.ParSummary;
import com.mfb.lending.module.reporting.dto.ReportResponses.PortfolioReport;
import com.mfb.lending.module.reporting.dto.ReportResponses.ReconciliationReport;
import com.mfb.lending.module.reporting.dto.ReportResponses.WriteOffReport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reports")
@Tag(name = "Financial reports")
@SecurityRequirement(name = "bearerAuth")
public class FinancialReportController {

    private final ReportingService reports;

    public FinancialReportController(ReportingService reports) {
        this.reports = reports;
    }

    @GetMapping("/loan-portfolio")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Management lending KPIs, portfolio composition, and product and branch performance")
    public ApiResponse<PortfolioReport> portfolio(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.portfolio(actor, query));
    }

    @GetMapping("/disbursements")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Disbursement report reconciled to general ledger account 1200")
    public ApiResponse<MoneyReport> disbursements(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.disbursements(actor, query));
    }

    @GetMapping("/repayments")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Repayment report reconciled to general ledger account 2100")
    public ApiResponse<MoneyReport> repayments(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.repayments(actor, query));
    }

    @GetMapping("/defaults")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Non-performing loans at 90 days past due")
    public ApiResponse<DefaultReport> defaults(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.defaults(actor, query));
    }

    @GetMapping("/branch-performance")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Branch disbursement, collection, and portfolio performance")
    public ApiResponse<GroupReport> branches(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.branchPerformance(actor, query));
    }

    @GetMapping("/product-performance")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Product disbursement, collection, interest, and portfolio performance")
    public ApiResponse<GroupReport> products(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.productPerformance(actor, query));
    }

    @GetMapping("/reconciliation")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Subledger to general ledger reconciliation")
    public ApiResponse<ReconciliationReport> reconciliation(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.reconciliation(actor, query));
    }

    @GetMapping("/management-par")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Portfolio at Risk calculated from the reporting book")
    public ApiResponse<ParSummary> par(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.par(actor, query));
    }

    @GetMapping("/interest-income")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Interest income reconciled to general ledger account 4200")
    public ApiResponse<IncomeReport> interest(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.interest(actor, query));
    }

    @GetMapping("/fee-income")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Fee income reconciled to general ledger account 4100")
    public ApiResponse<IncomeReport> fees(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.fees(actor, query));
    }

    @GetMapping("/outstanding")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Outstanding loan report")
    public ApiResponse<MoneyReport> outstanding(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.outstanding(actor, query));
    }

    @GetMapping("/maturity")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Loan maturity report")
    public ApiResponse<MoneyReport> maturity(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.maturity(actor, query));
    }

    @GetMapping("/delinquency")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Delinquency buckets and overdue loans")
    public ApiResponse<DelinquencyReport> delinquency(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.delinquency(actor, query));
    }

    @GetMapping("/closures")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Loans closed in the reporting period")
    public ApiResponse<MoneyReport> closures(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.closures(actor, query));
    }

    @GetMapping("/restructured")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Approved loan restructures in the reporting period")
    public ApiResponse<MoneyReport> restructured(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.restructured(actor, query));
    }

    @GetMapping("/write-offs")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Write-off expense, uncollectible cases, and recoveries")
    public ApiResponse<WriteOffReport> writeOffs(@AuthenticationPrincipal AuthenticatedUser actor, ReportQuery query) {
        return ApiResponse.success(reports.writeOffs(actor, query));
    }
}
