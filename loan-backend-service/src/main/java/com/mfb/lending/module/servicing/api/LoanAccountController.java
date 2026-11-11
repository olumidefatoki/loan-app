package com.mfb.lending.module.servicing.api;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.servicing.application.LoanServicing;
import com.mfb.lending.module.servicing.application.RestructureCommand;
import com.mfb.lending.module.servicing.application.ServicingAccess;
import com.mfb.lending.module.servicing.calculation.HolidayRule;
import com.mfb.lending.module.servicing.domain.LoanAccount;
import com.mfb.lending.module.servicing.dto.ServicingRequests.InterestAdjustmentRequest;
import com.mfb.lending.module.servicing.dto.ServicingRequests.RestructureRequest;
import com.mfb.lending.module.servicing.dto.ServicingRequests.SettlementQuoteRequest;
import com.mfb.lending.module.servicing.dto.ServicingRequests.SettlementRequest;
import com.mfb.lending.module.servicing.dto.ServicingResponses.BalanceResponse;
import com.mfb.lending.module.servicing.dto.ServicingResponses.LoanAccountResponse;
import com.mfb.lending.module.servicing.dto.ServicingResponses.RepaymentResponse;
import com.mfb.lending.module.servicing.dto.ServicingResponses.ScheduleResponse;
import com.mfb.lending.module.servicing.dto.ServicingResponses.SettlementQuoteResponse;
import com.mfb.lending.module.servicing.dto.ServicingResponses.StatementResponse;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loan-accounts")
@Tag(name = "Loan accounts")
@SecurityRequirement(name = "bearerAuth")
public class LoanAccountController {

    private static final ZoneId BANK = ZoneId.of("Africa/Lagos");

    private final LoanServicing servicing;
    private final ServicingAccess access;
    private final Clock clock;

    public LoanAccountController(LoanServicing servicing, ServicingAccess access, Clock clock) {
        this.servicing = servicing;
        this.access = access;
        this.clock = clock;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List loan accounts visible to the caller")
    public ApiResponse<PageResponse<LoanAccountResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        UUID scope = access.customerScope(actor, customerId);
        LoanServicing.Page result = servicing.list(scope, status, page, size);
        return ApiResponse.success(new PageResponse<>(
                result.items().stream().map(LoanAccountResponse::from).toList(),
                result.page(), result.size(), result.total()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a loan account and its outstanding balances")
    public ApiResponse<LoanAccountResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        LoanAccount account = servicing.get(id);
        access.view(actor, account);
        return ApiResponse.success(LoanAccountResponse.from(account));
    }

    @GetMapping("/{id}/balance")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get the outstanding balance of a loan account")
    public ApiResponse<BalanceResponse> balance(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        LoanAccount account = servicing.get(id);
        access.view(actor, account);
        return ApiResponse.success(BalanceResponse.from(account));
    }

    @GetMapping("/{id}/schedule")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a versioned repayment schedule, including its calculation parameters")
    public ApiResponse<ScheduleResponse> schedule(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @RequestParam(required = false) Integer version) {
        access.view(actor, servicing.get(id));
        return ApiResponse.success(ScheduleResponse.from(servicing.schedule(id, version)));
    }

    @GetMapping("/{id}/statement")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a loan statement, interest breakdown, and transaction history")
    public ApiResponse<StatementResponse> statement(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @RequestParam(required = false) LocalDate from,
            @RequestParam(required = false) LocalDate to) {
        access.view(actor, servicing.get(id));
        return ApiResponse.success(StatementResponse.from(servicing.statement(id, from, to)));
    }

    @PostMapping("/{id}/settlement-quote")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Quote an early or contractual settlement without posting it")
    public ApiResponse<SettlementQuoteResponse> quote(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @RequestBody(required = false) SettlementQuoteRequest request) {
        LoanAccount account = servicing.get(id);
        access.view(actor, account);
        LocalDate valueDate = request == null ? null : request.valueDate();
        LocalDate asOf = valueDate == null ? LocalDate.ofInstant(clock.instant(), BANK) : valueDate;
        return ApiResponse.success(SettlementQuoteResponse.from(account, asOf, servicing.settlementQuote(id, valueDate)));
    }

    @PostMapping("/{id}/settle")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Settle a loan for the quoted amount after core banking confirms the cash")
    public ApiResponse<RepaymentResponse> settle(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody SettlementRequest request) {
        LoanAccount account = servicing.get(id);
        access.repay(actor, account);
        var repayment = servicing.settle(new com.mfb.lending.module.servicing.application.RepaymentCommand(
                id, request.amount(), request.channel(), request.idempotencyKey(), request.valueDate(),
                request.externalReference(), true, actor.getId(), actor.getUsername()));
        return ApiResponse.success(RepaymentResponse.from(repayment, servicing.allocations(repayment.id())));
    }

    @PostMapping("/{id}/interest-adjustments")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Request an interest adjustment. It stays in draft until a different officer approves it")
    public ApiResponse<UUID> adjust(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody InterestAdjustmentRequest request) {
        access.administer(actor, servicing.get(id));
        return ApiResponse.success(servicing.adjustInterest(id, request.amount(), request.reason(), actor.getId(), actor.getUsername()).journalId());
    }

    @PostMapping("/{id}/schedule")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Restructure the remaining principal onto a new versioned schedule")
    public ApiResponse<ScheduleResponse> restructure(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody RestructureRequest request) {
        access.administer(actor, servicing.get(id));
        HolidayRule holiday = request.holidayRule() == null ? HolidayRule.NONE : request.holidayRule();
        servicing.restructure(new RestructureCommand(
                id, request.tenure(), request.interestRate(), request.capitalizeInterest(), request.repaymentDayOfMonth(),
                holiday, request.reason(), request.valueDate(), actor.getId(), actor.getUsername()));
        return ApiResponse.success(ScheduleResponse.from(servicing.schedule(id, null)));
    }
}
