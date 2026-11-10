package com.mfb.lending.module.auth.api;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.module.auth.application.AuthenticationService;
import com.mfb.lending.module.auth.application.OtpService;
import com.mfb.lending.module.auth.application.PasswordResetService;
import com.mfb.lending.module.auth.application.RegistrationService;
import com.mfb.lending.module.auth.domain.OtpPurpose;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.dto.AuthTokenResponse;
import com.mfb.lending.module.auth.dto.ForgotPasswordRequest;
import com.mfb.lending.module.auth.dto.LoginRequest;
import com.mfb.lending.module.auth.dto.MessageResponse;
import com.mfb.lending.module.auth.dto.RefreshTokenRequest;
import com.mfb.lending.module.auth.dto.RegisterCustomerRequest;
import com.mfb.lending.module.auth.dto.RegistrationResponse;
import com.mfb.lending.module.auth.dto.ResetPasswordRequest;
import com.mfb.lending.module.auth.dto.UserResponse;
import com.mfb.lending.module.auth.dto.VerifyOtpRequest;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Customer authentication", description = """
        Shared by React and Flutter. Clients send Authorization: Bearer for protected calls and the refresh token in JSON. \
        The signed-in customer is always taken from the access token, so one customer cannot request another customer's identity.""")
public class CustomerAuthController {

    private final RegistrationService registration;
    private final AuthenticationService authentication;
    private final OtpService otps;
    private final PasswordResetService passwords;

    public CustomerAuthController(
            RegistrationService registration,
            AuthenticationService authentication,
            OtpService otps,
            PasswordResetService passwords) {
        this.registration = registration;
        this.authentication = authentication;
        this.otps = otps;
        this.passwords = passwords;
    }

    @PostMapping("/register")
    @Operation(summary = "Register an individual or SME customer")
    public ResponseEntity<ApiResponse<RegistrationResponse>> register(
            @Valid @RequestBody RegisterCustomerRequest request,
            HttpServletRequest httpRequest) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(registration.register(request, ClientContexts.from(httpRequest))));
    }

    @PostMapping("/verify-otp")
    @Operation(summary = "Verify an email, phone, or password-reset code")
    public ApiResponse<MessageResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request, HttpServletRequest httpRequest) {
        otps.verify(
                request.identifier().trim(),
                OtpPurpose.valueOf(request.purpose()),
                request.code(),
                ClientContexts.from(httpRequest)).required();
        return ApiResponse.success(new MessageResponse("Verification successful"));
    }

    @PostMapping("/login")
    @Operation(summary = "Sign in a customer and issue a short-lived access token")
    public ApiResponse<AuthTokenResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(authentication.login(
                request.identifier(),
                request.password(),
                ClientContexts.from(httpRequest),
                PrincipalType.CUSTOMER).required());
    }

    @PostMapping("/refresh-token")
    @Operation(summary = "Rotate a customer refresh token")
    public ApiResponse<AuthTokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(authentication.refresh(
                request.refreshToken(),
                PrincipalType.CUSTOMER,
                ClientContexts.from(httpRequest)).required());
    }

    @PostMapping("/logout")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Revoke the current customer session and its refresh tokens")
    public ApiResponse<MessageResponse> logout(@AuthenticationPrincipal AuthenticatedUser user, HttpServletRequest httpRequest) {
        return ApiResponse.success(authentication.logout(user, ClientContexts.from(httpRequest)));
    }

    @PostMapping("/forgot-password")
    @Operation(summary = "Request a password-reset code without revealing whether the account exists")
    public ApiResponse<MessageResponse> forgot(@Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(passwords.forgot(request.identifier(), ClientContexts.from(httpRequest)));
    }

    @PostMapping("/reset-password")
    @Operation(summary = "Reset a password with a one-time code and revoke existing sessions")
    public ApiResponse<MessageResponse> reset(@Valid @RequestBody ResetPasswordRequest request, HttpServletRequest httpRequest) {
        return ApiResponse.success(passwords.reset(
                request.identifier(),
                request.code(),
                request.newPassword(),
                ClientContexts.from(httpRequest)).required());
    }

    @GetMapping("/me")
    @SecurityRequirement(name = "bearerAuth")
    @Operation(summary = "Return the authenticated customer or staff identity")
    public ApiResponse<UserResponse> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(authentication.me(user));
    }
}
