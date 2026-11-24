package com.mfb.lending.module.collections.api;

import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.collections.application.CollectionsAccess;
import com.mfb.lending.module.collections.application.RestructuringService;
import com.mfb.lending.module.collections.application.ServicingExposureSource;
import com.mfb.lending.module.collections.domain.CollectionModels.LoanRestructuring;
import com.mfb.lending.module.collections.dto.CollectionRequests.RestructuringRequest;
import com.mfb.lending.module.collections.dto.CollectionResponses.RestructuringResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loan-accounts")
@Tag(name = "Loan restructuring")
@SecurityRequirement(name = "bearerAuth")
public class LoanRestructuringController {

    private final RestructuringService restructuring;
    private final ServicingExposureSource exposures;
    private final CollectionsAccess access;

    public LoanRestructuringController(RestructuringService restructuring, ServicingExposureSource exposures, CollectionsAccess access) {
        this.restructuring = restructuring;
        this.exposures = exposures;
        this.access = access;
    }

    @PostMapping("/{id}/restructure")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Request a loan restructuring. It stays pending until a different authorised officer approves it")
    public ApiResponse<RestructuringResponse> restructure(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody RestructuringRequest request) {
        access.requestRestructuring(actor, exposures.require(id));
        LoanRestructuring created = restructuring.request(id, new com.mfb.lending.module.collections.application.RestructuringService.RestructuringRequest(
                request.type(), request.justification(), request.tenure(), request.interestRate(), request.repaymentFrequency(),
                request.repaymentDayOfMonth(), request.holidayRule(), request.revisedInstalment(), request.moratoriumDays(),
                request.partialSettlementAmount(), request.revisedMaturity(), request.valueDate(), actor.getId(), actor.getUsername()));
        return ApiResponse.success(view(created));
    }

    @GetMapping("/{id}/restructuring-history")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List restructuring requests and the delinquency position retained with each decision")
    public ApiResponse<java.util.List<RestructuringResponse>> history(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        access.viewRestructuring(actor, exposures.require(id));
        return ApiResponse.success(restructuring.history(id).stream().map(this::view).toList());
    }

    private RestructuringResponse view(LoanRestructuring item) {
        return RestructuringResponse.from(
                item,
                restructuring.approval(item.id()),
                restructuring.scheduleLinks(item.id()).stream().findFirst().orElse(null));
    }
}
