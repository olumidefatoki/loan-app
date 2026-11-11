package com.mfb.lending.module.approval.api;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.approval.application.ApprovalService;
import com.mfb.lending.module.approval.dto.ApprovalDecisionRequest;
import com.mfb.lending.module.approval.dto.ApprovalHistoryResponse;
import com.mfb.lending.module.approval.dto.ApprovalResponse;
import com.mfb.lending.module.approval.dto.CreateApprovalRequest;
import com.mfb.lending.module.approval.dto.DelegateApprovalRequest;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.dto.PageResponse;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/approvals")
@Tag(name = "Loan approvals", description = """
        Multi-stage loan approval. The active matrix version selects the stages from the amount, product, segment, branch, \
        risk rating, collateral position, security classification, and exception type. Limits are delegated records, not code. \
        The requester, the originating user, and the analyst who recommended the credit decision cannot decide the same request. \
        Send the version from the latest read. A conflicting decision returns 409. \
        Reading an approval applies expiry and supersedes it when the application amount, revision, or credit result has changed. \
        A committee stage stays open until the configured quorum of distinct approvals is reached. One rejection or return closes it.""")
@SecurityRequirement(name = "bearerAuth")
public class ApprovalController {

    private static final String VIEW =
            "hasAuthority('" + PermissionCodes.APPROVAL_VIEW
                    + "') or hasAuthority('" + PermissionCodes.LOAN_APPROVE
                    + "') or hasAuthority('" + PermissionCodes.LOAN_REJECT
                    + "') or hasAuthority('" + PermissionCodes.LOAN_APPLICATION_VIEW + "')";
    private static final String OPEN =
            "hasAuthority('" + PermissionCodes.LOAN_APPLICATION_CREATE
                    + "') or hasAuthority('" + PermissionCodes.CREDIT_ASSESSMENT_RUN
                    + "') or hasAuthority('" + PermissionCodes.LOAN_APPROVE
                    + "') or hasAuthority('" + PermissionCodes.APPROVAL_VIEW + "')";
    private static final String APPROVE = "hasAuthority('" + PermissionCodes.LOAN_APPROVE + "')";
    private static final String REJECT = "hasAuthority('" + PermissionCodes.LOAN_REJECT + "')";
    private static final String EITHER =
            "hasAuthority('" + PermissionCodes.LOAN_APPROVE
                    + "') or hasAuthority('" + PermissionCodes.LOAN_REJECT + "')";
    private static final String DELEGATE =
            "hasAuthority('" + PermissionCodes.APPROVAL_DELEGATE
                    + "') or hasAuthority('" + PermissionCodes.LOAN_APPROVE + "')";

    private final ApprovalService approvals;

    public ApprovalController(ApprovalService approvals) {
        this.approvals = approvals;
    }

    @PostMapping
    @PreAuthorize(OPEN)
    @Operation(summary = "Request approval", description = "Opens an approval for an application in credit assessment, or completes it when every automated gate is clear. An exception type routes the request to a matrix for that exception and still requires a different checker.")
    public ResponseEntity<ApiResponse<ApprovalResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody CreateApprovalRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                approvals.create(request.applicationId(), request.exceptionType(), request.reason(), actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/my-queue")
    @PreAuthorize(VIEW)
    @Operation(summary = "List the caller's approval queue", description = "Returns open requests in the caller's branches that match the caller's stage role and delegated limit, plus requests assigned to the caller. Makers are left out. Expiry and superseded decisions are applied before the page is returned.")
    public ApiResponse<PageResponse<ApprovalResponse>> queue(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(approvals.queue(actor, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    @Operation(summary = "Get an approval", description = "Returns the current stage, the credit result it was opened against, and the optimistic-lock version. A stale or expired approval is updated before the response is returned.")
    public ApiResponse<ApprovalResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(approvals.get(id, actor));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize(APPROVE)
    @Operation(summary = "Approve the current stage", description = "Records an immutable approval. A stage below the final stage advances the request. A committee stage waits for its quorum. The version must match the latest read.")
    public ApiResponse<ApprovalResponse> approve(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ApprovalDecisionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(approvals.approve(id, request.version(), request.reason(), actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize(REJECT)
    @Operation(summary = "Reject the application", description = "Closes the approval and rejects the application. The decision cannot be rewritten.")
    public ApiResponse<ApprovalResponse> reject(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ApprovalDecisionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(approvals.reject(id, request.version(), request.reason(), actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/return")
    @PreAuthorize(EITHER)
    @Operation(summary = "Return the application for correction", description = "Closes the approval and returns the application to documents pending with correction open.")
    public ApiResponse<ApprovalResponse> returnForCorrection(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ApprovalDecisionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(approvals.returnForCorrection(id, request.version(), request.reason(), actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/request-information")
    @PreAuthorize(EITHER)
    @Operation(summary = "Request additional information", description = "Leaves the approval open and records the information request. The same checker can decide it after the information is supplied, provided the application revision and credit result are unchanged.")
    public ApiResponse<ApprovalResponse> requestInformation(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ApprovalDecisionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(approvals.requestInformation(id, request.version(), request.reason(), actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/delegate")
    @PreAuthorize(DELEGATE)
    @Operation(summary = "Delegate this approval", description = "Assigns the open stage to another user who holds the stage role in the branch. The delegate's limit for this request is capped at the delegator's current limit.")
    public ApiResponse<ApprovalResponse> delegate(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody DelegateApprovalRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(approvals.delegate(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize(VIEW)
    @Operation(summary = "Get approval history", description = "Returns every recorded action with the user, role, stage, decision, amount, reason, timestamp, and correlation id. History rows are append-only.")
    public ApiResponse<List<ApprovalHistoryResponse>> history(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(approvals.history(id, actor));
    }
}
