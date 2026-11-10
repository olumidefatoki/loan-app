package com.mfb.lending.config;

import java.time.Duration;
import java.util.Base64;

import com.mfb.lending.module.auth.security.Base32;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.beans.factory.InitializingBean;

@ConfigurationProperties(prefix = "app.auth")
public class AuthProperties implements InitializingBean {

    private final Jwt jwt = new Jwt();
    private final Password password = new Password();
    private final Lockout lockout = new Lockout();
    private final Otp otp = new Otp();
    private final RateLimit rateLimit = new RateLimit();
    private final Bootstrap bootstrap = new Bootstrap();
    private String encryptionKey;
    private byte[] hmacSecretBytes = new byte[0];
    private byte[] encryptionKeyBytes = new byte[0];

    @Override
    public void afterPropertiesSet() {
        hmacSecretBytes = decodeRequired(jwt.hmacSecret, "app.auth.jwt.hmac-secret", 32);
        encryptionKeyBytes = decodeRequired(encryptionKey, "app.auth.encryption-key", 32);
        if (encryptionKeyBytes.length != 32) {
            throw new IllegalStateException("app.auth.encryption-key must decode to 32 bytes");
        }
        if (otp.pepper == null || otp.pepper.length() < 16) {
            throw new IllegalStateException("app.auth.otp.pepper must be at least 16 characters");
        }
        if (otp.length < 6 || otp.length > 8) {
            throw new IllegalStateException("app.auth.otp.length must be between 6 and 8");
        }
        if (otp.maxAttempts < 1 || otp.maxAttempts > 10) {
            throw new IllegalStateException("app.auth.otp.max-attempts must be between 1 and 10");
        }
        if (jwt.accessTokenTtl == null || jwt.accessTokenTtl.isZero() || jwt.accessTokenTtl.isNegative()
                || jwt.accessTokenTtl.compareTo(Duration.ofMinutes(30)) > 0) {
            throw new IllegalStateException("app.auth.jwt.access-token-ttl must be greater than zero and at most 30 minutes");
        }
        if (jwt.refreshTokenTtl == null || jwt.refreshTokenTtl.compareTo(jwt.accessTokenTtl) <= 0) {
            throw new IllegalStateException("app.auth.jwt.refresh-token-ttl must be longer than the access token");
        }
        if (jwt.staffRefreshTokenTtl == null || jwt.staffRefreshTokenTtl.compareTo(jwt.accessTokenTtl) <= 0) {
            throw new IllegalStateException("app.auth.jwt.staff-refresh-token-ttl must be longer than the access token");
        }
        if (jwt.mfaTokenTtl == null || jwt.mfaTokenTtl.isZero() || jwt.mfaTokenTtl.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalStateException("app.auth.jwt.mfa-token-ttl must be greater than zero and at most 10 minutes");
        }
        if (lockout.maxFailures < 3) {
            throw new IllegalStateException("app.auth.lockout.max-failures must be at least 3");
        }
        if (rateLimit.loginMaxAttempts < 3) {
            throw new IllegalStateException("app.auth.rate-limit.login-max-attempts must be at least 3");
        }
        if (bootstrap.password != null && !bootstrap.password.isBlank()) {
            require(bootstrap.email, "app.auth.bootstrap.email");
            require(bootstrap.phoneNumber, "app.auth.bootstrap.phone-number");
            require(bootstrap.mfaSecret, "app.auth.bootstrap.mfa-secret");
            try {
                Base32.decode(bootstrap.mfaSecret);
            } catch (IllegalArgumentException ex) {
                throw new IllegalStateException("app.auth.bootstrap.mfa-secret must be Base32", ex);
            }
        }
    }

    public byte[] hmacSecretBytes() {
        return hmacSecretBytes;
    }

    public byte[] encryptionKeyBytes() {
        return encryptionKeyBytes;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Password getPassword() {
        return password;
    }

    public Lockout getLockout() {
        return lockout;
    }

    public Otp getOtp() {
        return otp;
    }

    public RateLimit getRateLimit() {
        return rateLimit;
    }

    public Bootstrap getBootstrap() {
        return bootstrap;
    }

    public String getEncryptionKey() {
        return encryptionKey;
    }

    public void setEncryptionKey(String encryptionKey) {
        this.encryptionKey = encryptionKey;
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required when a bootstrap password is set");
        }
    }

