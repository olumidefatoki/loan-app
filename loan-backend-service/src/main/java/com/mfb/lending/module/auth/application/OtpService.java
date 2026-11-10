package com.mfb.lending.module.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.config.AuthProperties;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.OtpChannel;
import com.mfb.lending.module.auth.domain.OtpPurpose;
import com.mfb.lending.module.auth.domain.OtpRecord;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.persistence.OtpRepository;
import com.mfb.lending.module.auth.persistence.UserRepository;
import com.mfb.lending.module.auth.security.OtpHasher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class OtpService {

    private final OtpRepository otps;
    private final UserRepository users;
    private final OtpHasher hasher;
    private final OtpDispatcher dispatcher;
    private final SecureTokens tokens;
    private final AuditService audit;
    private final AuthProperties properties;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public OtpService(
            OtpRepository otps,
            UserRepository users,
            OtpHasher hasher,
            OtpDispatcher dispatcher,
            SecureTokens tokens,
            AuditService audit,
            AuthProperties properties,
            RateLimiter rateLimiter,
            Clock clock,
            TransactionTemplate transactions) {
        this.otps = otps;
        this.users = users;
        this.hasher = hasher;
        this.dispatcher = dispatcher;
        this.tokens = tokens;
        this.audit = audit;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.transactions = transactions;
    }

    public void issue(UserRecord user, OtpPurpose purpose, OtpChannel channel, String destination, ClientContext client) {
        transactions.executeWithoutResult(status -> issueInTransaction(user, purpose, channel, destination, client));
    }

    public void issueInTransaction(
            UserRecord user,
            OtpPurpose purpose,
            OtpChannel channel,
            String destination,
            ClientContext client) {
        Instant now = clock.instant();
        otps.supersedeOpen(user.id(), purpose, now);
        String code = tokens.numericCode(properties.getOtp().getLength());
        otps.insert(new OtpRecord(
                UUID.randomUUID(),
                user.id(),
                purpose,
                channel,
                destination,
                hasher.hash(user.id(), purpose, code),
                now.plus(properties.getOtp().getTtl()),
                0,
                properties.getOtp().getMaxAttempts(),
                null,
                now));
        dispatcher.dispatch(user.id().toString(), purpose, channel, destination, code);
        audit.record(
                "OTP_ISSUED",
                "system_user",
                user.id().toString(),
                user.id().toString(),
                client,
                Map.of("purpose", purpose.name(), "channel", channel.name()));
    }

    public AuthResult<UserRecord> verify(String identifier, OtpPurpose purpose, String code, ClientContext client) {
        String normalized = Identifiers.normalize(identifier);
        rateLimiter.assertAllowed(
                "otp",
                client.ipAddress(),
                normalized,
                properties.getRateLimit().getLoginMaxAttempts(),
                properties.getRateLimit().getLoginWindow());
        UserRecord user = users.findByIdentifier(normalized).orElse(null);
        if (user == null) {
            return AuthResult.fail(HttpStatus.UNAUTHORIZED, ErrorCode.OTP_INVALID, "The verification code is incorrect");
        }
        return transactions.execute(status -> {
            AuthResult<OtpRecord> checked = checkInTransaction(user, purpose, code, client);
            if (checked.code() != null) {
                return AuthResult.fail(checked.status(), checked.code(), checked.message());
            }
            consumeInTransaction(checked.value(), user, client);
            return AuthResult.ok(users.findById(user.id()).orElseThrow());
        });
    }

    public AuthResult<OtpRecord> checkInTransaction(UserRecord user, OtpPurpose purpose, String code, ClientContext client) {
        OtpRecord otp = otps.findLatest(user.id(), purpose).orElse(null);
        if (otp == null) {
            return AuthResult.fail(HttpStatus.UNAUTHORIZED, ErrorCode.OTP_INVALID, "The verification code is incorrect");
        }
        boolean matches = hasher.matches(user.id(), purpose, code, otp.codeHash());
        if (otp.consumedAt() != null) {
            if (matches && otp.attemptCount() < otp.maxAttempts()) {
                return AuthResult.fail(HttpStatus.UNAUTHORIZED, ErrorCode.OTP_REUSED, "The verification code has already been used");
            }
            return invalid();
        }
        if (!matches) {
            int attempts = otp.attemptCount() + 1;
            Instant consumedAt = attempts >= otp.maxAttempts() ? clock.instant() : null;
            otps.recordFailure(otp.id(), attempts, consumedAt);
            audit.record("OTP_REJECTED", "otp_record", otp.id().toString(), user.id().toString(), client,
                    Map.of("purpose", purpose.name(), "attempts", attempts));
            return invalid();
        }
        if (!otp.expiresAt().isAfter(clock.instant())) {
            return AuthResult.fail(HttpStatus.UNAUTHORIZED, ErrorCode.OTP_EXPIRED, "The verification code has expired");
        }
        return AuthResult.ok(otp);
    }

    public void consumeInTransaction(OtpRecord otp, UserRecord user, ClientContext client) {
        otps.consume(otp.id(), clock.instant());
        if (otp.purpose() == OtpPurpose.EMAIL_VERIFICATION) {
            users.markEmailVerified(user.id());
            users.activateIfVerified(user.id());
        } else if (otp.purpose() == OtpPurpose.PHONE_VERIFICATION) {
            users.markPhoneVerified(user.id());
            users.activateIfVerified(user.id());
        }
        audit.record("OTP_VERIFIED", "system_user", user.id().toString(), user.id().toString(), client,
                Map.of("purpose", otp.purpose().name()));
    }

    private static AuthResult<OtpRecord> invalid() {
        return AuthResult.fail(HttpStatus.UNAUTHORIZED, ErrorCode.OTP_INVALID, "The verification code is incorrect");
    }
}
