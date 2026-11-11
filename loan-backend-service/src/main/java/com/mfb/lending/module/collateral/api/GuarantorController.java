package com.mfb.lending.module.collateral.api;

import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.collateral.application.GuarantorService;
import com.mfb.lending.module.collateral.domain.GuarantorDocumentCode;
import com.mfb.lending.module.collateral.domain.GuarantorDocumentRecord;
import com.mfb.lending.module.collateral.dto.CreateGuarantorRequest;
import com.mfb.lending.module.collateral.dto.GuaranteeAcceptanceRequest;
import com.mfb.lending.module.collateral.dto.GuarantorResponse;
import com.mfb.lending.module.collateral.dto.GuarantorVerificationRequest;
import com.mfb.lending.module.collateral.dto.StoredDocumentResponse;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/guarantors")
@Tag(name = "Guarantors", description = """
        Guarantor identity, employment, income, obligations, consent, and guarantee acceptance. \
        BVN and NIN are encrypted and returned only in masked form. \
        A guarantor is not approved security until consent, verification, a guarantee agreement, and acceptance by a different officer are complete.""")
@SecurityRequirement(name = "bearerAuth")
public class GuarantorController {

    private static final String READ =
            "hasAuthority('" + PermissionCodes.GUARANTOR_VIEW
                    + "') or hasAuthority('" + PermissionCodes.GUARANTOR_MANAGE
                    + "') or hasAuthority('" + PermissionCodes.GUARANTOR_VERIFY + "')";
    private static final String MANAGE = "hasAuthority('" + PermissionCodes.GUARANTOR_MANAGE + "')";
    private static final String VERIFY = "hasAuthority('" + PermissionCodes.GUARANTOR_VERIFY + "')";

    private final GuarantorService guarantors;

    public GuarantorController(GuarantorService guarantors) {
        this.guarantors = guarantors;
    }

    @PostMapping
    @PreAuthorize(MANAGE)
    @Operation(summary = "Register a guarantor", description = "Records identity, contact details, BVN and NIN verification references, employment, income, obligations, and consent. approvedSecurity is false.")
    public ResponseEntity<ApiResponse<GuarantorResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody CreateGuarantorRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(guarantors.create(request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/{id}")
    @PreAuthorize(READ)
    @Operation(summary = "Get a guarantor", description = "Returns masked identity numbers, verification and acceptance status, consent, and document metadata. The guarantor is approved security only after acceptance.")
    public ApiResponse<GuarantorResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(guarantors.get(id, actor));
    }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(MANAGE)
    @Operation(summary = "Upload a guarantor document", description = "Accepts GUARANTEE_AGREEMENT, IDENTITY, INCOME_EVIDENCE, CONSENT, or OTHER. Files are scanned and stored privately.")
    public ResponseEntity<ApiResponse<StoredDocumentResponse>> upload(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @RequestParam GuarantorDocumentCode documentCode,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(guarantors.upload(id, documentCode, file, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/{id}/documents/{documentId}")
    @PreAuthorize(READ)
    @Operation(summary = "Download a guarantor document", description = "Returns the private file to an authorised officer. The storage location is not disclosed.")
    public ResponseEntity<byte[]> download(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @PathVariable UUID documentId) {
        GuarantorDocumentRecord document = guarantors.download(id, documentId, actor);
        String filename = document.fileName().replace("\"", "");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(guarantors.content(document));
    }

    @PostMapping("/{id}/verify")
    @PreAuthorize(VERIFY)
    @Operation(summary = "Verify a guarantor", description = "Verification records the identity check. It does not make the guarantor approved security.")
    public ApiResponse<GuarantorResponse> verify(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody GuarantorVerificationRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(guarantors.verify(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize(VERIFY)
    @Operation(summary = "Accept or reject a guarantee", description = "Acceptance requires a different officer from the verifier, recorded consent, and a guarantee agreement. Only then is the guarantor approved security, including on applications already linked.")
    public ApiResponse<GuarantorResponse> accept(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody GuaranteeAcceptanceRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(guarantors.accept(id, request, actor, ClientContexts.from(httpRequest)));
    }
}