    private static byte[] decodeRequired(String value, String name, int minimumBytes) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(name + " must be Base64", ex);
        }
        if (decoded.length < minimumBytes) {
            throw new IllegalStateException(name + " must decode to at least " + minimumBytes + " bytes");
        }
        return decoded;
    }

    public static class Jwt {
        private String issuer = "mfb-lending";
        private String audience = "lending-api";
        private String hmacSecret;
        private Duration accessTokenTtl = Duration.ofMinutes(15);
        private Duration refreshTokenTtl = Duration.ofDays(7);
        private Duration staffRefreshTokenTtl = Duration.ofHours(12);
        private Duration mfaTokenTtl = Duration.ofMinutes(5);

        public String getIssuer() {
            return issuer;
        }

        public void setIssuer(String issuer) {
            this.issuer = issuer;
        }

        public String getAudience() {
            return audience;
        }

        public void setAudience(String audience) {
            this.audience = audience;
        }

        public String getHmacSecret() {
            return hmacSecret;
        }

        public void setHmacSecret(String hmacSecret) {
            this.hmacSecret = hmacSecret;
        }

        public Duration getAccessTokenTtl() {
            return accessTokenTtl;
        }

        public void setAccessTokenTtl(Duration accessTokenTtl) {
            this.accessTokenTtl = accessTokenTtl;
        }

        public Duration getRefreshTokenTtl() {
            return refreshTokenTtl;
        }

        public void setRefreshTokenTtl(Duration refreshTokenTtl) {
            this.refreshTokenTtl = refreshTokenTtl;
        }

        public Duration getStaffRefreshTokenTtl() {
            return staffRefreshTokenTtl;
        }

        public void setStaffRefreshTokenTtl(Duration staffRefreshTokenTtl) {
            this.staffRefreshTokenTtl = staffRefreshTokenTtl;
        }

        public Duration getMfaTokenTtl() {
            return mfaTokenTtl;
        }

        public void setMfaTokenTtl(Duration mfaTokenTtl) {
            this.mfaTokenTtl = mfaTokenTtl;
        }
    }

    public static class Password {
        private Duration staffMaxAge = Duration.ofDays(90);

        public Duration getStaffMaxAge() {
            return staffMaxAge;
        }

        public void setStaffMaxAge(Duration staffMaxAge) {
            this.staffMaxAge = staffMaxAge;
        }
    }

    public static class Lockout {
        private int maxFailures = 5;
        private Duration duration = Duration.ofMinutes(15);

        public int getMaxFailures() {
            return maxFailures;
        }

        public void setMaxFailures(int maxFailures) {
            this.maxFailures = maxFailures;
        }

        public Duration getDuration() {
            return duration;
        }

        public void setDuration(Duration duration) {
            this.duration = duration;
        }
    }

    public static class Otp {
        private Duration ttl = Duration.ofMinutes(5);
        private int length = 6;
        private int maxAttempts = 5;
        private String pepper;
        private boolean captureEnabled;

        public Duration getTtl() {
            return ttl;
        }

        public void setTtl(Duration ttl) {
            this.ttl = ttl;
        }

        public int getLength() {
            return length;
        }

        public void setLength(int length) {
            this.length = length;
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public void setMaxAttempts(int maxAttempts) {
            this.maxAttempts = maxAttempts;
        }

        public String getPepper() {
            return pepper;
        }

        public void setPepper(String pepper) {
            this.pepper = pepper;
        }

        public boolean isCaptureEnabled() {
            return captureEnabled;
        }

        public void setCaptureEnabled(boolean captureEnabled) {
            this.captureEnabled = captureEnabled;
        }
    }

    public static class RateLimit {
        private int loginMaxAttempts = 10;
        private Duration loginWindow = Duration.ofMinutes(15);
        private int registerMaxAttempts = 5;
        private Duration registerWindow = Duration.ofHours(1);

        public int getLoginMaxAttempts() {
            return loginMaxAttempts;
        }

        public void setLoginMaxAttempts(int loginMaxAttempts) {
            this.loginMaxAttempts = loginMaxAttempts;
        }

        public Duration getLoginWindow() {
            return loginWindow;
        }

        public void setLoginWindow(Duration loginWindow) {
            this.loginWindow = loginWindow;
        }

        public int getRegisterMaxAttempts() {
            return registerMaxAttempts;
        }

        public void setRegisterMaxAttempts(int registerMaxAttempts) {
            this.registerMaxAttempts = registerMaxAttempts;
        }

        public Duration getRegisterWindow() {
            return registerWindow;
        }

        public void setRegisterWindow(Duration registerWindow) {
            this.registerWindow = registerWindow;
        }
    }

    public static class Bootstrap {
        private String email = "";
        private String password = "";
        private String phoneNumber = "";
        private String fullName = "Platform Administrator";
        private String mfaSecret = "";

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public String getPhoneNumber() {
            return phoneNumber;
        }

        public void setPhoneNumber(String phoneNumber) {
            this.phoneNumber = phoneNumber;
        }

        public String getFullName() {
            return fullName;
        }

        public void setFullName(String fullName) {
            this.fullName = fullName;
        }

        public String getMfaSecret() {
            return mfaSecret;
        }

        public void setMfaSecret(String mfaSecret) {
            this.mfaSecret = mfaSecret;
        }
    }
}
