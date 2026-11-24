package com.mfb.lending.module.collections.api;

import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.collections.application.CollectionsAccess;
import com.mfb.lending.module.collections.application.RestructuringService;
import com.mfb.lending.module.collections.application.ServicingExposureSource;
import com.mfb.lending.module.collections.domain.CollectionModels.LoanRestructuring;
import com.mfb.lending.module.collections.dto.CollectionRequests.DecisionRequest;
import com.mfb.lending.module.collections.dto.CollectionRequests.RejectRequest;
import com.mfb.lending.module.collections.dto.CollectionResponses.RestructuringResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/restructuring")
@Tag(name = "Loan restructuring")
@SecurityRequirement(name = "bearerAuth")
public class RestructuringAdminController {

    private final RestructuringService restructuring;
    private final ServicingExposureSource exposures;
    private final CollectionsAccess access;

    public RestructuringAdminController(RestructuringService restructuring, ServicingExposureSource exposures, CollectionsAccess access) {
        this.restructuring = restructuring;
        this.exposures = exposures;
        this.access = access;
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Approve a restructuring. The previous schedule is kept, accrued amounts stay, and core cash is reconciled")
    public ApiResponse<RestructuringResponse> approve(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @RequestBody(required = false) DecisionRequest request) {
        LoanRestructuring current = restructuring.get(id);
        access.decideRestructuring(actor, exposures.require(current.loanAccountId()));
        LoanRestructuring decided = restructuring.approve(id, actor.getId(), actor.getUsername(), request == null ? null : request.reason());
        return ApiResponse.success(RestructuringResponse.from(
                decided, restructuring.approval(decided.id()), restructuring.scheduleLinks(decided.id()).stream().findFirst().orElse(null)));
    }

    @PostMapping("/{id}/reject")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Reject a restructuring without changing the repayment schedule or accrued amounts")
    public ApiResponse<RestructuringResponse> reject(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody RejectRequest request) {
        LoanRestructuring current = restructuring.get(id);
        access.decideRestructuring(actor, exposures.require(current.loanAccountId()));
        LoanRestructuring decided = restructuring.reject(id, actor.getId(), actor.getUsername(), request.reason());
        return ApiResponse.success(RestructuringResponse.from(decided, restructuring.approval(decided.id()), null));
    }
}
