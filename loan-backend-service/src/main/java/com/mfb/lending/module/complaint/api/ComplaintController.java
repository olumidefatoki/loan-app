package com.mfb.lending.module.complaint.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.complaint.application.ComplaintService;
import com.mfb.lending.module.complaint.application.ComplaintService.ComplaintDetail;
import com.mfb.lending.module.complaint.domain.ComplaintModels.Complaint;
import com.mfb.lending.module.complaint.domain.ComplaintModels.ComplaintActivity;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
@RequestMapping("/api/v1/complaints")
@Tag(name = "Complaints", description = "Customer complaints, assignment, SLA escalation, and resolution history.")
@SecurityRequirement(name = "bearerAuth")
public class ComplaintController {

    private final ComplaintService complaints;

    public ComplaintController(ComplaintService complaints) {
        this.complaints = complaints;
    }

    @PostMapping
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Submit a complaint")
    public ApiResponse<ComplaintResponse> submit(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody SubmitComplaintRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(ComplaintResponse.from(complaints.submit(
                actor, request.category(), request.subject(), request.description(), ClientContexts.from(httpRequest))));
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List complaints visible to the caller")
    public ApiResponse<PageResponse<ComplaintResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageResponse<Complaint> result = complaints.list(actor, status, page, size);
        return ApiResponse.success(new PageResponse<>(result.items().stream().map(ComplaintResponse::from).toList(), result.page(), result.size(), result.total()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a complaint and its response history")
    public ApiResponse<ComplaintDetailResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(ComplaintDetailResponse.from(complaints.get(actor, id)));
    }

    @PostMapping("/{id}/activities")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Add a complaint response, assignment, escalation, or resolution")
    public ApiResponse<ActivityResponse> activity(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ActivityRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(ActivityResponse.from(complaints.addActivity(
                actor, id, request.activityType(), request.note(), request.assigneeId(), ClientContexts.from(httpRequest))));
    }

    public record SubmitComplaintRequest(
            @NotBlank @Size(max = 32) String category,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 2000) String description) {
    }

    public record ActivityRequest(
            @Size(max = 32) String activityType,
            @NotBlank @Size(max = 2000) String note,
            UUID assigneeId) {
    }

    public record ComplaintResponse(
            UUID id, String reference, UUID customerId, UUID branchId, String category, String status, String subject,
            UUID assigneeId, Instant slaDueAt, Instant escalatedAt, Instant resolvedAt, String resolution, Instant createdAt) {
        static ComplaintResponse from(Complaint complaint) {
            return new ComplaintResponse(
                    complaint.id(), complaint.reference(), complaint.customerId(), complaint.branchId(), complaint.category(),
                    complaint.status(), complaint.subject(), complaint.assigneeId(), complaint.slaDueAt(), complaint.escalatedAt(),
                    complaint.resolvedAt(), complaint.resolution(), complaint.createdAt());
        }
    }

    public record ActivityResponse(UUID id, String activityType, String note, String actorName, Instant createdAt) {
        static ActivityResponse from(ComplaintActivity activity) {
            return new ActivityResponse(activity.id(), activity.activityType(), activity.note(), activity.actorName(), activity.createdAt());
        }
    }

    public record ComplaintDetailResponse(ComplaintResponse complaint, List<ActivityResponse> activities) {
        static ComplaintDetailResponse from(ComplaintDetail detail) {
            return new ComplaintDetailResponse(
                    ComplaintResponse.from(detail.complaint()),
                    detail.activities().stream().map(ActivityResponse::from).toList());
        }
    }
}
