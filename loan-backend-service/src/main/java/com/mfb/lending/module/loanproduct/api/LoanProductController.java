package com.mfb.lending.module.loanproduct.api;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.loanproduct.application.LoanProductService;
import com.mfb.lending.module.loanproduct.dto.CalculatorConfigResponse;
import com.mfb.lending.module.loanproduct.dto.CommercialTermsRequest;
import com.mfb.lending.module.loanproduct.dto.CreateLoanProductRequest;
import com.mfb.lending.module.loanproduct.dto.CreateLoanProductVersionRequest;
import com.mfb.lending.module.loanproduct.dto.LoanProductResponse;
import com.mfb.lending.module.loanproduct.dto.LoanProductSummaryResponse;
import com.mfb.lending.module.loanproduct.dto.LoanProductVersionResponse;
import com.mfb.lending.module.loanproduct.dto.ProductActionRequest;
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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Loan products", description = "Configurable personal and SME lending products. Versions are immutable after they leave draft, so a booked loan keeps the terms it was opened on. Approval and activation use maker-checker.")
@SecurityRequirement(name = "bearerAuth")
public class LoanProductController {

    private static final String READ =
            "hasRole('CUSTOMER') or hasAuthority('" + PermissionCodes.LOAN_PRODUCT_VIEW
                    + "') or hasAuthority('" + PermissionCodes.LOAN_PRODUCT_MANAGE
                    + "') or hasAuthority('" + PermissionCodes.LOAN_PRODUCT_APPROVE
                    + "') or hasAuthority('" + PermissionCodes.LOAN_APPLICATION_VIEW + "')";
    private static final String HISTORY =
            "hasAuthority('" + PermissionCodes.LOAN_PRODUCT_VIEW
                    + "') or hasAuthority('" + PermissionCodes.LOAN_PRODUCT_MANAGE
                    + "') or hasAuthority('" + PermissionCodes.LOAN_PRODUCT_APPROVE + "')";
    private static final String MANAGE = "hasAuthority('" + PermissionCodes.LOAN_PRODUCT_MANAGE + "')";
    private static final String APPROVE = "hasAuthority('" + PermissionCodes.LOAN_PRODUCT_APPROVE + "')";

    private final LoanProductService products;

    public LoanProductController(LoanProductService products) {
        this.products = products;
    }

    @PostMapping("/admin/loan-products")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Create a draft loan product", description = "Stores the product code, category, and the first draft version. The product is not offered until a different administrator approves and activates it.")
    public ResponseEntity<ApiResponse<LoanProductResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody CreateLoanProductRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(products.create(request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/loan-products")
    @PreAuthorize(READ)
    @Operation(summary = "List loan products", description = "Customers and originators receive active products whose effective date has started. Staff with LOAN_PRODUCT_VIEW can filter every status.")
    public ApiResponse<PageResponse<LoanProductSummaryResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String customerSegment,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(products.list(actor, status, category, customerSegment, page, size));
    }

    @GetMapping("/loan-products/{id}")
    @PreAuthorize(READ)
    @Operation(summary = "Get a loan product", description = "Returns the version currently in force. Unpublished products are visible to product administrators only.")
    public ApiResponse<LoanProductResponse> get(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id) {
        return ApiResponse.success(products.get(id, actor));
    }

    @PutMapping("/admin/loan-products/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Update the open draft", description = "Changes the draft version in place. A submitter can revise a version that is still awaiting approval, which returns it to draft. Active terms and booked loans are left unchanged.")
    public ApiResponse<LoanProductResponse> update(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody CommercialTermsRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(products.update(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/admin/loan-products/{id}/versions")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Open a new product version", description = "Creates the next draft from the supplied terms. The version already in force stays effective until the new version is approved and activated.")
    public ResponseEntity<ApiResponse<LoanProductResponse>> openVersion(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody CreateLoanProductVersionRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(products.openVersion(id, request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/admin/loan-products/{id}/versions")
    @PreAuthorize(HISTORY)
    @Operation(summary = "List product version history", description = "Returns every version, including superseded terms that booked loans still use.")
    public ApiResponse<List<LoanProductVersionResponse>> versions(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id) {
        return ApiResponse.success(products.versions(id, actor));
    }

    @PostMapping("/admin/loan-products/{id}/submit")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Submit a draft for approval", description = "Freezes the draft for maker-checker review. The submitter cannot approve or activate it.")
    public ApiResponse<LoanProductResponse> submit(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(products.submit(id, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/admin/loan-products/{id}/approve")
    @PreAuthorize(APPROVE)
    @Operation(summary = "Approve a submitted product version", description = "A different administrator from the submitter must approve the version before it can be activated.")
    public ApiResponse<LoanProductResponse> approve(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(products.approve(id, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/admin/loan-products/{id}/activate")
    @PreAuthorize(APPROVE)
    @Operation(summary = "Activate an approved product version", description = "Makes the approved version the one offered to customers. The previous live version is superseded and its terms stay readable for loans already booked on it. A suspended product can be activated again by a different administrator.")
    public ApiResponse<LoanProductResponse> activate(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @RequestBody(required = false) ProductActionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(products.activate(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/admin/loan-products/{id}/suspend")
    @PreAuthorize(APPROVE)
    @Operation(summary = "Suspend an active product", description = "Stops the product being offered. Booked loans keep the version they were opened on. A different administrator from the submitter must suspend it.")
    public ApiResponse<LoanProductResponse> suspend(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ProductActionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(products.suspend(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/admin/loan-products/{id}/deactivate")
    @PreAuthorize(APPROVE)
    @Operation(summary = "Deactivate a product", description = "Retires the product and freezes its versions. A different administrator from the submitter must deactivate it. Booked loans keep their original terms.")
    public ApiResponse<LoanProductResponse> deactivate(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ProductActionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(products.deactivate(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @GetMapping("/loan-products/{id}/calculator-config")
    @PreAuthorize(READ)
    @Operation(summary = "Get calculator configuration", description = "Returns interest method, day count, rounding, fees, and limits for the active version currently offered to customers.")
    public ApiResponse<CalculatorConfigResponse> calculator(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id) {
        return ApiResponse.success(products.calculator(id, actor));
    }
}
