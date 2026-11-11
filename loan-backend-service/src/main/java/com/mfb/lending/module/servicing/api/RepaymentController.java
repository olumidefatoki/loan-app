package com.mfb.lending.module.servicing.api;

import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.servicing.application.LoanServicing;
import com.mfb.lending.module.servicing.application.RepaymentCommand;
import com.mfb.lending.module.servicing.application.ServicingAccess;
import com.mfb.lending.module.servicing.dto.ServicingRequests.RepaymentRequest;
import com.mfb.lending.module.servicing.dto.ServicingRequests.ReversalRequest;
import com.mfb.lending.module.servicing.dto.ServicingResponses.RepaymentResponse;
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
@RequestMapping("/api/v1/repayments")
@Tag(name = "Repayments")
@SecurityRequirement(name = "bearerAuth")
public class RepaymentController {

    private final LoanServicing servicing;
    private final ServicingAccess access;

    public RepaymentController(LoanServicing servicing, ServicingAccess access) {
        this.servicing = servicing;
        this.access = access;
    }

    @PostMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Collect a repayment. The loan balance changes only after core banking confirms the cash")
    public ApiResponse<RepaymentResponse> repay(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody RepaymentRequest request) {
        access.repay(actor, servicing.get(request.loanAccountId()));
        var repayment = servicing.repay(new RepaymentCommand(
                request.loanAccountId(), request.amount(), request.channel(), request.idempotencyKey(),
                request.valueDate(), request.externalReference(), false, actor.getId(), actor.getUsername()));
        return ApiResponse.success(RepaymentResponse.from(repayment, servicing.allocations(repayment.id())));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a repayment receipt and its allocation")
    public ApiResponse<RepaymentResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        var repayment = servicing.repayment(id);
        access.view(actor, servicing.get(repayment.loanAccountId()));
        return ApiResponse.success(RepaymentResponse.from(repayment, servicing.allocations(id)));
    }

    @PostMapping("/{id}/reversal")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Reverse a posted repayment after core banking confirms the reversal")
    public ApiResponse<RepaymentResponse> reverse(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ReversalRequest request) {
        var current = servicing.repayment(id);
        access.repay(actor, servicing.get(current.loanAccountId()));
        var repayment = servicing.reverse(id, request.idempotencyKey(), actor.getId(), actor.getUsername());
        return ApiResponse.success(RepaymentResponse.from(repayment, servicing.allocations(repayment.id())));
    }
}
