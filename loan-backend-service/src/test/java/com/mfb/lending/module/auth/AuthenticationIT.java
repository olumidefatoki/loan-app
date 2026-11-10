package com.mfb.lending.module.auth;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.JsonNode;
import com.mfb.lending.module.auth.application.CapturedOtpStore;
import com.mfb.lending.module.auth.security.AesGcmCipher;
import com.mfb.lending.module.auth.security.TokenHasher;
import com.mfb.lending.module.auth.security.TotpVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthenticationIT {

    private static final String ADMIN_EMAIL = "admin@mfb.test";
    private static final String PASSWORD = "Str0ng-Passw0rd!";
    private static final String ADMIN_MFA_SECRET = "JBSWY3DPEHPK3PXP";

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    @Container
    @ServiceConnection
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private CapturedOtpStore capturedOtps;

    @Autowired
    private AesGcmCipher cipher;

    @Autowired
    private TotpVerifier totp;

    private String adminToken;

    @BeforeAll
    void signInAdministrator() {
        adminToken = staffSession(ADMIN_EMAIL, PASSWORD, ADMIN_MFA_SECRET).accessToken();
    }

    @BeforeEach
    void clearRateLimits() {
        var keys = redisTemplate.keys("auth:rl:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void customerCanRegisterVerifyAndUseARotatingSession() {
        Customer customer = registerVerifiedCustomer();
        JsonNode login = post("/api/v1/auth/login", Map.of(
                "identifier", customer.email(),
                "password", PASSWORD)).getBody();
        assertThat(login.path("data").path("mfaRequired").asBoolean()).isFalse();
        String accessToken = login.path("data").path("accessToken").asText();
        String refreshToken = login.path("data").path("refreshToken").asText();
        assertThat(login.path("data").path("expiresIn").asLong()).isLessThanOrEqualTo(900);

        JsonNode me = exchange("/api/v1/auth/me", HttpMethod.GET, null, accessToken);
        assertThat(me.path("data").path("id").asText()).isEqualTo(customer.id());
        assertThat(me.path("data").path("email").asText()).isEqualTo(customer.email());
        assertThat(me.path("data").path("permissions")).isEmpty();

        JsonNode refreshed = post("/api/v1/auth/refresh-token", Map.of("refreshToken", refreshToken)).getBody();
        String rotated = refreshed.path("data").path("refreshToken").asText();
        assertThat(rotated).isNotEqualTo(refreshToken);
        exchange("/api/v1/auth/logout", HttpMethod.POST, null, refreshed.path("data").path("accessToken").asText());

        ResponseEntity<JsonNode> revoked = post("/api/v1/auth/refresh-token", Map.of("refreshToken", rotated));
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revoked.getBody().path("code").asText()).isEqualTo("TOKEN_REVOKED");

        String hash = jdbc.queryForObject(
                "SELECT password_hash FROM system_users WHERE email = ?",
                String.class,
                customer.email());
        assertThat(hash).startsWith("$argon2id$").doesNotContain(PASSWORD);
        String storedOtp = jdbc.queryForObject(
                "SELECT code_hash FROM otp_records WHERE user_id = ?::uuid AND purpose = 'EMAIL_VERIFICATION'",
                String.class,
                customer.id());
        assertThat(storedOtp).isNotEqualTo(customer.emailCode());
        String payload = jdbc.queryForObject(
                """
                SELECT payload::text FROM integration_outbox
                WHERE aggregate_id = ? AND event_type = 'OtpRequested' AND payload::text LIKE '%EMAIL_VERIFICATION%'
                ORDER BY created_at DESC LIMIT 1
                """,
                String.class,
                customer.id());
        assertThat(payload).doesNotContain("\"" + customer.emailCode() + "\"");
        assertThat(cipher.decrypt(payload.replaceAll(".*\"ciphertext\"\\s*:\\s*\"([^\"]+)\".*", "$1")))
                .isEqualTo(customer.emailCode());
    }

    @Test
    void invalidPasswordExpiredOtpAndReusedOtpAreRejected() {
        Customer customer = registerCustomer();
        ResponseEntity<JsonNode> invalid = post("/api/v1/auth/login", Map.of(
                "identifier", customer.email(),
                "password", "Wrong-Passw0rd!"));
        assertThat(invalid.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(invalid.getBody().path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        Integer failures = jdbc.queryForObject(
                "SELECT COUNT(*) FROM login_attempts WHERE identifier = ? AND success = FALSE",
                Integer.class,
                customer.email());
        assertThat(failures).isEqualTo(1);

        jdbc.update(
                "UPDATE otp_records SET expires_at = now() - interval '1 minute' WHERE user_id = ?::uuid AND purpose = 'EMAIL_VERIFICATION'",
                customer.id());
        ResponseEntity<JsonNode> expired = post("/api/v1/auth/verify-otp", Map.of(
                "identifier", customer.email(),
                "purpose", "EMAIL_VERIFICATION",
                "code", customer.emailCode()));
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(expired.getBody().path("code").asText()).isEqualTo("OTP_EXPIRED");

        ResponseEntity<JsonNode> phone = post("/api/v1/auth/verify-otp", Map.of(
                "identifier", customer.phone(),
                "purpose", "PHONE_VERIFICATION",
                "code", customer.phoneCode()));
        assertThat(phone.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> reused = post("/api/v1/auth/verify-otp", Map.of(
                "identifier", customer.phone(),
                "purpose", "PHONE_VERIFICATION",
                "code", customer.phoneCode()));
        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reused.getBody().path("code").asText()).isEqualTo("OTP_REUSED");
    }

    @Test
    void expiredAndReusedRefreshTokensAreRejected() {
        Customer customer = registerVerifiedCustomer();
        JsonNode login = post("/api/v1/auth/login", Map.of(
                "identifier", customer.email(),
                "password", PASSWORD)).getBody().path("data");
        String refreshToken = login.path("refreshToken").asText();
        jdbc.update(
                "UPDATE refresh_tokens SET expires_at = now() - interval '1 minute' WHERE token_hash = ?",
                TokenHasher.sha256(refreshToken));
        ResponseEntity<JsonNode> expired = post("/api/v1/auth/refresh-token", Map.of("refreshToken", refreshToken));
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(expired.getBody().path("code").asText()).isEqualTo("TOKEN_EXPIRED");

        JsonNode fresh = post("/api/v1/auth/login", Map.of(
                "identifier", customer.email(),
                "password", PASSWORD)).getBody().path("data");
        String first = fresh.path("refreshToken").asText();
        String second = post("/api/v1/auth/refresh-token", Map.of("refreshToken", first))
                .getBody().path("data").path("refreshToken").asText();
        String third = post("/api/v1/auth/refresh-token", Map.of("refreshToken", second))
                .getBody().path("data").path("refreshToken").asText();
        ResponseEntity<JsonNode> reuse = post("/api/v1/auth/refresh-token", Map.of("refreshToken", first));
        assertThat(reuse.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(reuse.getBody().path("code").asText()).isEqualTo("TOKEN_REUSE");
        ResponseEntity<JsonNode> family = post("/api/v1/auth/refresh-token", Map.of("refreshToken", third));
        assertThat(family.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(family.getBody().path("code").asText()).isIn("TOKEN_REVOKED", "TOKEN_REUSE");
    }

    @Test
    void repeatedFailuresLockTheAccountAndTheAddressIsRateLimited() {
        Customer customer = registerVerifiedCustomer();
        for (int attempt = 1; attempt <= 4; attempt++) {
            ResponseEntity<JsonNode> response = post("/api/v1/auth/login", Map.of(
                    "identifier", customer.email(),
                    "password", "Wrong-Passw0rd!"));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().path("code").asText()).isEqualTo("INVALID_CREDENTIALS");
        }
        ResponseEntity<JsonNode> locked = post("/api/v1/auth/login", Map.of(
                "identifier", customer.email(),
                "password", "Wrong-Passw0rd!"));
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);
        assertThat(locked.getBody().path("code").asText()).isEqualTo("ACCOUNT_LOCKED");
        ResponseEntity<JsonNode> stillLocked = post("/api/v1/auth/login", Map.of(
                "identifier", customer.email(),
                "password", PASSWORD));
        assertThat(stillLocked.getStatusCode()).isEqualTo(HttpStatus.LOCKED);

        clearRateLimits();
        for (int attempt = 0; attempt < 10; attempt++) {
            ResponseEntity<JsonNode> response = post("/api/v1/auth/login", Map.of(
                    "identifier", "missing-" + attempt + "-" + UUID.randomUUID() + "@example.com",
                    "password", PASSWORD));
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        }
        ResponseEntity<JsonNode> limited = post("/api/v1/auth/login", Map.of(
                "identifier", "missing-final-" + UUID.randomUUID() + "@example.com",
                "password", PASSWORD));
        assertThat(limited.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(limited.getBody().path("code").asText()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void authorizationStopsCustomersOtherBranchesAndPrivilegeEscalation() {
        Customer first = registerVerifiedCustomer();
        Customer second = registerVerifiedCustomer();
        String firstToken = post("/api/v1/auth/login", Map.of(
                "identifier", first.email(),
                "password", PASSWORD)).getBody().path("data").path("accessToken").asText();

        ResponseEntity<JsonNode> anonymous = exchangeRaw("/api/v1/admin/users", HttpMethod.GET, null, null);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        ResponseEntity<JsonNode> otherCustomer = exchangeRaw(
                "/api/v1/admin/users/" + second.id(),
                HttpMethod.GET,
                null,
                firstToken);
        assertThat(otherCustomer.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(otherCustomer.getBody().toString()).doesNotContain(second.email());

        UUID branchA = insertBranch("A" + UUID.randomUUID().toString().substring(0, 6));
        UUID branchB = insertBranch("B" + UUID.randomUUID().toString().substring(0, 6));
        Staff managerA = createStaff("BRANCH_MANAGER", branchA);
        Staff managerB = createStaff("BRANCH_MANAGER", branchB);
        Staff officerA = createStaff("RELATIONSHIP_OFFICER", branchA);

        ResponseEntity<JsonNode> outsideBranch = exchangeRaw(
                "/api/v1/admin/users/" + managerB.userId(),
                HttpMethod.GET,
                null,
                managerA.accessToken());
        assertThat(outsideBranch.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> insideBranch = exchangeRaw(
                "/api/v1/admin/users/" + officerA.userId(),
                HttpMethod.GET,
                null,
                managerA.accessToken());
        assertThat(insideBranch.getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> escalation = exchangeRaw(
                "/api/v1/admin/users/" + officerA.userId() + "/roles",
                HttpMethod.POST,
                Map.of("roleCodes", java.util.List.of("SYSTEM_ADMIN")),
                managerA.accessToken());
        assertThat(escalation.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> officerEscalation = exchangeRaw(
                "/api/v1/admin/users/" + managerA.userId() + "/roles",
                HttpMethod.POST,
                Map.of("roleCodes", java.util.List.of("SYSTEM_ADMIN")),
                officerA.accessToken());
        assertThat(officerEscalation.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        ResponseEntity<JsonNode> staffRefreshOnCustomerApi = post(
                "/api/v1/auth/refresh-token",
                Map.of("refreshToken", managerA.refreshToken()));
        assertThat(staffRefreshOnCustomerApi.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void staffPasswordExpiryDeactivationAndResetAreEnforced() {
        UUID branchId = insertBranch("C" + UUID.randomUUID().toString().substring(0, 6));
        Staff officer = createStaff("RELATIONSHIP_OFFICER", branchId);
        jdbc.update(
                "UPDATE system_users SET password_expires_at = now() - interval '1 day' WHERE id = ?::uuid",
                officer.userId());
        ResponseEntity<JsonNode> expired = post("/api/v1/admin/auth/login", Map.of(
                "identifier", officer.email(),
                "password", PASSWORD));
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(expired.getBody().path("code").asText()).isEqualTo("PASSWORD_EXPIRED");

        post("/api/v1/auth/forgot-password", Map.of("identifier", officer.email()));
        String code = capturedOtps.latest(officer.email(), "PASSWORD_RESET");
        assertThat(code).isNotBlank();
        ResponseEntity<JsonNode> reset = post("/api/v1/auth/reset-password", Map.of(
                "identifier", officer.email(),
                "code", code,
                "newPassword", "N3w-Str0ng-Pass!"));
        assertThat(reset.getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<JsonNode> oldPassword = post("/api/v1/admin/auth/login", Map.of(
                "identifier", officer.email(),
                "password", PASSWORD));
        assertThat(oldPassword.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        JsonNode signedIn = post("/api/v1/admin/auth/login", Map.of(
                "identifier", officer.email(),
                "password", "N3w-Str0ng-Pass!")).getBody().path("data");
        exchangeRaw(
                "/api/v1/admin/users/" + officer.userId(),
                HttpMethod.DELETE,
                null,
                adminToken);
        ResponseEntity<JsonNode> disabled = exchangeRaw("/api/v1/auth/me", HttpMethod.GET, null, signedIn.path("accessToken").asText());
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        ResponseEntity<JsonNode> disabledLogin = post("/api/v1/admin/auth/login", Map.of(
                "identifier", officer.email(),
                "password", "N3w-Str0ng-Pass!"));
        assertThat(disabledLogin.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(disabledLogin.getBody().path("code").asText()).isEqualTo("ACCOUNT_DISABLED");
    }

    private Customer registerVerifiedCustomer() {
        Customer customer = registerCustomer();
        assertThat(post("/api/v1/auth/verify-otp", Map.of(
                "identifier", customer.email(),
                "purpose", "EMAIL_VERIFICATION",
                "code", customer.emailCode())).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(post("/api/v1/auth/verify-otp", Map.of(
                "identifier", customer.phone(),
                "purpose", "PHONE_VERIFICATION",
                "code", customer.phoneCode())).getStatusCode()).isEqualTo(HttpStatus.OK);
        return customer;
    }

    private Customer registerCustomer() {
        String email = "customer." + UUID.randomUUID() + "@example.com";
        String phone = uniquePhone();
        ResponseEntity<JsonNode> response = post("/api/v1/auth/register", Map.of(
                "customerType", "INDIVIDUAL",
                "fullName", "Ada Customer",
                "email", email,
                "phoneNumber", phone,
                "password", PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String emailCode = capturedOtps.latest(email, "EMAIL_VERIFICATION");
        String phoneCode = capturedOtps.latest(phone, "PHONE_VERIFICATION");
        assertThat(emailCode).hasSize(6);
        assertThat(phoneCode).hasSize(6);
        return new Customer(response.getBody().path("data").path("userId").asText(), email, phone, emailCode, phoneCode);
    }

    private Staff createStaff(String role, UUID branchId) {
        String email = role.toLowerCase() + "." + UUID.randomUUID() + "@mfb.test";
        String phone = uniquePhone();
        ResponseEntity<JsonNode> response = exchangeRaw(
                "/api/v1/admin/users",
                HttpMethod.POST,
                Map.of(
                        "fullName", "Branch Staff",
                        "email", email,
                        "phoneNumber", phone,
                        "password", PASSWORD,
                        "roleCodes", java.util.List.of(role),
                        "branchIds", java.util.List.of(branchId)),
                adminToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String userId = response.getBody().path("data").path("user").path("id").asText();
        JsonNode mfa = response.getBody().path("data").path("mfaSetup");
        String secret = mfa.isMissingNode() || mfa.isNull() ? null : mfa.path("secret").asText();
        Session session;
        if (secret == null || secret.isBlank()) {
            JsonNode tokens = post("/api/v1/admin/auth/login", Map.of("identifier", email, "password", PASSWORD))
                    .getBody().path("data");
            assertThat(tokens.path("mfaRequired").asBoolean()).isFalse();
            session = new Session(tokens.path("accessToken").asText(), tokens.path("refreshToken").asText());
        } else {
            session = staffSession(email, PASSWORD, secret);
        }
        return new Staff(userId, email, session.accessToken(), session.refreshToken());
    }

    private Session staffSession(String email, String password, String secret) {
        JsonNode challenge = post("/api/v1/admin/auth/login", Map.of("identifier", email, "password", password))
                .getBody().path("data");
        assertThat(challenge.path("mfaRequired").asBoolean()).isTrue();
        String code = totp.codeAt(secret, Instant.now());
        JsonNode tokens = post("/api/v1/admin/auth/mfa/verify", Map.of(
                "mfaToken", challenge.path("mfaToken").asText(),
                "code", code)).getBody().path("data");
        assertThat(tokens.path("accessToken").asText()).isNotBlank();
        assertThat(tokens.path("refreshToken").asText()).isNotBlank();
        return new Session(tokens.path("accessToken").asText(), tokens.path("refreshToken").asText());
    }

    private UUID insertBranch(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO branches (id, code, name, created_by) VALUES (?, ?, ?, ?)", id, code, code, "auth-it");
        return id;
    }

    private static String uniquePhone() {
        return "+2348" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    private ResponseEntity<JsonNode> post(String path, Object body) {
        return exchangeRaw(path, HttpMethod.POST, body, null);
    }

    private JsonNode exchange(String path, HttpMethod method, Object body, String accessToken) {
        ResponseEntity<JsonNode> response = exchangeRaw(path, method, body, accessToken);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> exchangeRaw(String path, HttpMethod method, Object body, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private record Customer(String id, String email, String phone, String emailCode, String phoneCode) {
    }

    private record Staff(String userId, String email, String accessToken, String refreshToken) {
    }

    private record Session(String accessToken, String refreshToken) {
    }
}
