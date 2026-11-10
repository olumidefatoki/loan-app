package com.mfb.lending.module.auth.api;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.application.AuthenticationService;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.dto.AuthTokenResponse;
import com.mfb.lending.module.auth.dto.LoginRequest;
import com.mfb.lending.module.auth.dto.MessageResponse;
import com.mfb.lending.module.auth.dto.MfaVerifyRequest;
import com.mfb.lending.module.auth.dto.RefreshTokenRequest;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/auth")
@Tag(name = "Staff authentication", description = """
        Staff sign-in uses the same token format as customer sign-in. Privileged roles must complete MFA before an access token is issued. \
        React and Flutter clients share this contract.""")
public class StaffAuthController {

    private final AuthenticationService authentication;

    public StaffAuthController(AuthenticationService authentication) {
        this.authentication = authentication;
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in a staff account")
    public ApiResponse<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(authentication.login(
                request.identifier(),
                request.password(),
                ClientContexts.from(httpRequest),
                PrincipalType.STAFF).required());
    }

    @PostMapping("/mfa/verify")
    @Operation(summary = "Complete a staff MFA challenge")
    public ApiResponse<AuthTokenResponse> verifyMfa(@Valid @RequestBody MfaVerifyRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(authentication.verifyMfa(
                request.mfaToken(),
                request.code(),
                ClientContexts.from(httpRequest)).required());
    }

    @PostMapping("/refresh-token")
    @Operation(summary = "Rotate a staff refresh token")
    public ApiResponse<AuthTokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(authentication.refresh(
                request.refreshToken(),
                PrincipalType.STAFF,
                ClientContexts.from(httpRequest)).required());
    }

    @PostMapping("/logout")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Revoke the current staff session and its refresh tokens")
    public ApiResponse<MessageResponse> logout(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest httpRequest) {
        return ApiResponse.success(authentication.logout(user, ClientContexts.from(httpRequest)));
    }
}
