package com.mfb.lending.module.loanapplication.api;

import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.loanapplication.application.LoanApplicationService;
import com.mfb.lending.module.loanapplication.dto.LoanApplicationResponse;
import com.mfb.lending.module.loanapplication.dto.LoanApplicationSummaryResponse;
import com.mfb.lending.module.loanapplication.dto.TransitionRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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
@RequestMapping("/api/v1/admin/loan-applications")
@Tag(name = "Loan application queue", description = "Staff queue and controlled status transitions. Supports pagination, filtering, and sorting.")
@SecurityRequirement(name = "bearerAuth")
public class LoanApplicationAdminController {

    private static final String QUEUE =
            "hasAuthority('" + PermissionCodes.LOAN_APPLICATION_VIEW
                    + "') or hasAuthority('" + PermissionCodes.LOAN_APPLICATION_CREATE
                    + "') or hasAuthority('" + PermissionCodes.LOAN_APPROVE
                    + "') or hasAuthority('" + PermissionCodes.LOAN_REJECT
                    + "') or hasAuthority('" + PermissionCodes.LOAN_DISBURSE + "')";

    private final LoanApplicationService applications;

    public LoanApplicationAdminController(LoanApplicationService applications) {
        this.applications = applications;
    }

    @GetMapping("/queue")
    @PreAuthorize(QUEUE)
    @Operation(summary = "List the staff application queue", description = "Excludes drafts and closed applications unless status is set. Filter by status, productId, branchId, customerSegment, and q. Sort by submittedAt, updatedAt, amount, or reference.")
    public ApiResponse<PageResponse<LoanApplicationSummaryResponse>> queue(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) UUID productId,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String customerSegment,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String sort,
            @RequestParam(required = false) String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(applications.queue(
                actor, productId, branchId, status, customerSegment, q, sort, direction, page, size));
    }

    @PostMapping("/{id}/transitions")
    @PreAuthorize(QUEUE)
    @Operation(summary = "Move an application to the next status", description = "Only configured transitions are accepted. Rejection, approval, offer issue, and disbursement each require the matching permission. Returning the application to DOCUMENTS_PENDING opens a correction revision.")
    public ApiResponse<LoanApplicationResponse> transition(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody TransitionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(applications.transition(id, request, actor, ClientContexts.from(httpRequest)));
    }
}
