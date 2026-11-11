package com.mfb.lending.module.credit.api;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.credit.application.CreditAssessmentService;
import com.mfb.lending.module.credit.dto.CreditQueueItemResponse;
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
@RequestMapping("/api/v1/admin/credit-assessments")
@Tag(name = "Credit assessment queue", description = "Work queue for referred, pending-data, compliance-hold, and newly opened assessments assigned to the officer or still unassigned.")
@SecurityRequirement(name = "bearerAuth")
public class CreditAssessmentAdminController {

    private static final String VIEW =
            "hasAuthority('" + PermissionCodes.CREDIT_ASSESSMENT_VIEW
                    + "') or hasAuthority('" + PermissionCodes.CREDIT_ASSESSMENT_RUN + "')";

    private final CreditAssessmentService assessments;

    public CreditAssessmentAdminController(CreditAssessmentService assessments) {
        this.assessments = assessments;
    }

    @GetMapping("/queue")
    @PreAuthorize(VIEW)
    @Operation(summary = "List assigned credit assessments", description = "Defaults to the caller's assignments plus unassigned work in the caller's branches. decision filters one outcome. includeUnassigned=false returns only the caller's assignments.")
    public ApiResponse<PageResponse<CreditQueueItemResponse>> queue(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String decision,
            @RequestParam(defaultValue = "true") boolean includeUnassigned,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(assessments.queue(actor, decision, includeUnassigned, page, size));
    }
}
