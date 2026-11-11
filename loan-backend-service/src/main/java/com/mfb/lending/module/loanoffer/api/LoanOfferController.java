package com.mfb.lending.module.loanoffer.api;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.loanoffer.application.LoanOfferService;
import com.mfb.lending.module.loanoffer.application.LoanOfferService.DocumentFile;
import com.mfb.lending.module.loanoffer.dto.AcceptOfferRequest;
import com.mfb.lending.module.loanoffer.dto.AgreementResponse;
import com.mfb.lending.module.loanoffer.dto.GenerateOfferRequest;
import com.mfb.lending.module.loanoffer.dto.LoanOfferResponse;
import com.mfb.lending.module.loanoffer.dto.OfferReasonRequest;
import com.mfb.lending.module.loanoffer.dto.ScheduleItemResponse;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@Tag(name = "Loan offers", description = """
        Issues a loan offer from a finally approved application. Instalments, interest, fees, and taxes come from the loan calculation engine \
        and the product version pinned to the application. A material change creates a new offer version and a new agreement; the previous agreement stays readable. \
        Customer acceptance is an electronic record tied to a verified KYC identity. It is not a qualified digital signature unless an approved signature provider is configured \
        and the institution has the required legal approval. The agreement PDF checksum is stored and checked on every download.""")
@SecurityRequirement(name = "bearerAuth")
public class LoanOfferController {

    private static final String VIEW =
            "hasRole('CUSTOMER') or hasAuthority('" + PermissionCodes.LOAN_OFFER_VIEW
                    + "') or hasAuthority('" + PermissionCodes.LOAN_OFFER_MANAGE
                    + "') or hasAuthority('" + PermissionCodes.LOAN_APPLICATION_VIEW + "')";
    private static final String MANAGE = "hasAuthority('" + PermissionCodes.LOAN_OFFER_MANAGE + "')";
    private static final String CUSTOMER = "hasRole('CUSTOMER')";

    private final LoanOfferService offers;

    public LoanOfferController(LoanOfferService offers) {
        this.offers = offers;
    }

    @PostMapping("/api/v1/loan-applications/{id}/generate-offer")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Generate a loan offer", description = "Requires a final approval for the current application revision. The principal cannot exceed the approved amount. Send issue=false to keep the offer in draft. The same terms return the current offer. Changed terms create a new version and require a fresh acceptance.")
    public ResponseEntity<ApiResponse<LoanOfferResponse>> generate(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @RequestBody(required = false) GenerateOfferRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(
                offers.generate(id, request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/api/v1/loan-offers/{id}")
    @PreAuthorize(VIEW)
    @Operation(summary = "Get a loan offer", description = "Returns the current terms, engine version, and agreement checksum. An elapsed expiry is applied before the response is returned.")
    public ApiResponse<LoanOfferResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(offers.get(id, actor));
    }

    @PostMapping("/api/v1/loan-offers/{id}/accept")
    @PreAuthorize(CUSTOMER)
    @Operation(summary = "Accept a loan offer", description = "Records the customer's electronic acceptance of the current agreement version after checking that KYC is verified and the offer has not expired. agreementRead must be true. This is not stored as a qualified digital signature. A concurrent second acceptance returns 409.")
    public ApiResponse<LoanOfferResponse> accept(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody AcceptOfferRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(offers.accept(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/api/v1/loan-offers/{id}/decline")
    @PreAuthorize(CUSTOMER)
    @Operation(summary = "Decline a loan offer", description = "Closes an issued offer and withdraws the application. An expired offer cannot be declined after it has already expired.")
    public ApiResponse<LoanOfferResponse> decline(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody OfferReasonRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(offers.decline(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/api/v1/loan-offers/{id}/cancel")
    @PreAuthorize(MANAGE)
    @Operation(summary = "Cancel a loan offer", description = "Cancels a draft or issued offer. An issued offer also cancels the application. A draft cancellation leaves the application approved.")
    public ApiResponse<LoanOfferResponse> cancel(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody OfferReasonRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(offers.cancel(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @GetMapping("/api/v1/loan-offers/{id}/schedule")
    @PreAuthorize(VIEW)
    @Operation(summary = "Get the offer repayment schedule", description = "Returns the schedule produced by the loan calculation engine for the current offer version, or for the version query when one is supplied.")
    public ApiResponse<List<ScheduleItemResponse>> schedule(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @RequestParam(required = false) Integer version) {
        return ApiResponse.success(offers.schedule(id, version, actor));
    }

    @GetMapping("/api/v1/loan-offers/{id}/agreement")
    @PreAuthorize(VIEW)
    @Operation(summary = "Get a loan agreement", description = "Returns the rendered agreement, its SHA-256 checksum, and any acceptance. kind is LOAN, GUARANTOR, or COLLATERAL. version selects an older offer version. Accepted agreements remain available after a later version is issued.")
    public ApiResponse<AgreementResponse> agreement(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @RequestParam(required = false) Integer version,
            @RequestParam(required = false) String kind) {
        return ApiResponse.success(offers.agreement(id, version, kind, actor));
    }

    @GetMapping(value = "/api/v1/loan-offers/{id}/agreement/document", produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize(VIEW)
    @Operation(summary = "Download the agreement PDF", description = "Returns the stored PDF. The download is rejected when the file bytes do not match the stored SHA-256 checksum.")
    public ResponseEntity<byte[]> document(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @RequestParam(required = false) Integer version,
            @RequestParam(required = false) String kind) {
        DocumentFile file = offers.document(id, version, kind, actor);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.fileName() + "\"")
                .header("X-Content-Sha256", file.checksumSha256())
                .contentType(MediaType.APPLICATION_PDF)
                .body(file.body());
    }
}
