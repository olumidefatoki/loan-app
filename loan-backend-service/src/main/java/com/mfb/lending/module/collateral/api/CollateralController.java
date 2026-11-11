package com.mfb.lending.module.collateral.api;

import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.collateral.application.CollateralService;
import com.mfb.lending.module.collateral.application.CollateralValuationService;
import com.mfb.lending.module.collateral.domain.CollateralDocumentCode;
import com.mfb.lending.module.collateral.domain.CollateralDocumentRecord;
import com.mfb.lending.module.collateral.dto.CollateralDetailResponse;
import com.mfb.lending.module.collateral.dto.CollateralResponse;
import com.mfb.lending.module.collateral.dto.CreateCollateralRequest;
import com.mfb.lending.module.collateral.dto.CreateValuationRequest;
import com.mfb.lending.module.collateral.dto.ReleaseCollateralRequest;
import com.mfb.lending.module.collateral.dto.StoredDocumentResponse;
import com.mfb.lending.module.collateral.dto.UpdateCollateralRequest;
import com.mfb.lending.module.collateral.dto.ValuationDecisionRequest;
import com.mfb.lending.module.collateral.dto.ValuationResponse;
import com.mfb.lending.module.collateral.dto.VerifyCollateralRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/collaterals")
@Tag(name = "Collateral", description = """
        Security register for land, property, vehicles, equipment, machinery, inventory, fixed deposits, receivables, and other approved collateral. \
        Eligible lending value is calculated from market value, forced sale value, and the policy lending ratio, then kept on the approved valuation. \
        Only a collateral officer can verify or approve collateral. The officer who submitted a valuation cannot approve it. \
        Documents stay in private storage and are not returned as public URLs.""")
@SecurityRequirement(name = "bearerAuth")
public class CollateralController {

    private static final String VIEW =
            "hasRole('CUSTOMER') or hasAuthority('" + PermissionCodes.COLLATERAL_VIEW
                    + "') or hasAuthority('" + PermissionCodes.COLLATERAL_MANAGE
                    + "') or hasAuthority('" + PermissionCodes.COLLATERAL_VERIFY
                    + "') or hasAuthority('" + PermissionCodes.COLLATERAL_RELEASE + "')";
    private static final String MANAGE = "hasAuthority('" + PermissionCodes.COLLATERAL_MANAGE + "')";
    private static final String VERIFY = "hasAuthority('" + PermissionCodes.COLLATERAL_VERIFY + "')";
    private static final String RELEASE = "hasAuthority('" + PermissionCodes.COLLATERAL_RELEASE + "')";

    private final CollateralService collaterals;
    private final CollateralValuationService valuations;

    public CollateralController(CollateralService collaterals, CollateralValuationService valuations) {
        this.collaterals = collaterals;
        this.valuations = valuations;
    }

    @PostMapping
    @PreAuthorize(MANAGE)
    @Operation(summary = "Register collateral", description = "Captures the security, owner, identification, location, and initial valuation. The initial valuation stays pending until a different collateral officer approves it. Eligible value cannot be pledged until the collateral itself is approved.")
    public ResponseEntity<ApiResponse<CollateralDetailResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody CreateCollateralRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(collaterals.create(request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping
    @PreAuthorize(VIEW)
    @Operation(summary = "List collateral", description = "Customers see their own collateral. Staff see collateral for customers in their branches. Filter by customerId, type, status, and q.")
    public ApiResponse<PageResponse<CollateralResponse>> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) UUID customerId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(collaterals.list(actor, customerId, type, status, q, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    @Operation(summary = "Get collateral", description = "Returns the security, valuation history, documents, insurance, encumbrances, pledges, available value, and release history.")
    public ApiResponse<CollateralDetailResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(collaterals.get(id, actor));
    }

    @PutMapping("/{id}")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Update collateral details", description = "Updates description, owner, identification, location, or insurance. Value changes go through a valuation. Changing verified identity or insurance returns the collateral for review. Pledged collateral cannot change identity.")
    public ApiResponse<CollateralDetailResponse> update(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCollateralRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(collaterals.update(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(MANAGE)
    @Operation(summary = "Upload a collateral document", description = "Accepts PDF, JPEG, and PNG evidence such as TITLE, LEGAL_OPINION, SEARCH_REPORT, INSURANCE_POLICY, VALUATION_REPORT, PHOTOGRAPH, or OTHER. The file is scanned and stored privately.")
    public ResponseEntity<ApiResponse<StoredDocumentResponse>> upload(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @RequestParam CollateralDocumentCode documentCode,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(collaterals.upload(id, documentCode, file, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/{id}/documents/{documentId}")
    @PreAuthorize(VIEW)
    @Operation(summary = "Download a collateral document", description = "Returns the private file to an authorised caller. The storage location is not disclosed.")
    public ResponseEntity<byte[]> download(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @PathVariable UUID documentId) {
        CollateralDocumentRecord document = collaterals.download(id, documentId, actor);
        return file(document.fileName(), document.contentType(), collaterals.content(document));
    }

    @PostMapping("/{id}/valuations")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Submit a valuation", description = "Records an initial valuation or a revaluation. Eligible lending value is the lower of the stated value and market value multiplied by the policy lending ratio, and it cannot exceed forced sale value. A second officer approves it.")
    public ResponseEntity<ApiResponse<ValuationResponse>> valuate(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody CreateValuationRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(valuations.add(id, request, actor, ClientContexts.from(httpRequest))));
    }

    @PostMapping("/{id}/valuations/{valuationId}/decision")
    @PreAuthorize(VERIFY)
    @Operation(summary = "Approve or reject a valuation", description = "The submitting officer cannot decide the valuation. Approval copies the accepted eligible value onto the collateral. Approval is refused when the new value would fall below amounts already pledged.")
    public ApiResponse<ValuationResponse> decide(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @PathVariable UUID valuationId,
            @Valid @RequestBody ValuationDecisionRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(valuations.decide(id, valuationId, request, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize(VERIFY)
    @Operation(summary = "Verify collateral", description = "Records ownership, document, legal, insurance, and encumbrance checks. APPROVE is accepted only when those checks, a current approved valuation, and a clear encumbrance position are complete. REJECT records the reason and blocks pledging.")
    public ApiResponse<CollateralDetailResponse> verify(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody VerifyCollateralRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(collaterals.verify(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/release")
    @PreAuthorize(RELEASE)
    @Operation(summary = "Release collateral", description = "Requires a settlement reference, a discharge reference, and an officer other than the one who approved the collateral. Every active pledge must already be rejected, withdrawn, cancelled, expired, or disbursed.")
    public ApiResponse<CollateralDetailResponse> release(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody ReleaseCollateralRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(collaterals.release(id, request, actor, ClientContexts.from(httpRequest)));
    }

    private static ResponseEntity<byte[]> file(String fileName, String contentType, byte[] body) {
        String filename = fileName.replace("\"", "");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(body);
    }
}
