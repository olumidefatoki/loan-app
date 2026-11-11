package com.mfb.lending.module.approval.api;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.approval.application.ApprovalMatrixService;
import com.mfb.lending.module.approval.dto.ApprovalMatrixResponse;
import com.mfb.lending.module.approval.dto.DelegationResponse;
import com.mfb.lending.module.approval.dto.GrantDelegationRequest;
import com.mfb.lending.module.approval.dto.SaveApprovalMatrixRequest;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
@Tag(name = "Approval configuration", description = """
        Approval matrices and delegated limits. A draft version can be edited. Activate publishes it and leaves the previous version readable. \
        Match criteria and stage ceilings belong to the version, so a draft does not change approvals already in flight. \
        Delegated limits are the only approval authority. A user with no active delegation cannot approve.""")
@SecurityRequirement(name = "bearerAuth")
public class ApprovalMatrixAdminController {

    private static final String MANAGE = "hasAuthority('" + PermissionCodes.APPROVAL_MATRIX_MANAGE + "')";

    private final ApprovalMatrixService matrices;

    public ApprovalMatrixAdminController(ApprovalMatrixService matrices) {
        this.matrices = matrices;
    }

    @PostMapping("/approval-matrices")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Create an approval matrix draft")
    public ResponseEntity<ApiResponse<ApprovalMatrixResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody SaveApprovalMatrixRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(matrices.create(request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/approval-matrices")
    @PreAuthorize(MANAGE)
    @Operation(summary = "List approval matrices")
    public ApiResponse<List<ApprovalMatrixResponse>> list() {
        return ApiResponse.success(matrices.list());
    }

    @GetMapping("/approval-matrices/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Get an approval matrix and its versions")
    public ApiResponse<ApprovalMatrixResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(matrices.get(id));
    }

    @PutMapping("/approval-matrices/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Update the draft approval matrix", description = "Edits the draft version. An active matrix keeps its published version until the new draft is activated.")
    public ApiResponse<ApprovalMatrixResponse> update(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody SaveApprovalMatrixRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(matrices.update(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @DeleteMapping("/approval-matrices/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Delete an unused draft matrix")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        matrices.delete(id, actor, ClientContexts.from(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/approval-matrices/{id}/activate")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Activate the draft matrix version", description = "The previously active version stays stored as superseded. Later approvals use the new version. Open approvals keep the version they were opened against.")
    public ApiResponse<ApprovalMatrixResponse> activate(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(matrices.activate(id, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/approval-delegations")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Grant a delegated approval limit", description = "The limit applies to one user, role, and stage. A branch, when set, restricts that limit to the branch. This is the authority ceiling used by approval decisions.")
    public ResponseEntity<ApiResponse<DelegationResponse>> grant(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody GrantDelegationRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(matrices.grant(request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/approval-delegations")
    @PreAuthorize(MANAGE)
    @Operation(summary = "List delegated approval limits", description = "userId limits the list to one user. Omit it to list every delegation.")
    public ApiResponse<List<DelegationResponse>> delegations(@RequestParam(required = false) UUID userId) {
        return ApiResponse.success(matrices.delegations(userId));
    }

    @DeleteMapping("/approval-delegations/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Revoke a delegated approval limit")
    public ResponseEntity<Void> revoke(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        matrices.revoke(id, actor, ClientContexts.from(httpRequest));
        return ResponseEntity.noContent().build();
    }
}
