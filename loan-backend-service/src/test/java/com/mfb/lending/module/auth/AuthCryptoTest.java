package com.mfb.lending.module.auth;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.UnauthorizedException;
import com.mfb.lending.config.AuthProperties;
import com.mfb.lending.module.auth.domain.OtpPurpose;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.security.AesGcmCipher;
import com.mfb.lending.module.auth.security.Base32;
import com.mfb.lending.module.auth.security.JwtService;
import com.mfb.lending.module.auth.security.OtpHasher;
import com.mfb.lending.module.auth.security.TokenHasher;
import com.mfb.lending.module.auth.security.TotpVerifier;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthCryptoTest {

    private static final String JWT_SECRET = "dGVzdC1zdWl0ZS1qd3QtaG1hYy1zZWNyZXQtMzJiISE=";
    private static final String DATA_KEY = "dGVzdC1zdWl0ZS1hZXMtMjU2LWRhdGEta2V5LTMyYiE=";

    @Test
    void secretsAreStoredOnlyAsHashesOrCiphertext() {
        OtpHasher hasher = new OtpHasher("test-suite-otp-pepper");
        UUID userId = UUID.randomUUID();
        String hash = hasher.hash(userId, OtpPurpose.EMAIL_VERIFICATION, "123456");

        assertThat(hash).hasSize(64).doesNotContain("123456");
        assertThat(hasher.matches(userId, OtpPurpose.EMAIL_VERIFICATION, "123456", hash)).isTrue();
        assertThat(hasher.matches(userId, OtpPurpose.EMAIL_VERIFICATION, "654321", hash)).isFalse();
        assertThat(hasher.hash(UUID.randomUUID(), OtpPurpose.EMAIL_VERIFICATION, "123456")).isNotEqualTo(hash);

        assertThat(TokenHasher.sha256("refresh-token")).hasSize(64).isNotEqualTo("refresh-token");
        assertThat(TokenHasher.matches("refresh-token", TokenHasher.sha256("refresh-token"))).isTrue();

        AesGcmCipher cipher = new AesGcmCipher(java.util.Base64.getDecoder().decode(DATA_KEY));
        String encrypted = cipher.encrypt("123456");
        assertThat(encrypted).doesNotContain("123456");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("123456");
    }

    @Test
    void base32RoundTripsAndTotpMatchesTheRfcVector() {
        byte[] hello = "Hello!".getBytes(StandardCharsets.UTF_8);
        assertThat(Base32.decode(Base32.encode(hello))).isEqualTo(hello);

        TotpVerifier verifier = new TotpVerifier();
        String secret = Base32.encode("12345678901234567890".getBytes(StandardCharsets.UTF_8));
        assertThat(verifier.codeAt(secret, Instant.ofEpochSecond(59))).isEqualTo("287082");

        Instant now = Instant.ofEpochSecond(59);
        assertThat(verifier.matchStep(secret, "287082", now, null).isPresent()).isTrue();
        long accepted = verifier.matchStep(secret, "287082", now, null).getAsLong();
        assertThat(verifier.matchStep(secret, "287082", now, accepted).isPresent()).isFalse();
    }

    @Test
    void accessTokensAreShortLivedAndMfaTokensCannotAccessTheApi() {
        AuthProperties properties = properties();
        Clock issuedAt = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        JwtService service = new JwtService(properties, issuedAt);
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        String accessToken = service.accessToken(
                userId,
                sessionId,
                PrincipalType.CUSTOMER,
                List.of("CUSTOMER"),
                List.of(),
                List.of(),
                false);

        JwtService.AccessClaims claims = service.parseAccessToken(accessToken);
        assertThat(claims.userId()).isEqualTo(userId);
        assertThat(claims.sessionId()).isEqualTo(sessionId);
        assertThat(claims.principalType()).isEqualTo(PrincipalType.CUSTOMER);

        String tampered = accessToken.substring(0, accessToken.length() - 2) + "aa";
        assertThatThrownBy(() -> service.parseAccessToken(tampered))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.UNAUTHORIZED);

        Clock later = Clock.fixed(issuedAt.instant().plusSeconds(20 * 60), ZoneOffset.UTC);
        assertThatThrownBy(() -> new JwtService(properties, later).parseAccessToken(accessToken))
                .isInstanceOf(UnauthorizedException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.TOKEN_EXPIRED);

        String mfaToken = service.mfaToken(userId);
        assertThatThrownBy(() -> service.parseAccessToken(mfaToken))
                .isInstanceOf(UnauthorizedException.class);
        assertThat(service.parseMfaToken(mfaToken)).isEqualTo(userId);
    }

    private static AuthProperties properties() {
        AuthProperties properties = new AuthProperties();
        properties.getJwt().setHmacSecret(JWT_SECRET);
        properties.setEncryptionKey(DATA_KEY);
        properties.getOtp().setPepper("test-suite-otp-pepper");
        properties.afterPropertiesSet();
        return properties;
    }
}
