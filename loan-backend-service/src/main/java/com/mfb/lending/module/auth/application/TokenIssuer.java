package com.mfb.lending.module.auth.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.mfb.lending.config.AuthProperties;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.RefreshTokenRecord;
import com.mfb.lending.module.auth.domain.SecurityProfile;
import com.mfb.lending.module.auth.domain.SessionRecord;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.dto.AuthTokenResponse;
import com.mfb.lending.module.auth.persistence.RefreshTokenRepository;
import com.mfb.lending.module.auth.persistence.SessionRepository;
import com.mfb.lending.module.auth.security.JwtService;
import com.mfb.lending.module.auth.security.TokenHasher;
import org.springframework.stereotype.Component;

@Component
public class TokenIssuer {

    private final SessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final JwtService jwtService;
    private final SecureTokens tokens;
    private final AuthProperties properties;
    private final Clock clock;

    public TokenIssuer(
            SessionRepository sessions,
            RefreshTokenRepository refreshTokens,
            JwtService jwtService,
            SecureTokens tokens,
            AuthProperties properties,
            Clock clock) {
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.jwtService = jwtService;
        this.tokens = tokens;
        this.properties = properties;
        this.clock = clock;
    }

    public IssuedSession issue(UserRecord user, SecurityProfile profile, ClientContext client) {
        Instant now = clock.instant();
        Duration refreshTtl = refreshTtl(user.principalType());
        Instant expiresAt = now.plus(refreshTtl);
        UUID sessionId = UUID.randomUUID();
        UUID familyId = UUID.randomUUID();
        String refreshToken = tokens.refreshToken();
        sessions.insert(new SessionRecord(
                sessionId,
                user.id(),
                "ACTIVE",
                client.ipAddress(),
                client.userAgent(),
                now,
                now,
                expiresAt,
                null,
                null));
        refreshTokens.insert(new RefreshTokenRecord(
                UUID.randomUUID(),
                sessionId,
                user.id(),
                familyId,
                TokenHasher.sha256(refreshToken),
                expiresAt,
                now,
                null,
                null));
        String accessToken = jwtService.accessToken(
                user.id(),
                sessionId,
                user.principalType(),
                profile.roles(),
                profile.permissions(),
                profile.branchIds(),
                profile.unrestrictedBranches());
        return new IssuedSession(sessionId, new AuthTokenResponse(
                "Bearer",
                accessToken,
                refreshToken,
                properties.getJwt().getAccessTokenTtl().toSeconds(),
                sessionId,
                false,
                null));
    }

    public Duration refreshTtl(PrincipalType principalType) {
        return principalType == PrincipalType.STAFF
                ? properties.getJwt().getStaffRefreshTokenTtl()
                : properties.getJwt().getRefreshTokenTtl();
    }

    public record IssuedSession(UUID sessionId, AuthTokenResponse tokens) {
    }
}
