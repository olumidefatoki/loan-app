package com.mfb.lending.module.loanoffer.api;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.loanoffer.application.AgreementTemplateService;
import com.mfb.lending.module.loanoffer.dto.AgreementTemplateResponse;
import com.mfb.lending.module.loanoffer.dto.SaveAgreementTemplateRequest;
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
@RequestMapping("/api/v1/admin/loan-agreement-templates")
@Tag(name = "Loan agreement templates", description = "Configurable agreement text. A change publishes a new version and leaves the previous text in place. Templates should be legally reviewed before they are used for customer acceptance.")
@SecurityRequirement(name = "bearerAuth")
public class AgreementTemplateAdminController {

    private static final String MANAGE =
            "hasAuthority('" + PermissionCodes.SYSTEM_CONFIGURE
                    + "') or hasAuthority('" + PermissionCodes.LOAN_OFFER_MANAGE + "')";

    private final AgreementTemplateService templates;

    public AgreementTemplateAdminController(AgreementTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    @PreAuthorize(MANAGE)
    @Operation(summary = "List agreement templates", description = "Returns every template version, including inactive ones.")
    public ApiResponse<List<AgreementTemplateResponse>> list() {
        return ApiResponse.success(templates.list());
    }

    @GetMapping("/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Get an agreement template")
    public ApiResponse<AgreementTemplateResponse> get(@PathVariable UUID id) {
        return ApiResponse.success(templates.get(id));
    }

    @PostMapping
    @PreAuthorize(MANAGE)
    @Operation(summary = "Create an agreement template", description = "Creates version 1. A later change is a new version, not an edit of this body.")
    public ResponseEntity<ApiResponse<AgreementTemplateResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody SaveAgreementTemplateRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                templates.create(request, actor, ClientContexts.from(httpRequest))));
    }

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Publish a new template version", description = "Inserts the next version for the same code and kind and makes it the active text. Agreements already issued keep the version they were rendered from.")
    public ApiResponse<AgreementTemplateResponse> revise(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody SaveAgreementTemplateRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(templates.revise(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Delete an unused agreement template", description = "Deletes a template version that no agreement references. A used version returns 409.")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        templates.delete(id, actor, ClientContexts.from(httpRequest));
        return ResponseEntity.noContent().build();
    }
}
