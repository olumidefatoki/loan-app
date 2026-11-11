package com.mfb.lending.module.servicing.api;

import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.servicing.application.LoanServicing;
import com.mfb.lending.module.servicing.application.ServicingAccess;
import com.mfb.lending.module.servicing.dto.ServicingResponses.JournalResponse;
import com.mfb.lending.module.servicing.dto.ServicingResponses.ReconciliationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/accounting")
@Tag(name = "Accounting")
@SecurityRequirement(name = "bearerAuth")
public class AccountingAdminController {

    private final LoanServicing servicing;
    private final ServicingAccess access;

    public AccountingAdminController(LoanServicing servicing, ServicingAccess access) {
        this.servicing = servicing;
        this.access = access;
    }

    @GetMapping("/journals")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List journals. Posted journals are immutable")
    public ApiResponse<PageResponse<JournalResponse>> journals(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        access.viewJournals(actor);
        var headers = servicing.journals(page, size);
        return ApiResponse.success(new PageResponse<>(
                headers.stream().map(header -> JournalResponse.from(header, servicing.journalLines(header.id()))).toList(),
                page, size, servicing.journalCount()));
    }

    @GetMapping("/journals/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a journal and its lines")
    public ApiResponse<JournalResponse> journal(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        access.viewJournals(actor);
        return ApiResponse.success(JournalResponse.from(servicing.journal(id), servicing.journalLines(id)));
    }

    @PostMapping("/journals/{id}/approve")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Approve a draft journal. The approver must be a different officer from the requester")
    public ApiResponse<JournalResponse> approve(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        access.approve(actor);
        var header = servicing.approveJournal(id, actor.getId());
        return ApiResponse.success(JournalResponse.from(header, servicing.journalLines(id)));
    }

    @GetMapping("/reconciliation")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Reconcile confirmed servicing collections with core banking cash")
    public ApiResponse<ReconciliationResponse> reconciliation(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) UUID loanAccountId) {
        access.viewJournals(actor);
        if (loanAccountId != null) {
            access.view(actor, servicing.get(loanAccountId));
        }
        return ApiResponse.success(ReconciliationResponse.from(servicing.reconcile(loanAccountId)));
    }
}
