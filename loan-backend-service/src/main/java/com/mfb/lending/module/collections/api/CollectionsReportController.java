package com.mfb.lending.module.collections.api;

import java.time.LocalDate;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.collections.application.CollectionsAccess;
import com.mfb.lending.module.collections.application.CollectionsReportService;
import com.mfb.lending.module.collections.application.DelinquencyService;
import com.mfb.lending.module.collections.calculation.DelinquencyRules;
import com.mfb.lending.module.collections.dto.CollectionResponses.CollectionsReportResponse;
import com.mfb.lending.module.collections.dto.CollectionResponses.ParResponse;
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
@RequestMapping("/api/v1/reports")
@Tag(name = "Collections reports")
@SecurityRequirement(name = "bearerAuth")
public class CollectionsReportController {

    private final DelinquencyService delinquency;
    private final CollectionsReportService reports;
    private final CollectionsAccess access;

    public CollectionsReportController(DelinquencyService delinquency, CollectionsReportService reports, CollectionsAccess access) {
        this.delinquency = delinquency;
        this.reports = reports;
        this.access = access;
    }

    @GetMapping("/par")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Portfolio at Risk for 30, 60, and 90 days", description = DelinquencyRules.PAR_DESCRIPTION)
    public ApiResponse<ParResponse> par(@AuthenticationPrincipal AuthenticatedUser actor, @RequestParam(required = false) LocalDate date) {
        access.viewReports(actor);
        return ApiResponse.success(ParResponse.from(delinquency.par(date)));
    }

    @GetMapping("/collections")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Collections performance, promises, recoveries, and exposure")
    public ApiResponse<CollectionsReportResponse> collections(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) LocalDate date) {
        access.viewReports(actor);
        return ApiResponse.success(CollectionsReportResponse.from(reports.report(date)));
    }
}
