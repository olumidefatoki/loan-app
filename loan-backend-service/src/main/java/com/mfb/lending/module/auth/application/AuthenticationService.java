package com.mfb.lending.module.auth.application;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.UnauthorizedException;
import com.mfb.lending.config.AuthProperties;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.RefreshTokenRecord;
import com.mfb.lending.module.auth.domain.SecurityProfile;
import com.mfb.lending.module.auth.domain.SessionRecord;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import com.mfb.lending.module.auth.dto.AuthTokenResponse;
import com.mfb.lending.module.auth.dto.MfaSetup;
import com.mfb.lending.module.auth.dto.MessageResponse;
import com.mfb.lending.module.auth.dto.UserResponse;
import com.mfb.lending.module.auth.persistence.LoginAttemptRepository;
import com.mfb.lending.module.auth.persistence.RefreshTokenRepository;
import com.mfb.lending.module.auth.persistence.SecurityProfileRepository;
import com.mfb.lending.module.auth.persistence.SessionRepository;
import com.mfb.lending.module.auth.persistence.UserRepository;
import com.mfb.lending.module.auth.security.AesGcmCipher;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.auth.security.JwtService;
import com.mfb.lending.module.auth.security.TokenHasher;
import com.mfb.lending.module.auth.security.TotpVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthenticationService {

    private static final String INVALID_CREDENTIALS = "The email, phone number, or password is incorrect";

    private final UserRepository users;
    private final SecurityProfileRepository profiles;
    private final SessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final LoginAttemptRepository attempts;
    private final PasswordVerifier passwords;
    private final TokenIssuer tokenIssuer;
    private final JwtService jwtService;
    private final TotpVerifier totpVerifier;
    private final AesGcmCipher cipher;
    private final SecureTokens tokens;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final AuthProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public AuthenticationService(
            UserRepository users,
            SecurityProfileRepository profiles,
            SessionRepository sessions,
            RefreshTokenRepository refreshTokens,
            LoginAttemptRepository attempts,
            PasswordVerifier passwords,
            TokenIssuer tokenIssuer,
            JwtService jwtService,
            TotpVerifier totpVerifier,
            AesGcmCipher cipher,
            SecureTokens tokens,
            AuditService audit,
            RateLimiter rateLimiter,
            AuthProperties properties,
            Clock clock,
            TransactionTemplate transactions) {
        this.users = users;
        this.profiles = profiles;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.attempts = attempts;
        this.passwords = passwords;
        this.tokenIssuer = tokenIssuer;
        this.jwtService = jwtService;
        this.totpVerifier = totpVerifier;
        this.cipher = cipher;
        this.tokens = tokens;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
        this.transactions = transactions;
    }

    public AuthResult<AuthTokenResponse> login(String identifier, String password, ClientContext client, PrincipalType expected) {
        String normalized = Identifiers.normalize(identifier);
        rateLimiter.assertLoginAllowed(
                client.ipAddress(),
                normalized,
                properties.getRateLimit().getLoginMaxAttempts(),
                properties.getRateLimit().getLoginWindow());
        UserRecord candidate = users.findByIdentifier(normalized).orElse(null);
        if (candidate != null && candidate.principalType() != expected) {
            candidate = null;
        }
        boolean passwordMatches = passwords.matches(password, candidate == null ? null : candidate.passwordHash());
        UUID userId = candidate == null ? null : candidate.id();
        return transactions.execute(status -> finishLogin(userId, passwordMatches, normalized, client, expected));
    }

    public AuthResult<AuthTokenResponse> verifyMfa(String mfaToken, String code, ClientContext client) {
        UUID userId = jwtService.parseMfaToken(mfaToken);
        rateLimiter.assertLoginAllowed(
                client.ipAddress(),
                userId.toString(),
                properties.getRateLimit().getLoginMaxAttempts(),
                properties.getRateLimit().getLoginWindow());
        return transactions.execute(status -> finishMfa(userId, code, client));
    }

    public AuthResult<AuthTokenResponse> refresh(String refreshToken, PrincipalType expected, ClientContext client) {
        return transactions.execute(status -> rotate(refreshToken, expected, client));
    }

    public MessageResponse logout(AuthenticatedUser actor, ClientContext client) {
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            sessions.revoke(actor.getSessionId(), now, "LOGOUT");
            refreshTokens.revokeSession(actor.getSessionId(), now);
            audit.record("LOGOUT", "user_session", actor.getSessionId().toString(), actor.getId().toString(), client, Map.of());
        });
        return new MessageResponse("Signed out");
    }

    public UserResponse me(AuthenticatedUser actor) {
        UserRecord user = users.findById(actor.getId()).orElseThrow(
                () -> new UnauthorizedException(ErrorCode.UNAUTHORIZED, "Authentication is required"));
        return AuthViews.user(user, profiles.load(user.id()));
    }

    public MfaSetup enroll(UUID userId, String actor) {
        String secret = tokens.totpSecret();
        users.updateMfa(userId, true, cipher.encrypt(secret), null, actor);
        UserRecord user = users.findById(userId).orElseThrow();
        return new MfaSetup(secret, otpauth(user.email(), secret));
    }

    private AuthResult<AuthTokenResponse> finishLogin(
            UUID userId,
            boolean passwordMatches,
            String identifier,
            ClientContext client,
            PrincipalType expected) {
        if (userId == null) {
            recordAttempt(null, identifier, expected, false, "INVALID_CREDENTIALS", client);
            audit.record("LOGIN_FAILED", "system_user", null, actor(null, identifier), client,
                    Map.of("reason", "INVALID_CREDENTIALS", "principalType", expected.name()));
            return invalidCredentials();
        }
        UserRecord user = users.lockById(userId).orElseThrow();
        Instant now = clock.instant();
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
            recordAttempt(user.id(), identifier, expected, false, "ACCOUNT_LOCKED", client);
            audit.record("LOGIN_FAILED", "system_user", user.id().toString(), user.id().toString(), client,
                    Map.of("reason", "ACCOUNT_LOCKED"));
            return locked();
        }
        if (!passwordMatches) {
            int prior = user.lockedUntil() != null && !user.lockedUntil().isAfter(now) ? 0 : user.failedLoginCount();
            int next = prior + 1;
            Instant lockedUntil = next >= properties.getLockout().getMaxFailures()
                    ? now.plus(properties.getLockout().getDuration())
                    : null;
            users.updateFailure(user.id(), next, lockedUntil, "auth");
            recordAttempt(user.id(), identifier, expected, false, "INVALID_CREDENTIALS", client);
            audit.record("LOGIN_FAILED", "system_user", user.id().toString(), user.id().toString(), client,
                    Map.of("reason", lockedUntil == null ? "INVALID_CREDENTIALS" : "ACCOUNT_LOCKED", "attempts", next));
            return lockedUntil == null ? invalidCredentials() : locked();
        }
        if (user.status() == UserStatus.DISABLED) {
            recordAttempt(user.id(), identifier, expected, false, "ACCOUNT_DISABLED", client);
            audit.record("LOGIN_FAILED", "system_user", user.id().toString(), user.id().toString(), client,
                    Map.of("reason", "ACCOUNT_DISABLED"));
            return AuthResult.fail(HttpStatus.FORBIDDEN, ErrorCode.ACCOUNT_DISABLED, "The account is disabled");
        }
        if (user.principalType() == PrincipalType.CUSTOMER && (!user.emailVerified() || !user.phoneVerified()
                || user.status() != UserStatus.ACTIVE)) {
            recordAttempt(user.id(), identifier, expected, false, "ACCOUNT_NOT_VERIFIED", client);
            return AuthResult.fail(
                    HttpStatus.FORBIDDEN,
                    ErrorCode.ACCOUNT_NOT_VERIFIED,
                    "Verify the email address and phone number before signing in");
        }
        if (user.passwordExpiresAt() != null && !user.passwordExpiresAt().isAfter(now)) {
            recordAttempt(user.id(), identifier, expected, false, "PASSWORD_EXPIRED", client);
            audit.record("LOGIN_FAILED", "system_user", user.id().toString(), user.id().toString(), client,
                    Map.of("reason", "PASSWORD_EXPIRED"));
            return AuthResult.fail(HttpStatus.FORBIDDEN, ErrorCode.PASSWORD_EXPIRED, "The password has expired and must be reset");
        }
        SecurityProfile profile = profiles.load(user.id());
        if (user.mfaEnabled() || profile.requiresMfa()) {
            if (!user.mfaEnabled() || user.mfaSecretEncrypted() == null) {
                return AuthResult.fail(
                        HttpStatus.FORBIDDEN,
                        ErrorCode.MFA_ENROLLMENT_REQUIRED,
                        "Multi-factor authentication must be enrolled before this account can sign in");
            }
            users.clearFailures(user.id(), "auth");
            audit.record("MFA_CHALLENGE_ISSUED", "system_user", user.id().toString(), user.id().toString(), client, Map.of());
            return AuthResult.ok(new AuthTokenResponse(null, null, null, null, null, true, jwtService.mfaToken(user.id())));
        }
        return completeLogin(user, profile, identifier, client);
    }

    private AuthResult<AuthTokenResponse> finishMfa(UUID userId, String code, ClientContext client) {
        UserRecord user = users.lockById(userId).orElse(null);
        if (user == null || user.principalType() != PrincipalType.STAFF || user.mfaSecretEncrypted() == null) {
            return AuthResult.fail(HttpStatus.UNAUTHORIZED, ErrorCode.MFA_INVALID, "The authentication code is incorrect");
        }
        Instant now = clock.instant();
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(now)) {
            return locked();
        }
        if (user.status() != UserStatus.ACTIVE) {
            return AuthResult.fail(HttpStatus.FORBIDDEN, ErrorCode.ACCOUNT_DISABLED, "The account is disabled");
        }
        String secret = cipher.decrypt(user.mfaSecretEncrypted());
        var step = totpVerifier.matchStep(secret, code, now, user.mfaLastAcceptedStep());
        if (step.isEmpty()) {
            int next = user.failedLoginCount() + 1;
            Instant lockedUntil = next >= properties.getLockout().getMaxFailures()
                    ? now.plus(properties.getLockout().getDuration())
                    : null;
            users.updateFailure(user.id(), next, lockedUntil, "auth");
            recordAttempt(user.id(), user.email(), PrincipalType.STAFF, false, "MFA_INVALID", client);
            audit.record("MFA_CHALLENGE_FAILED", "system_user", user.id().toString(), user.id().toString(), client, Map.of());
            return lockedUntil == null
                    ? AuthResult.fail(HttpStatus.UNAUTHORIZED, ErrorCode.MFA_INVALID, "The authentication code is incorrect")
                    : locked();
        }
        users.updateMfa(user.id(), true, user.mfaSecretEncrypted(), step.getAsLong(), "auth");
        return completeLogin(users.lockById(user.id()).orElseThrow(), profiles.load(user.id()), user.email(), client);
    }

    private AuthResult<AuthTokenResponse> completeLogin(
            UserRecord user,
            SecurityProfile profile,
            String identifier,
            ClientContext client) {
        users.clearFailures(user.id(), "auth");
        TokenIssuer.IssuedSession issued = tokenIssuer.issue(user, profile, client);
        recordAttempt(user.id(), identifier, user.principalType(), true, null, client);
        audit.record("LOGIN_SUCCEEDED", "user_session", issued.sessionId().toString(), user.id().toString(), client,
                Map.of("principalType", user.principalType().name()));
        return AuthResult.ok(issued.tokens());
    }

    private AuthResult<AuthTokenResponse> rotate(String refreshToken, PrincipalType expected, ClientContext client) {
        RefreshTokenRecord token = refreshTokens.lockByHash(TokenHasher.sha256(refreshToken)).orElse(null);
        if (token == null) {
            return AuthResult.fail(HttpStatus.UNAUTHORIZED, ErrorCode.UNAUTHORIZED, "The refresh token is not valid");
        }
        Instant now = clock.instant();
        if (token.replacedBy() != null) {
            refreshTokens.revokeFamily(token.familyId(), now);
            sessions.revoke(token.sessionId(), now, "REFRESH_REUSE");
            audit.record("REFRESH_TOKEN_REUSE", "refresh_token", token.id().toString(), token.userId().toString(), client,
                    Map.of("familyId", token.familyId().toString()));
            return AuthResult.fail(
                    HttpStatus.UNAUTHORIZED,
                    ErrorCode.TOKEN_REUSE,
                    "The refresh token was reused and the session has been revoked");
        }
        if (token.revokedAt() != null) {
            return AuthResult.fail(HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_REVOKED, "The refresh token has been revoked");
        }
        if (!token.expiresAt().isAfter(now)) {
            return AuthResult.fail(HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_EXPIRED, "The refresh token has expired");
        }
        SessionRecord session = sessions.findById(token.sessionId()).orElse(null);
        UserRecord user = users.lockById(token.userId()).orElse(null);
        if (session == null || !session.active(now) || user == null || user.principalType() != expected || user.status() != UserStatus.ACTIVE) {
            return AuthResult.fail(HttpStatus.UNAUTHORIZED, ErrorCode.TOKEN_REVOKED, "The refresh token has been revoked");
        }
        SecurityProfile profile = profiles.load(user.id());
        String nextToken = tokens.refreshToken();
        UUID nextId = UUID.randomUUID();
        Instant expiresAt = now.plus(tokenIssuer.refreshTtl(user.principalType()));
        refreshTokens.replace(token.id(), nextId, now);
        refreshTokens.insert(new RefreshTokenRecord(
                nextId,
                token.sessionId(),
                token.userId(),
                token.familyId(),
                TokenHasher.sha256(nextToken),
                expiresAt,
                now,
                null,
                null));
        sessions.extend(session.id(), now, expiresAt);
        audit.record("TOKEN_REFRESHED", "user_session", session.id().toString(), user.id().toString(), client, Map.of());
        String accessToken = jwtService.accessToken(
                user.id(),
                session.id(),
                user.principalType(),
                profile.roles(),
                profile.permissions(),
                profile.branchIds(),
                profile.unrestrictedBranches());
        return AuthResult.ok(new AuthTokenResponse(
                "Bearer",
                accessToken,
                nextToken,
                properties.getJwt().getAccessTokenTtl().toSeconds(),
                session.id(),
                false,
                null));
    }

    private void recordAttempt(
            UUID userId,
            String identifier,
            PrincipalType principalType,
            boolean success,
            String reason,
            ClientContext client) {
        attempts.insert(userId, identifier, principalType, success, reason, client.ipAddress(), client.userAgent(), clock.instant());
    }

    private static AuthResult<AuthTokenResponse> invalidCredentials() {
        return AuthResult.fail(HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_CREDENTIALS, INVALID_CREDENTIALS);
    }

    private static AuthResult<AuthTokenResponse> locked() {
        return AuthResult.fail(HttpStatus.LOCKED, ErrorCode.ACCOUNT_LOCKED, "The account is temporarily locked");
    }

    private static String actor(UUID userId, String identifier) {
        if (userId != null) {
            return userId.toString();
        }
        if (identifier == null || identifier.isBlank()) {
            return "anonymous";
        }
        return identifier.length() <= 128 ? identifier : identifier.substring(0, 128);
    }

    private static String otpauth(String email, String secret) {
        String issuer = URLEncoder.encode("MFB Lending", StandardCharsets.UTF_8);
        String label = URLEncoder.encode(email, StandardCharsets.UTF_8);
        return "otpauth://totp/" + issuer + ":" + label
                + "?secret=" + secret
                + "&issuer=" + issuer
                + "&algorithm=SHA1&digits=6&period=30";
    }
}
