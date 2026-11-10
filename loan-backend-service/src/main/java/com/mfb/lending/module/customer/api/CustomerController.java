package com.mfb.lending.module.customer.api;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.application.CustomerComplianceService;
import com.mfb.lending.module.customer.application.CustomerDocumentService;
import com.mfb.lending.module.customer.application.CustomerProfileService;
import com.mfb.lending.module.customer.application.CustomerService;
import com.mfb.lending.module.customer.domain.ClientChannel;
import com.mfb.lending.module.customer.domain.DocumentRecord;
import com.mfb.lending.module.customer.domain.DocumentType;
import com.mfb.lending.module.customer.dto.AccountResponse;
import com.mfb.lending.module.customer.dto.AccountVerifyRequest;
import com.mfb.lending.module.customer.dto.BeneficialOwnerRequest;
import com.mfb.lending.module.customer.dto.CreateCustomerRequest;
import com.mfb.lending.module.customer.dto.CustomerResponse;
import com.mfb.lending.module.customer.dto.CustomerSummaryResponse;
import com.mfb.lending.module.customer.dto.DirectorRequest;
import com.mfb.lending.module.customer.dto.DocumentResponse;
import com.mfb.lending.module.customer.dto.IndividualProfileRequest;
import com.mfb.lending.module.customer.dto.KycResponse;
import com.mfb.lending.module.customer.dto.PartyResponse;
import com.mfb.lending.module.customer.dto.ScreeningResponse;
import com.mfb.lending.module.customer.dto.SmeProfileRequest;
import com.mfb.lending.module.customer.dto.UpdateCustomerRequest;
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
@RequestMapping("/api/v1/customers")
@Tag(name = "Customers", description = "Customer onboarding, profiles, documents, and KYC. Web and mobile clients use the same endpoints. Identity numbers are stored encrypted and returned masked.")
@SecurityRequirement(name = "bearerAuth")
public class CustomerController {

    private static final String STAFF_OR_CUSTOMER =
            "hasAuthority('" + PermissionCodes.CUSTOMER_CREATE + "') or hasRole('CUSTOMER')";
    private static final String VIEW_OR_CUSTOMER =
            "hasAuthority('" + PermissionCodes.CUSTOMER_VIEW + "') or hasRole('CUSTOMER')";

    private final CustomerService customers;
    private final CustomerProfileService profiles;
    private final CustomerComplianceService compliance;
    private final CustomerDocumentService documents;

    public CustomerController(
            CustomerService customers,
            CustomerProfileService profiles,
            CustomerComplianceService compliance,
            CustomerDocumentService documents) {
        this.customers = customers;
        this.profiles = profiles;
        this.compliance = compliance;
        this.documents = documents;
    }

