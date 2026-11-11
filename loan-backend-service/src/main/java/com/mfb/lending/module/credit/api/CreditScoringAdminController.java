package com.mfb.lending.module.credit.api;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.credit.application.CreditPolicyService;
import com.mfb.lending.module.credit.dto.CreateScoringModelRequest;
import com.mfb.lending.module.credit.dto.CreditRuleResponse;
import com.mfb.lending.module.credit.dto.SaveCreditRuleRequest;
import com.mfb.lending.module.credit.dto.ScoringModelResponse;
import com.mfb.lending.module.credit.dto.ScoringSpecRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/credit-scoring")
@Tag(name = "Credit scoring policy", description = """
        Scoring models and assessment rules. A published model version keeps its weights, risk categories, thresholds, and policy version. \
        Activate a new draft to change later assessments. Component weights must sum to 1. \
        Rules carry the threshold and whether an approved exception may waive them. Compliance holds cannot be waived.""")
@SecurityRequirement(name = "bearerAuth")
public class CreditScoringAdminController {

    private static final String MANAGE = "hasAuthority('" + PermissionCodes.CREDIT_POLICY_MANAGE + "')";

    private final CreditPolicyService policies;

    public CreditScoringAdminController(CreditPolicyService policies) {
        this.policies = policies;
    }

    @PostMapping("/models")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Create a scoring model draft")
    public ResponseEntity<ApiResponse<ScoringModelResponse>> createModel(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody CreateScoringModelRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(policies.createModel(request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/models")
    @PreAuthorize(MANAGE)
    @Operation(summary = "List scoring models")
    public ApiResponse<List<ScoringModelResponse>> listModels() {
        return ApiResponse.success(policies.listModels());
    }

    @GetMapping("/models/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Get a scoring model and its versions")
    public ApiResponse<ScoringModelResponse> getModel(@PathVariable UUID id) {
        return ApiResponse.success(policies.getModel(id));
    }

    @PutMapping("/models/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Update the draft scoring version", description = "Active and retired versions cannot be edited. Add a version instead.")
    public ApiResponse<ScoringModelResponse> updateModel(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ScoringSpecRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(policies.updateModel(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/models/{id}/versions")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Add a draft scoring version", description = "The previous published version stays in force until this draft is activated.")
    public ResponseEntity<ApiResponse<ScoringModelResponse>> addVersion(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ScoringSpecRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(policies.addVersion(id, request, actor, ClientContexts.from(httpRequest))));
    }

    @PostMapping("/models/{id}/activate")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Activate the draft scoring version", description = "Retires the previously active version without rewriting it.")
    public ApiResponse<ScoringModelResponse> activate(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(policies.activate(id, actor, ClientContexts.from(httpRequest)));
    }

    @DeleteMapping("/models/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Delete a draft scoring model")
    public ResponseEntity<Void> deleteModel(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        policies.deleteModel(id, actor, ClientContexts.from(httpRequest));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/rules")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Create an assessment rule")
    public ResponseEntity<ApiResponse<CreditRuleResponse>> createRule(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody SaveCreditRuleRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(policies.createRule(request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/rules")
    @PreAuthorize(MANAGE)
    @Operation(summary = "List assessment rules")
    public ApiResponse<List<CreditRuleResponse>> listRules() {
        return ApiResponse.success(policies.listRules());
    }

    @GetMapping("/rules/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Get an assessment rule")
    public ApiResponse<CreditRuleResponse> getRule(@PathVariable UUID id) {
        return ApiResponse.success(policies.getRule(id));
    }

    @PutMapping("/rules/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Update an assessment rule", description = "The rule code stays fixed so existing exceptions still point at it.")
    public ApiResponse<CreditRuleResponse> updateRule(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody SaveCreditRuleRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(policies.updateRule(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @DeleteMapping("/rules/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Delete an assessment rule", description = "Deletion is rejected when a policy exception already references the rule.")
    public ResponseEntity<Void> deleteRule(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        policies.deleteRule(id, actor, ClientContexts.from(httpRequest));
        return ResponseEntity.noContent().build();
    }
}
