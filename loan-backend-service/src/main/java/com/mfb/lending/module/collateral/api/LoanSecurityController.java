package com.mfb.lending.module.collateral.api;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.api.ClientContexts;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.collateral.application.CollateralAllocationService;
import com.mfb.lending.module.collateral.application.GuarantorService;
import com.mfb.lending.module.collateral.dto.AllocateCollateralRequest;
import com.mfb.lending.module.collateral.dto.LinkGuarantorRequest;
import com.mfb.lending.module.collateral.dto.LoanCollateralListResponse;
import com.mfb.lending.module.collateral.dto.LoanCollateralResponse;
import com.mfb.lending.module.collateral.dto.LoanGuarantorResponse;
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
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loan-applications/{applicationId}")
@Tag(name = "Loan security", description = """
        Links approved collateral and guarantors to a loan application. \
        Shared security is allowed only when every active pledge is marked as shared, and the total pledged amount cannot exceed eligible collateral value. \
        A linked guarantor remains unapproved security until verification and guarantee acceptance are complete.""")
@SecurityRequirement(name = "bearerAuth")
public class LoanSecurityController {

    private static final String COLLATERAL_READ =
            "hasAuthority('" + PermissionCodes.COLLATERAL_VIEW
                    + "') or hasAuthority('" + PermissionCodes.COLLATERAL_MANAGE
                    + "') or hasAuthority('" + PermissionCodes.COLLATERAL_VERIFY
                    + "') or hasAuthority('" + PermissionCodes.COLLATERAL_RELEASE
                    + "') or hasAuthority('" + PermissionCodes.LOAN_APPLICATION_VIEW
                    + "') or hasAuthority('" + PermissionCodes.LOAN_APPLICATION_CREATE + "')";
    private static final String COLLATERAL_PLEDGE = "hasAuthority('" + PermissionCodes.COLLATERAL_MANAGE + "')";
    private static final String GUARANTOR_READ =
            "hasAuthority('" + PermissionCodes.GUARANTOR_VIEW
                    + "') or hasAuthority('" + PermissionCodes.GUARANTOR_MANAGE
                    + "') or hasAuthority('" + PermissionCodes.GUARANTOR_VERIFY + "')";
    private static final String GUARANTOR_LINK = "hasAuthority('" + PermissionCodes.GUARANTOR_MANAGE + "')";

    private final CollateralAllocationService allocations;
    private final GuarantorService guarantors;

    public LoanSecurityController(CollateralAllocationService allocations, GuarantorService guarantors) {
        this.allocations = allocations;
        this.guarantors = guarantors;
    }

    @PostMapping("/collaterals")
    @PreAuthorize(COLLATERAL_PLEDGE)
    @Operation(summary = "Allocate collateral to an application", description = "The collateral must be approved, unexpired, and registered to the same customer. sharedSecurity=false keeps the pledge exclusive. A shared pledge is refused when it would exceed the remaining eligible value.")
    public ResponseEntity<ApiResponse<LoanCollateralResponse>> allocate(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID applicationId,
            @Valid @RequestBody AllocateCollateralRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(allocations.allocate(applicationId, request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/collaterals")
    @PreAuthorize(COLLATERAL_READ)
    @Operation(summary = "List collateral allocated to an application", description = "Returns each pledge, the eligible value still available, and coverage as total allocated value divided by the facility amount.")
    public ApiResponse<LoanCollateralListResponse> collaterals(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID applicationId) {
        return ApiResponse.success(allocations.list(applicationId, actor));
    }

    @PostMapping("/guarantors")
    @PreAuthorize(GUARANTOR_LINK)
    @Operation(summary = "Link a guarantor to an application", description = "approvedSecurity is false until the guarantor has recorded consent, a verified identity, a guarantee agreement, and acceptance by a second officer.")
    public ResponseEntity<ApiResponse<LoanGuarantorResponse>> link(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID applicationId,
            @Valid @RequestBody LinkGuarantorRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(guarantors.link(applicationId, request, actor, ClientContexts.from(httpRequest))));
    }

    @GetMapping("/guarantors")
    @PreAuthorize(GUARANTOR_READ)
    @Operation(summary = "List guarantors linked to an application", description = "Shows whether each linked guarantor is approved security.")
    public ApiResponse<List<LoanGuarantorResponse>> guarantors(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @PathVariable UUID applicationId) {
        return ApiResponse.success(guarantors.listLinks(applicationId, actor));
    }
}
