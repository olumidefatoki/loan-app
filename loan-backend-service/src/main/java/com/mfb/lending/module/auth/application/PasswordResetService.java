package com.mfb.lending.module.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.config.AuthProperties;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.OtpChannel;
import com.mfb.lending.module.auth.domain.OtpPurpose;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import com.mfb.lending.module.auth.dto.MessageResponse;
import com.mfb.lending.module.auth.persistence.RefreshTokenRepository;
import com.mfb.lending.module.auth.persistence.SessionRepository;
import com.mfb.lending.module.auth.persistence.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PasswordResetService {

    private static final String GENERIC = "If the account exists, a verification code has been sent";

    private final UserRepository users;
    private final SessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final OtpService otps;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final AuthProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public PasswordResetService(
            UserRepository users,
            SessionRepository sessions,
            RefreshTokenRepository refreshTokens,
            OtpService otps,
            PasswordEncoder passwordEncoder,
            AuditService audit,
            RateLimiter rateLimiter,
            AuthProperties properties,
            Clock clock,
            TransactionTemplate transactions) {
        this.users = users;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.otps = otps;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
        this.transactions = transactions;
    }

    public MessageResponse forgot(String identifier, ClientContext client) {
        String normalized = Identifiers.normalize(identifier);
        rateLimiter.assertAllowed(
                "forgot-password",
                client.ipAddress(),
                normalized,
                properties.getRateLimit().getRegisterMaxAttempts(),
                properties.getRateLimit().getRegisterWindow());
        transactions.executeWithoutResult(status -> {
            UserRecord user = users.findByIdentifier(normalized).orElse(null);
            if (user == null || user.status() == UserStatus.DISABLED) {
                return;
            }
            OtpChannel channel = Identifiers.isEmail(normalized) ? OtpChannel.EMAIL : OtpChannel.SMS;
            otps.issueInTransaction(user, OtpPurpose.PASSWORD_RESET, channel, normalized, client);
            audit.record("PASSWORD_RESET_REQUESTED", "system_user", user.id().toString(), user.id().toString(), client, Map.of());
        });
        return new MessageResponse(GENERIC);
    }

    public AuthResult<MessageResponse> reset(String identifier, String code, String newPassword, ClientContext client) {
        String normalized = Identifiers.normalize(identifier);
        rateLimiter.assertAllowed(
                "reset-password",
                client.ipAddress(),
                normalized,
                properties.getRateLimit().getLoginMaxAttempts(),
                properties.getRateLimit().getLoginWindow());
        UserRecord existing = users.findByIdentifier(normalized).orElse(null);
        PasswordPolicy.check(newPassword, existing == null ? normalized : existing.email(), existing == null ? null : existing.phoneNumber());
        return transactions.execute(status -> resetInTransaction(existing, normalized, code, newPassword, client));
    }

    private AuthResult<MessageResponse> resetInTransaction(
            UserRecord existing,
            String identifier,
            String code,
            String newPassword,
            ClientContext client) {
        if (existing == null) {
            return AuthResult.fail(HttpStatus.UNAUTHORIZED, ErrorCode.OTP_INVALID, "The verification code is incorrect");
        }
        UserRecord user = users.lockById(existing.id()).orElseThrow();
        if (user.status() == UserStatus.DISABLED) {
            return AuthResult.fail(HttpStatus.FORBIDDEN, ErrorCode.ACCOUNT_DISABLED, "The account is disabled");
        }
        AuthResult<com.mfb.lending.module.auth.domain.OtpRecord> checked =
                otps.checkInTransaction(user, OtpPurpose.PASSWORD_RESET, code, client);
        if (checked.code() != null) {
            return AuthResult.fail(checked.status(), checked.code(), checked.message());
        }
        if (passwordEncoder.matches(newPassword, user.passwordHash())) {
            throw ValidationException.field("newPassword", "Choose a different password");
        }
        otps.consumeInTransaction(checked.value(), user, client);
        Instant now = clock.instant();
        Instant expiresAt = user.principalType() == PrincipalType.STAFF
                ? now.plus(properties.getPassword().getStaffMaxAge())
                : null;
        users.updatePassword(user.id(), passwordEncoder.encode(newPassword), now, expiresAt);
        sessions.revokeAll(user.id(), now, "PASSWORD_RESET");
        refreshTokens.revokeUser(user.id(), now);
        audit.record("PASSWORD_RESET", "system_user", user.id().toString(), user.id().toString(), client, Map.of());
        return AuthResult.ok(new MessageResponse("Password updated"));
    }
}
