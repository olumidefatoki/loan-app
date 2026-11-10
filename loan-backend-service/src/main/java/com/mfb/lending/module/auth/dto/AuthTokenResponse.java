package com.mfb.lending.module.auth.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record AuthTokenResponse(
        @Schema(example = "Bearer") String tokenType,
        String accessToken,
        String refreshToken,
        @Schema(description = "Access token lifetime in seconds", example = "900") Long expiresIn,
        UUID sessionId,
        @Schema(description = "When true, submit mfaToken and the authenticator code to the staff MFA endpoint before using the API")
        boolean mfaRequired,
        String mfaToken
) {
}
