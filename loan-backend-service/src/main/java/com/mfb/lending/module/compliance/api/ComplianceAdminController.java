package com.mfb.lending.module.compliance.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.compliance.application.ComplianceService;
import com.mfb.lending.module.compliance.application.ComplianceService.CaseDetail;
import com.mfb.lending.module.compliance.domain.ComplianceModels.CaseActivity;
import com.mfb.lending.module.compliance.domain.ComplianceModels.ComplianceAlert;
import com.mfb.lending.module.compliance.domain.ComplianceModels.ComplianceCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/compliance")
@Tag(name = "Compliance", description = "Sanctions, PEP, and AML review. A potential sanctions match stays open until an officer records an investigation note and a reviewer decides.")
@SecurityRequirement(name = "bearerAuth")
public class ComplianceAdminController {

    private final ComplianceService compliance;

    public ComplianceAdminController(ComplianceService compliance) {
        this.compliance = compliance;
    }

    @GetMapping("/alerts")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List compliance alerts")
    public ApiResponse<PageResponse<AlertResponse>> alerts(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String alertType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<ComplianceAlert> result = compliance.alerts(actor, status, alertType, page, size);
        return ApiResponse.success(new PageResponse<>(result.items().stream().map(AlertResponse::from).toList(), result.page(), result.size(), result.total()));
    }

    @GetMapping("/cases")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List compliance cases")
    public ApiResponse<PageResponse<CaseResponse>> cases(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String caseType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<ComplianceCase> result = compliance.cases(actor, status, caseType, page, size);
        return ApiResponse.success(new PageResponse<>(result.items().stream().map(CaseResponse::from).toList(), result.page(), result.size(), result.total()));
    }

    @GetMapping("/cases/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a compliance case and its investigation history")
    public ApiResponse<CaseDetailResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(CaseDetailResponse.from(compliance.get(actor, id)));
    }

    @PostMapping("/cases/{id}/assign")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Assign a compliance case")
    public ApiResponse<CaseResponse> assign(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody AssignRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(CaseResponse.from(compliance.assign(actor, id, request.assigneeId(), ClientContexts.from(httpRequest))));
    }

    @PostMapping("/cases/{id}/activities")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Record an investigation note. Notes are retained with the case.")
    public ApiResponse<ActivityResponse> note(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody NoteRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(ActivityResponse.from(compliance.note(actor, id, request.note(), ClientContexts.from(httpRequest))));
    }

    @PostMapping("/cases/{id}/escalate")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Escalate a compliance case")
    public ApiResponse<CaseResponse> escalate(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ReasonRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(CaseResponse.from(compliance.escalate(actor, id, request.reason(), ClientContexts.from(httpRequest))));
    }

    @PostMapping("/cases/{id}/resolve")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Clear or reject a compliance case after review. Sanctions matches are not cleared without an investigation note.")
    public ApiResponse<CaseResponse> resolve(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ResolveRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(CaseResponse.from(compliance.resolve(
                actor, id, request.decision(), request.reason(), request.riskClassification(), ClientContexts.from(httpRequest))));
    }

    public record AssignRequest(@NotNull UUID assigneeId) {
    }

    public record NoteRequest(@NotBlank @Size(max = 2000) String note) {
    }

    public record ReasonRequest(@NotBlank @Size(max = 2000) String reason) {
    }

    public record ResolveRequest(
            @NotBlank @Size(max = 16) String decision,
            @NotBlank @Size(max = 2000) String reason,
            @Size(max = 16) String riskClassification) {
    }

    public record AlertResponse(UUID id, UUID customerId, String alertType, String severity, String status, String summary, Instant openedAt) {
        static AlertResponse from(ComplianceAlert alert) {
            return new AlertResponse(alert.id(), alert.customerId(), alert.alertType(), alert.severity(), alert.status(), alert.summary(), alert.openedAt());
        }
    }

    public record CaseResponse(
            UUID id, String reference, UUID customerId, UUID alertId, String caseType, String status, String riskClassification,
            UUID assigneeId, String summary, String decision, Instant evidenceRetainedUntil, Instant openedAt) {
        static CaseResponse from(ComplianceCase complianceCase) {
            return new CaseResponse(
                    complianceCase.id(), complianceCase.reference(), complianceCase.customerId(), complianceCase.alertId(),
                    complianceCase.caseType(), complianceCase.status(), complianceCase.riskClassification(), complianceCase.assigneeId(),
                    complianceCase.summary(), complianceCase.decision(), complianceCase.evidenceRetainedUntil(), complianceCase.openedAt());
        }
    }

    public record ActivityResponse(UUID id, String activityType, String note, String actorName, Instant createdAt) {
        static ActivityResponse from(CaseActivity activity) {
            return new ActivityResponse(activity.id(), activity.activityType(), activity.note(), activity.actorName(), activity.createdAt());
        }
    }

    public record CaseDetailResponse(CaseResponse complianceCase, List<ActivityResponse> activities) {
        static CaseDetailResponse from(CaseDetail detail) {
            return new CaseDetailResponse(CaseResponse.from(detail.complianceCase()), detail.activities().stream().map(ActivityResponse::from).toList());
        }
    }
}
