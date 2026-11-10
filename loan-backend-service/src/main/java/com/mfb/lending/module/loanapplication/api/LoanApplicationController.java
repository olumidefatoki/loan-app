package com.mfb.lending.module.loanapplication.api;

import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.domain.ClientChannel;
import com.mfb.lending.module.loanapplication.application.LoanApplicationDocumentService;
import com.mfb.lending.module.loanapplication.application.LoanApplicationService;
import com.mfb.lending.module.loanapplication.dto.ApplicationDashboardResponse;
import com.mfb.lending.module.loanapplication.dto.ApplicationDocumentResponse;
import com.mfb.lending.module.loanapplication.dto.ApplicationHistoryResponse;
import com.mfb.lending.module.loanapplication.dto.LoanApplicationResponse;
import com.mfb.lending.module.loanapplication.dto.LoanApplicationSummaryResponse;
import com.mfb.lending.module.loanapplication.dto.SaveLoanApplicationRequest;
import com.mfb.lending.module.loanapplication.dto.WithdrawRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/loan-applications")
@Tag(name = "Loan applications", description = """
        Loan origination for individual and SME customers. React and Flutter use the same endpoints. \
        Send Authorization: Bearer and optional X-Client-Channel WEB or MOBILE. \
        Save a draft at each step, upload the product's required documents, then submit. \
        A submitted application stays locked until staff return it for correction. \
        Customers can read only their own applications.""")
@SecurityRequirement(name = "bearerAuth")
public class LoanApplicationController {

    private static final String APPLY =
            "hasRole('CUSTOMER') or hasAuthority('" + PermissionCodes.LOAN_APPLICATION_CREATE + "')";
    private static final String READ =
            "hasRole('CUSTOMER') or hasAuthority('" + PermissionCodes.LOAN_APPLICATION_VIEW
                    + "') or hasAuthority('" + PermissionCodes.LOAN_APPLICATION_CREATE
                    + "') or hasAuthority('" + PermissionCodes.LOAN_APPROVE
                    + "') or hasAuthority('" + PermissionCodes.LOAN_REJECT
                    + "') or hasAuthority('" + PermissionCodes.LOAN_DISBURSE + "')";

    private final LoanApplicationService applications;
    private final LoanApplicationDocumentService documents;

    public LoanApplicationController(LoanApplicationService applications, LoanApplicationDocumentService documents) {
        this.applications = applications;
        this.documents = documents;
    }

    @PostMapping
    @PreAuthorize(APPLY)
    @Operation(summary = "Start a loan application draft", description = "Creates a draft pinned to the active product version. Individual customers send individualFinancials. SME customers send smeFinancials.")
    public ResponseEntity<ApiResponse<LoanApplicationResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody SaveLoanApplicationRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(applications.create(request, actor, ClientContexts.from(httpRequest), channel(httpRequest))));
    }

    @GetMapping
    @PreAuthorize(READ)
    @Operation(summary = "List visible loan applications", description = "Customers see their own applications. Staff see applications in assigned branches. Supports status, productId, branchId, customerSegment, q, sort, and direction.")
    public ApiResponse<PageResponse<LoanApplicationSummaryResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String customerSegment,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(applications.list(
                actor, customerId, productId, branchId, status, customerSegment, q, sort, direction, page, size));
    }

    @GetMapping("/my-applications")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "List the signed-in customer's applications")
    public ApiResponse<PageResponse<LoanApplicationSummaryResponse>> mine(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(applications.myApplications(actor, status, sort, direction, page, size));
    }

    @GetMapping("/summary")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Customer application dashboard", description = "Counts drafts, applications in progress, offers, approvals, disbursements, and closed applications, plus the requested amount still on book.")
    public ApiResponse<ApplicationDashboardResponse> summary(@AuthenticationPrincipal AuthenticatedUser actor) {
        return ApiResponse.success(applications.dashboard(actor));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    @Operation(summary = "Get a loan application", description = "Returns the saved draft, including the financial section that matches the customer and the product document checklist.")
    public ApiResponse<LoanApplicationResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(applications.get(id, actor));
    }

    @PutMapping("/{id}")
    @PreAuthorize(APPLY)
    @Operation(summary = "Update a draft or a returned application", description = "Omitted sections are left unchanged. A material change after the application was returned for correction creates a new revision. Submitted applications cannot be edited freely.")
    public ApiResponse<LoanApplicationResponse> update(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody SaveLoanApplicationRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(applications.update(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize(APPLY)
    @Operation(summary = "Submit an application", description = "Validates product limits, the segment-specific financials, guarantors, collateral, and required documents. Resubmission is allowed after staff return the application for correction.")
    public ApiResponse<LoanApplicationResponse> submit(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(applications.submit(id, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/withdraw")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Withdraw an application", description = "The customer can withdraw until the application reaches a terminal or post-offer state that no longer allows withdrawal.")
    public ApiResponse<LoanApplicationResponse> withdraw(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody WithdrawRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(applications.withdraw(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/accept-offer")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Accept an issued offer")
    public ApiResponse<LoanApplicationResponse> accept(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(applications.acceptOffer(id, actor, ClientContexts.from(httpRequest)));
    }

    @GetMapping("/{id}/history")
    @PreAuthorize(READ)
    @Operation(summary = "Get application status history and events")
    public ApiResponse<ApplicationHistoryResponse> history(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(applications.history(id, actor));
    }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(APPLY)
    @Operation(summary = "Upload a supporting document", description = "Accepts PDF, JPEG, and PNG. documentCode must be one of the product's document codes when the product defines them. Replacing a file keeps the previous upload.")
    public ResponseEntity<ApiResponse<ApplicationDocumentResponse>> upload(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @RequestParam String documentCode,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                documents.upload(id, documentCode, file, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/{id}/documents")
    @PreAuthorize(READ)
    @Operation(summary = "List current application documents")
    public ApiResponse<java.util.List<ApplicationDocumentResponse>> documents(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id) {
        return ApiResponse.success(documents.list(id, actor));
    }

    private static ClientChannel channel(HttpServletRequest request) {
        return ClientChannel.parse(request.getHeader("X-Client-Channel"));
    }
}
