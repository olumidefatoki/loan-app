package com.mfb.lending.module.credit.api;

import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.credit.application.CreditAssessmentService;
import com.mfb.lending.module.credit.dto.BureauCheckResponse;
import com.mfb.lending.module.credit.dto.CommentRequest;
import com.mfb.lending.module.credit.dto.CommentResponse;
import com.mfb.lending.module.credit.dto.CreateCreditAssessmentRequest;
import com.mfb.lending.module.credit.dto.CreditAssessmentResponse;
import com.mfb.lending.module.credit.dto.CreditReportResponse;
import com.mfb.lending.module.credit.dto.RecommendRequest;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/credit-assessments")
@Tag(name = "Credit assessments", description = """
        Credit assessment for an existing loan application. Inputs captured here stay fixed. \
        Each evaluation appends a result that keeps the scoring model version, policy version, and calculation inputs and outputs. \
        Decisions are AUTO_APPROVE, REFER, AUTO_REJECT, PENDING_DATA, or COMPLIANCE_HOLD. \
        A score never overrides a compliance hold or a mandatory policy failure. \
        An analyst approval that would change the decision opens a policy exception. A different officer approves it by sending exceptionId. \
        Bureau checks use the configured provider port and do not call a vendor URL from this API.""")
@SecurityRequirement(name = "bearerAuth")
public class CreditAssessmentController {

    private static final String RUN = "hasAuthority('" + PermissionCodes.CREDIT_ASSESSMENT_RUN + "')";
    private static final String VIEW =
            "hasAuthority('" + PermissionCodes.CREDIT_ASSESSMENT_VIEW
                    + "') or hasAuthority('" + PermissionCodes.CREDIT_ASSESSMENT_RUN + "')";

    private final CreditAssessmentService assessments;

    public CreditAssessmentController(CreditAssessmentService assessments) {
        this.assessments = assessments;
    }

    @PostMapping
    @PreAuthorize(RUN)
    @Operation(summary = "Open a credit assessment", description = "Snapshots individual or SME financial inputs for one open application. A second open assessment for the same application returns 409.")
    public ResponseEntity<ApiResponse<CreditAssessmentResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody CreateCreditAssessmentRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(assessments.create(request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    @Operation(summary = "Get a credit assessment", description = "Returns the preserved inputs, latest calculations, score, model version, policy version, and decision reasons.")
    public ApiResponse<CreditAssessmentResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(assessments.get(id, actor));
    }

    @PostMapping("/{id}/evaluate")
    @PreAuthorize(RUN)
    @Operation(summary = "Evaluate a credit assessment", description = "Runs the active scoring model and policy rules. The result is appended. Earlier results stay readable on the report.")
    public ApiResponse<CreditAssessmentResponse> evaluate(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(assessments.evaluate(id, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/bureau-check")
    @PreAuthorize(RUN)
    @Operation(summary = "Request a credit bureau check", description = "Identifies the customer, requests an enquiry, retrieves the mapped report, and records exposure. No bureau vendor endpoint is called unless a deployment supplies its own provider adapter.")
    public ApiResponse<BureauCheckResponse> bureau(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(assessments.bureauCheck(id, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/recommend")
    @PreAuthorize(RUN)
    @Operation(summary = "Submit an analyst recommendation", description = "APPROVE against a policy failure opens a pending exception and leaves the decision unchanged until another officer sends the same endpoint with exceptionId. REQUEST_INFORMATION moves a non-compliance decision to PENDING_DATA. The requester cannot approve their own exception.")
    public ApiResponse<CreditAssessmentResponse> recommend(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody RecommendRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(assessments.recommend(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/comments")
    @PreAuthorize(RUN)
    @Operation(summary = "Add an assessment comment")
    public ResponseEntity<ApiResponse<CommentResponse>> comment(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody CommentRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(assessments.comment(id, request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/{id}/report")
    @PreAuthorize(VIEW)
    @Operation(summary = "Get the credit assessment report", description = "Returns the current decision, score components, every evaluation, bureau history, comments, and policy exceptions.")
    public ApiResponse<CreditReportResponse> report(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(assessments.report(id, actor));
    }
}