    @PostMapping
    @PreAuthorize(STAFF_OR_CUSTOMER)
    @Operation(summary = "Create a customer", description = "Staff create a customer in an assigned branch. A customer creates their own record.")
    public ResponseEntity<ApiResponse<CustomerResponse>> create(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @Valid @RequestBody CreateCustomerRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(customers.create(request, actor, ClientContexts.from(httpRequest), channel(httpRequest))));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('" + PermissionCodes.CUSTOMER_VIEW + "')")
    @Operation(summary = "Search and look up customers", description = "Filters are exact for reference, email, phone, BVN, NIN, and CAC number. q matches name, reference, email, or phone. Results stay inside the caller's branches.")
    public ApiResponse<PageResponse<CustomerSummaryResponse>> search(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String customerType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) String reference,
            @RequestParam(required = false) String email,
            @RequestParam(required = false) String phoneNumber,
            @RequestParam(required = false) String bvn,
            @RequestParam(required = false) String nin,
            @RequestParam(required = false) String cacNumber,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(customers.search(
                actor, q, customerType, status, category, branchId, reference, email, phoneNumber, bvn, nin, cacNumber, page, size));
    }

    @GetMapping("/me")
    @PreAuthorize("hasRole('CUSTOMER')")
    @Operation(summary = "Get the signed-in customer's profile")
    public ApiResponse<CustomerResponse> me(@AuthenticationPrincipal AuthenticatedUser actor) {
        return ApiResponse.success(customers.me(actor));
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW_OR_CUSTOMER)
    @Operation(summary = "Get a customer")
    public ApiResponse<CustomerResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(customers.get(actor, id));
    }

    @PutMapping("/{id}")
    @PreAuthorize(STAFF_OR_CUSTOMER)
    @Operation(summary = "Update a customer", description = "Supports profile contact changes, categorisation, branch assignment, activation, deactivation, blacklist, and risk classification.")
    public ApiResponse<CustomerResponse> update(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCustomerRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(customers.update(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/{id}/individual-profile")
    @PreAuthorize(STAFF_OR_CUSTOMER)
    @Operation(summary = "Save an individual profile")
    public ApiResponse<CustomerResponse> individual(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody IndividualProfileRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(profiles.saveIndividual(id, request, actor, ClientContexts.from(httpRequest), channel(httpRequest)));
    }

    @PostMapping("/{id}/sme-profile")
    @PreAuthorize(STAFF_OR_CUSTOMER)
    @Operation(summary = "Save an SME profile", description = "Requires at least one authorised representative. Beneficial owners are added separately and are retained.")
    public ApiResponse<CustomerResponse> sme(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody SmeProfileRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(profiles.saveSme(id, request, actor, ClientContexts.from(httpRequest), channel(httpRequest)));
    }

    @PostMapping("/{id}/directors")
    @PreAuthorize(STAFF_OR_CUSTOMER)
    @Operation(summary = "Add a director")
    public ResponseEntity<ApiResponse<PartyResponse>> director(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody DirectorRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(profiles.addDirector(id, request, actor, ClientContexts.from(httpRequest))));
    }

    @PostMapping("/{id}/beneficial-owners")
    @PreAuthorize(STAFF_OR_CUSTOMER)
    @Operation(summary = "Add a beneficial owner")
    public ResponseEntity<ApiResponse<PartyResponse>> owner(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody BeneficialOwnerRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(profiles.addBeneficialOwner(id, request, actor, ClientContexts.from(httpRequest))));
    }

    @PostMapping("/{id}/kyc")
    @PreAuthorize(STAFF_OR_CUSTOMER)
    @Operation(summary = "Run KYC and screening", description = "Uses the configured provider ports. In development and test the mock provider times out on an all-zero identity number, fails an all-nine number, and matches names containing SANCTIONED, the whole word PEP, or HIGHRISK. Failures, timeouts, and screening matches open review cases. No vendor URL is called.")
    public ApiResponse<KycResponse> kyc(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(compliance.run(id, actor, ClientContexts.from(httpRequest)));
    }

    @GetMapping("/{id}/kyc")
    @PreAuthorize(VIEW_OR_CUSTOMER)
    @Operation(summary = "Get KYC checks and review cases")
    public ApiResponse<KycResponse> getKyc(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(compliance.kyc(id, actor));
    }

    @PostMapping(value = "/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize(STAFF_OR_CUSTOMER)
    @Operation(summary = "Upload a customer document", description = "Accepts PDF, JPEG, and PNG files within the configured size limit. The file is scanned before its metadata and checksum are stored. Bytes are kept in private storage.")
    public ResponseEntity<ApiResponse<DocumentResponse>> upload(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @RequestParam DocumentType documentType,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(documents.upload(id, documentType, file, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/{id}/documents")
    @PreAuthorize(VIEW_OR_CUSTOMER)
    @Operation(summary = "List customer documents")
    public ApiResponse<List<DocumentResponse>> documents(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(documents.list(id, actor));
    }

    @GetMapping("/{id}/documents/{documentId}")
    @PreAuthorize(VIEW_OR_CUSTOMER)
    @Operation(summary = "Download a customer document")
    public ResponseEntity<byte[]> download(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @PathVariable UUID documentId) {
        DocumentRecord document = documents.download(id, documentId, actor);
        String filename = document.fileName().replace("\"", "");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(document.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(documents.content(document));
    }

    @PostMapping("/{id}/accounts/verify")
    @PreAuthorize(STAFF_OR_CUSTOMER)
    @Operation(summary = "Verify a bank account")
    public ApiResponse<AccountResponse> verifyAccount(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID id,
            @Valid @RequestBody AccountVerifyRequest request,
            HttpServletRequest httpRequest) {
        return ApiResponse.success(compliance.verifyAccount(id, request, actor, ClientContexts.from(httpRequest)));
    }

    @GetMapping("/{id}/accounts")
    @PreAuthorize(VIEW_OR_CUSTOMER)
    @Operation(summary = "List customer bank accounts")
    public ApiResponse<List<AccountResponse>> accounts(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(compliance.accounts(id, actor));
    }

    @GetMapping("/{id}/screening")
    @PreAuthorize(VIEW_OR_CUSTOMER)
    @Operation(summary = "Get sanctions, PEP, and AML screening results")
    public ApiResponse<ScreeningResponse> screening(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(compliance.screening(id, actor));
    }

    private static ClientChannel channel(HttpServletRequest request) {
        return ClientChannel.parse(request.getHeader("X-Client-Channel"));
    }
}
