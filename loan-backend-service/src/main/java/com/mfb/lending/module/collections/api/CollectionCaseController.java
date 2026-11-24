package com.mfb.lending.module.collections.api;

import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.collections.application.CollectionCaseService;
import com.mfb.lending.module.collections.application.CollectionsAccess;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionCase;
import com.mfb.lending.module.collections.dto.CollectionRequests.ActivityRequest;
import com.mfb.lending.module.collections.dto.CollectionRequests.AssignRequest;
import com.mfb.lending.module.collections.dto.CollectionRequests.CloseRequest;
import com.mfb.lending.module.collections.dto.CollectionRequests.EscalateRequest;
import com.mfb.lending.module.collections.dto.CollectionRequests.PromiseRequest;
import com.mfb.lending.module.collections.dto.CollectionRequests.RecoveryRequest;
import com.mfb.lending.module.collections.dto.CollectionResponses.ActivityResponse;
import com.mfb.lending.module.collections.dto.CollectionResponses.CaseDetailResponse;
import com.mfb.lending.module.collections.dto.CollectionResponses.CaseResponse;
import com.mfb.lending.module.collections.dto.CollectionResponses.EscalationResponse;
import com.mfb.lending.module.collections.dto.CollectionResponses.PromiseResponse;
import com.mfb.lending.module.collections.dto.CollectionResponses.RecoveryResponse;
import com.mfb.lending.module.loanapplication.application.ApplicationAccess;
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
@RequestMapping("/api/v1/collections/cases")
@Tag(name = "Collections")
@SecurityRequirement(name = "bearerAuth")
public class CollectionCaseController {

    private final CollectionCaseService cases;
    private final CollectionsAccess access;

    public CollectionCaseController(CollectionCaseService cases, CollectionsAccess access) {
        this.cases = cases;
        this.access = access;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List collection cases visible to the caller")
    public ApiResponse<PageResponse<CaseResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        CollectionCaseService.Page result = cases.list(access.caseBranches(actor, branchId), status, page, size);
        return ApiResponse.success(new PageResponse<>(result.items().stream().map(CaseResponse::from).toList(), result.page(), result.size(), result.total()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get a collection case, including contact history, promises, escalations, and recoveries")
    public ApiResponse<CaseDetailResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        CollectionCaseService.CaseDetail detail = cases.get(id);
        access.viewCase(actor, detail.collectionCase());
        return ApiResponse.success(CaseDetailResponse.from(detail));
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Assign a collection case to an officer and branch")
    public ApiResponse<CaseResponse> assign(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody AssignRequest request) {
        CollectionCase collectionCase = cases.get(id).collectionCase();
        access.manage(actor, collectionCase);
        ApplicationAccess.assertBranch(actor, request.branchId());
        return ApiResponse.success(CaseResponse.from(cases.assign(id, request.officerId(), request.branchId(), actor.getId(), actor.getUsername())));
    }

    @PostMapping("/{id}/activities")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Record a customer contact or collection activity. The summary must not be used to store identity numbers")
    public ApiResponse<ActivityResponse> activity(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ActivityRequest request) {
        access.manage(actor, cases.get(id).collectionCase());
        return ApiResponse.success(ActivityResponse.from(cases.activity(id, request.activityType(), request.channel(), request.summary(), actor.getId(), actor.getUsername())));
    }

    @PostMapping("/{id}/promise-to-pay")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Record a promise to pay")
    public ApiResponse<PromiseResponse> promise(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody PromiseRequest request) {
        access.manage(actor, cases.get(id).collectionCase());
        return ApiResponse.success(PromiseResponse.from(cases.promise(id, request.amount(), request.promiseDate(), request.note(), actor.getId(), actor.getUsername())));
    }

    @PostMapping("/{id}/escalate")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Escalate a collection case")
    public ApiResponse<EscalationResponse> escalate(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody EscalateRequest request) {
        access.manage(actor, cases.get(id).collectionCase());
        return ApiResponse.success(EscalationResponse.from(cases.escalate(id, request.reason(), actor.getId(), actor.getUsername())));
    }

    @PostMapping("/{id}/recoveries")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Record a recovery against a posted repayment. This does not post cash or reduce the loan balance")
    public ApiResponse<RecoveryResponse> recover(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody RecoveryRequest request) {
        access.manage(actor, cases.get(id).collectionCase());
        return ApiResponse.success(RecoveryResponse.from(cases.recover(id, request.amount(), request.repaymentId(), request.note(), actor.getId(), actor.getUsername())));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Close a collection case. Uncollectible and restructured outcomes do not erase accrued obligations")
    public ApiResponse<CaseResponse> close(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody CloseRequest request) {
        access.manage(actor, cases.get(id).collectionCase());
        return ApiResponse.success(CaseResponse.from(cases.close(
                id, request.outcome(), request.note(), request.recoveryAmount(), request.repaymentId(), actor.getId(), actor.getUsername())));
    }
}
