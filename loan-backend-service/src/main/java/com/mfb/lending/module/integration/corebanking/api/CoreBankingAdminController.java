package com.mfb.lending.module.integration.corebanking.api;

import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.integration.corebanking.CoreBankingHealthService;
import com.mfb.lending.module.integration.corebanking.CoreDisbursementService;
import com.mfb.lending.module.integration.corebanking.CoreRepaymentService;
import com.mfb.lending.module.integration.corebanking.CoreTransactionEnquiryService;
import com.mfb.lending.module.integration.corebanking.DisbursementInstruction;
import com.mfb.lending.module.integration.corebanking.RepaymentInstruction;
import com.mfb.lending.module.integration.corebanking.dto.CoreBankingHealthResponse;
import com.mfb.lending.module.integration.corebanking.dto.CoreTransactionResponse;
import com.mfb.lending.module.integration.corebanking.dto.DisburseInstructionRequest;
import com.mfb.lending.module.integration.corebanking.dto.ReconciliationResponse;
import com.mfb.lending.module.integration.corebanking.dto.RepaymentInstructionRequest;
import com.mfb.lending.module.integration.corebanking.dto.ReversalInstructionRequest;
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
@RequestMapping("/api/v1/admin/integrations/core-banking")
@Tag(name = "Core banking integration", description = """
        Operational access to the core banking adapter. These endpoints do not call a vendor URL unless the HTTP provider \
        and a path for that operation have been configured. The mock provider never posts to a bank. \
        A financial timeout stays UNKNOWN until enquiry resolves it. Uncertain instructions are not retried.""")
@SecurityRequirement(name = "bearerAuth")
public class CoreBankingAdminController {

    private static final String VIEW =
            "hasAuthority('" + PermissionCodes.INTEGRATION_VIEW
                    + "') or hasAuthority('" + PermissionCodes.SYSTEM_CONFIGURE
                    + "') or hasAuthority('" + PermissionCodes.REPORT_VIEW + "')";
    private static final String OPERATE =
            "hasAuthority('" + PermissionCodes.INTEGRATION_OPERATE
                    + "') or hasAuthority('" + PermissionCodes.SYSTEM_CONFIGURE + "')";
    private static final String DISBURSE =
            "hasAuthority('" + PermissionCodes.LOAN_DISBURSE
                    + "') or hasAuthority('" + PermissionCodes.INTEGRATION_OPERATE + "')";
    private static final String REPAY =
            "hasAuthority('" + PermissionCodes.REPAYMENT_PROCESS
                    + "') or hasAuthority('" + PermissionCodes.INTEGRATION_OPERATE + "')";

    private final CoreBankingHealthService health;
    private final CoreTransactionEnquiryService transactions;
    private final CoreDisbursementService disbursements;
    private final CoreRepaymentService repayments;

    public CoreBankingAdminController(
            CoreBankingHealthService health,
            CoreTransactionEnquiryService transactions,
            CoreDisbursementService disbursements,
            CoreRepaymentService repayments) {
        this.health = health;
        this.transactions = transactions;
        this.disbursements = disbursements;
        this.repayments = repayments;
    }

    @GetMapping("/health")
    @PreAuthorize(VIEW)
    @Operation(summary = "Core banking integration health", description = "Reports the selected provider, circuit position, and local backlog. It does not call a core banking endpoint.")
    public ApiResponse<CoreBankingHealthResponse> health() {
        return ApiResponse.success(CoreBankingResponses.health(health.health()));
    }

    @GetMapping("/transactions")
    @PreAuthorize(VIEW)
    @Operation(summary = "List core banking transactions", description = "Returns local financial instructions. Filter by state or kind.")
    public ApiResponse<PageResponse<CoreTransactionResponse>> list(
            @RequestParam(required = false) String state,
            @RequestParam(required = false) String kind,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var result = transactions.list(state, kind, page, size);
        return ApiResponse.success(new PageResponse<>(
                result.items().stream().map(CoreBankingResponses::transaction).toList(),
                result.page(),
                result.size(),
                result.total()));
    }

    @GetMapping("/transactions/{id}")
    @PreAuthorize(VIEW)
    @Operation(summary = "Get a core banking transaction", description = "Returns the local instruction, its state, and the opaque provider reference when one has been recorded.")
    public ApiResponse<CoreTransactionResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(CoreBankingResponses.transaction(transactions.get(id)));
    }

    @PostMapping("/transactions/{id}/enquiry")
    @PreAuthorize(OPERATE)
    @Operation(summary = "Enquire on a core banking transaction", description = "Resolves UNKNOWN and SUBMITTED instructions by asking the adapter for the posting. It does not submit the instruction again.")
    public ApiResponse<CoreTransactionResponse> enquire(@PathVariable UUID id) {
        return ApiResponse.success(CoreBankingResponses.transaction(transactions.enquire(id)));
    }

    @PostMapping("/transactions/{id}/reconcile")
    @PreAuthorize(OPERATE)
    @Operation(summary = "Reconcile a core banking transaction", description = "Compares the local amount with adapter posting and GL information. A mismatch moves a confirmed transaction to RECONCILIATION_REQUIRED.")
    public ApiResponse<ReconciliationResponse> reconcile(@PathVariable UUID id) {
        return ApiResponse.success(CoreBankingResponses.reconciliation(transactions.reconcile(id)));
    }

    @PostMapping("/disbursements")
    @PreAuthorize(DISBURSE)
    @Operation(summary = "Submit an authorised disbursement", description = "Accepts a loan in PENDING_DISBURSEMENT, records a local pending instruction and outbox event, then submits it once through the core banking adapter. The loan becomes DISBURSED only after the posting is confirmed.")
    public ResponseEntity<ApiResponse<CoreTransactionResponse>> disburse(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody DisburseInstructionRequest request,
            HttpServletRequest httpRequest) {
        var transaction = disbursements.disburse(new DisbursementInstruction(
                request.applicationId(),
                request.idempotencyKey(),
                request.amount(),
                request.accountId(),
                actor.getId(),
                actor.getUsername(),
                null), ClientContexts.from(httpRequest));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(CoreBankingResponses.transaction(transaction)));
    }

    @PostMapping("/repayments")
    @PreAuthorize(REPAY)
    @Operation(summary = "Submit a repayment posting", description = "Records the repayment locally, submits it once, and allocates it only after the adapter confirms the posting.")
    public ResponseEntity<ApiResponse<CoreTransactionResponse>> repay(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody RepaymentInstructionRequest request,
            HttpServletRequest httpRequest) {
        var transaction = repayments.repay(new RepaymentInstruction(
                request.applicationId(),
                request.idempotencyKey(),
                request.amount(),
                null,
                actor.getId(),
                actor.getUsername(),
                null), ClientContexts.from(httpRequest));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(CoreBankingResponses.transaction(transaction)));
    }

    @PostMapping("/transactions/{id}/reversal")
    @PreAuthorize(REPAY)
    @Operation(summary = "Reverse a confirmed repayment", description = "Submits one reversal for a confirmed repayment. The original repayment stays successful until the reversal posting is confirmed. A timed-out reversal is not retried.")
    public ApiResponse<CoreTransactionResponse> reverse(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ReversalInstructionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(CoreBankingResponses.transaction(repayments.reverse(
                id, request.idempotencyKey(), actor.getId(), actor.getUsername(), ClientContexts.from(httpRequest))));
    }
}
