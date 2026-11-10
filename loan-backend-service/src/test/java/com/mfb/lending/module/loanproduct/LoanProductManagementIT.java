package com.mfb.lending.module.loanproduct;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.JsonNode;
import com.mfb.lending.module.auth.application.CapturedOtpStore;
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
import org.springframework.dao.DataAccessException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LoanProductManagementIT {

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
    void productCanBeApprovedVersionedSuspendedAndStillKeepBookedTerms() {
        UUID branchId = insertBranch("LP-" + UUID.randomUUID().toString().substring(0, 8));
        Staff maker = createStaff("BRANCH_MANAGER", branchId);
        Staff checker = createStaff("CREDIT_OFFICER", branchId);
        CustomerSession customer = verifiedCustomer();
        String code = productCode();

        JsonNode created = exchange(maker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products",
                createBody(code, "SALARY_ADVANCE", terms("9.50")), HttpStatus.CREATED).path("data");
        String productId = created.path("id").asText();
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("currentVersion").path("versionNumber").asInt()).isEqualTo(1);
        exchange(customer.accessToken(), HttpMethod.GET, "/api/v1/loan-products/" + productId, null, HttpStatus.NOT_FOUND);

        JsonNode revised = exchange(maker.accessToken(), HttpMethod.PUT, "/api/v1/admin/loan-products/" + productId,
                terms("8.75"), HttpStatus.OK).path("data");
        assertThat(new BigDecimal(revised.path("currentVersion").path("interestRate").asText())).isEqualByComparingTo("8.75");
        exchange(maker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products/" + productId + "/submit", Map.of(), HttpStatus.OK);
        exchange(maker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products/" + productId + "/approve", Map.of(), HttpStatus.FORBIDDEN);

        JsonNode approved = exchange(checker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products/" + productId + "/approve",
                Map.of(), HttpStatus.OK).path("data");
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        JsonNode active = exchange(checker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products/" + productId + "/activate",
                Map.of(), HttpStatus.OK).path("data");
        assertThat(active.path("status").asText()).isEqualTo("ACTIVE");
        String versionId = active.path("currentVersion").path("id").asText();
        assertThat(active.path("currentVersion").path("effectiveFrom").asText()).isNotBlank();

        JsonNode calculator = exchange(customer.accessToken(), HttpMethod.GET,
                "/api/v1/loan-products/" + productId + "/calculator-config", null, HttpStatus.OK).path("data");
        assertThat(calculator.path("interestMethod").asText()).isEqualTo("REDUCING_BALANCE");
        assertThat(calculator.path("dayCountConvention").asText()).isEqualTo("ACTUAL_365");
        assertThat(calculator.path("repaymentFrequency").asText()).isEqualTo("MONTHLY");
        assertThat(new BigDecimal(calculator.path("interestRate").asText())).isEqualByComparingTo("8.75");
        JsonNode catalogue = exchange(customer.accessToken(), HttpMethod.GET,
                "/api/v1/loan-products?category=SALARY_ADVANCE", null, HttpStatus.OK);
        assertThat(catalogue.path("data").path("items").findValuesAsText("productCode")).contains(code);

        jdbc.update(
                "INSERT INTO loan_agreements (id, product_id, product_version_id, terms_snapshot) VALUES (?::uuid, ?::uuid, ?::uuid, ?::jsonb)",
                UUID.randomUUID().toString(),
                productId,
                versionId,
                "{\"interestRate\":\"8.75\"}");

        exchange(maker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products/" + productId + "/versions",
                Map.of("changeReason", "Annual price review", "terms", terms("12.25")), HttpStatus.CREATED);
        exchange(maker.accessToken(), HttpMethod.PUT, "/api/v1/admin/loan-products/" + productId, terms("11.50"), HttpStatus.OK);
        assertThat(jdbc.queryForObject(
                "SELECT interest_rate FROM loan_product_versions WHERE id = ?::uuid", BigDecimal.class, versionId))
                .isEqualByComparingTo("8.75");
        exchange(maker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products/" + productId + "/submit", Map.of(), HttpStatus.OK);
        exchange(checker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products/" + productId + "/approve", Map.of(), HttpStatus.OK);
        JsonNode second = exchange(checker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products/" + productId + "/activate",
                Map.of(), HttpStatus.OK).path("data");
        assertThat(second.path("currentVersion").path("versionNumber").asInt()).isEqualTo(2);
        assertThat(new BigDecimal(second.path("currentVersion").path("interestRate").asText())).isEqualByComparingTo("11.50");

        assertThat(jdbc.queryForObject(
                "SELECT interest_rate FROM loan_product_versions WHERE id = ?::uuid", BigDecimal.class, versionId))
                .isEqualByComparingTo("8.75");
        assertThat(jdbc.queryForObject(
                "SELECT terms_snapshot ->> 'interestRate' FROM loan_agreements WHERE product_version_id = ?::uuid",
                String.class,
                versionId)).isEqualTo("8.75");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE loan_product_versions SET interest_rate = 1 WHERE id = ?::uuid", versionId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE loan_agreements SET terms_snapshot = '{\"interestRate\":\"1\"}'::jsonb WHERE product_version_id = ?::uuid",
                versionId))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject(
                "SELECT interest_rate FROM loan_product_versions WHERE id = ?::uuid", BigDecimal.class, versionId))
                .isEqualByComparingTo("8.75");

        JsonNode history = exchange(maker.accessToken(), HttpMethod.GET,
                "/api/v1/admin/loan-products/" + productId + "/versions", null, HttpStatus.OK).path("data");
        assertThat(history).hasSize(2);
        assertThat(history.findValuesAsText("status")).contains("SUPERSEDED", "ACTIVE");

        exchange(checker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products/" + productId + "/suspend",
                Map.of("reason", "Pricing review"), HttpStatus.OK);
        JsonNode afterSuspension = exchange(customer.accessToken(), HttpMethod.GET,
                "/api/v1/loan-products?category=SALARY_ADVANCE", null, HttpStatus.OK);
        assertThat(afterSuspension.path("data").path("items").findValuesAsText("productCode")).doesNotContain(code);
        exchange(customer.accessToken(), HttpMethod.GET,
                "/api/v1/loan-products/" + productId + "/calculator-config", null, HttpStatus.NOT_FOUND);

        JsonNode deactivated = exchange(checker.accessToken(), HttpMethod.POST,
                "/api/v1/admin/loan-products/" + productId + "/deactivate",
                Map.of("reason", "Product retired"), HttpStatus.OK).path("data");
        assertThat(deactivated.path("status").asText()).isEqualTo("DEACTIVATED");
        assertThat(jdbc.queryForObject(
                "SELECT interest_rate FROM loan_product_versions WHERE id = ?::uuid", BigDecimal.class, versionId))
                .isEqualByComparingTo("8.75");

        List<String> actions = jdbc.queryForList(
                "SELECT action FROM audit_logs WHERE entity_type = 'loan_product' AND entity_id = ? ORDER BY occurred_at",
                String.class,
                productId);
        assertThat(actions).contains(
                "LOAN_PRODUCT_CREATED",
                "LOAN_PRODUCT_UPDATED",
                "LOAN_PRODUCT_SUBMITTED",
                "LOAN_PRODUCT_APPROVED",
                "LOAN_PRODUCT_ACTIVATED",
                "LOAN_PRODUCT_VERSION_CREATED",
                "LOAN_PRODUCT_SUSPENDED",
                "LOAN_PRODUCT_DEACTIVATED");
    }

    @Test
    void invalidAmountsInterestAndDuplicateCodesAreRejected() {
        UUID branchId = insertBranch("BAD-" + UUID.randomUUID().toString().substring(0, 6));
        Staff maker = createStaff("BRANCH_MANAGER", branchId);
        Staff operations = createStaff("OPERATIONS_OFFICER", branchId);
        String code = productCode();

        JsonNode amounts = exchange(maker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products",
                createBody(code, "PERSONAL", terms("5000.00", "1000.00", "9.50")), HttpStatus.BAD_REQUEST);
        assertThat(amounts.path("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(amounts.path("errors").toString()).contains("maximumAmount");

        JsonNode interest = exchange(maker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products",
                createBody(code, "PERSONAL", terms("1000.00", "5000.00", "150")), HttpStatus.BAD_REQUEST);
        assertThat(interest.path("errors").toString()).contains("interestRate");

        JsonNode negative = exchange(maker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products",
                createBody(code, "PERSONAL", terms("1000.00", "5000.00", "-1")), HttpStatus.BAD_REQUEST);
        assertThat(negative.path("code").asText()).isEqualTo("VALIDATION_ERROR");

        exchange(maker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products",
                createBody(code, "EMERGENCY", terms("9.50")), HttpStatus.CREATED);
        JsonNode duplicate = exchange(maker.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products",
                createBody(code, "EMERGENCY", terms("9.50")), HttpStatus.CONFLICT);
        assertThat(duplicate.path("code").asText()).isEqualTo("CONFLICT");
        exchange(operations.accessToken(), HttpMethod.POST, "/api/v1/admin/loan-products",
                createBody(productCode(), "EMERGENCY", terms("9.50")), HttpStatus.FORBIDDEN);
    }

    @Test
    void submitterCannotApproveTheirOwnProduct() {
        String code = productCode();
        String productId = exchange(adminToken, HttpMethod.POST, "/api/v1/admin/loan-products",
                createBody(code, "AGRICULTURAL", terms("7.25")), HttpStatus.CREATED).path("data").path("id").asText();
        exchange(adminToken, HttpMethod.POST, "/api/v1/admin/loan-products/" + productId + "/submit", Map.of(), HttpStatus.OK);
        JsonNode rejected = exchange(adminToken, HttpMethod.POST, "/api/v1/admin/loan-products/" + productId + "/approve",
                Map.of(), HttpStatus.FORBIDDEN);
        assertThat(rejected.path("message").asText()).contains("different administrator");
    }

    private Map<String, Object> createBody(String code, String category, Map<String, Object> terms) {
        Map<String, Object> body = new HashMap<>();
        body.put("productCode", code);
        body.put("category", category);
        body.put("terms", terms);
        return body;
    }

    private Map<String, Object> terms(String interestRate) {
        return terms("1000.00", "50000.00", interestRate);
    }

    private Map<String, Object> terms(String minimum, String maximum, String interestRate) {
        Map<String, Object> body = new HashMap<>();
        body.put("productName", "Configurable loan");
        body.put("description", "Term loan for verified customers");
        body.put("customerSegment", "INDIVIDUAL");
        body.put("minimumAmount", new BigDecimal(minimum));
        body.put("maximumAmount", new BigDecimal(maximum));
        body.put("minimumTenure", 1);
        body.put("maximumTenure", 12);
        body.put("tenureUnit", "MONTHS");
        body.put("interestRate", new BigDecimal(interestRate));
        body.put("interestMethod", "REDUCING_BALANCE");
        body.put("interestPeriod", "ANNUAL");
        body.put("repaymentFrequency", "MONTHLY");
        body.put("dayCountConvention", "ACTUAL_365");
        body.put("roundingPrecision", 2);
        body.put("roundingMethod", "HALF_UP");
        body.put("gracePeriodDays", 5);
        body.put("earlyRepaymentRule", "ALLOWED");
        body.put("guarantorRequired", false);
        body.put("minimumGuarantors", 0);
        body.put("creditBureauRequired", true);
        body.put("minimumBureauScore", 500);
        body.put("penaltyBasis", "PERCENTAGE_OF_OVERDUE");
        body.put("penaltyRate", new BigDecimal("2.00"));
        body.put("penaltyAmount", new BigDecimal("0.00"));
        body.put("penaltyGraceDays", 3);
        body.put("fees", List.of(Map.of(
                "feeType", "PROCESSING",
                "name", "Processing fee",
                "calculation", "PERCENTAGE",
                "rate", new BigDecimal("1.50"))));
        body.put("documents", List.of(Map.of(
                "documentCode", "PAYSLIP",
                "documentName", "Payslip",
                "required", true)));
        body.put("eligibilityRules", List.of(Map.of(
                "ruleCode", "EMPLOYED",
                "description", "Applicant must be in active employment")));
        body.put("collateralRules", List.of());
        body.put("approvalRules", List.of(Map.of(
                "stepOrder", 1,
                "roleCode", "CREDIT_OFFICER",
                "description", "Credit officer approval")));
        return body;
    }

    private JsonNode exchange(String token, HttpMethod method, String path, Object body, HttpStatus expected) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        ResponseEntity<JsonNode> response = rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response.getBody();
    }

    private CustomerSession verifiedCustomer() {
        String email = "customer." + UUID.randomUUID() + "@example.com";
        String phone = uniquePhone();
        ResponseEntity<JsonNode> response = exchangeRaw(null, HttpMethod.POST, "/api/v1/auth/register", Map.of(
                "customerType", "INDIVIDUAL",
                "fullName", "Self Customer",
                "email", email,
                "phoneNumber", phone,
                "password", PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String emailCode = capturedOtps.latest(email, "EMAIL_VERIFICATION");
        String phoneCode = capturedOtps.latest(phone, "PHONE_VERIFICATION");
        assertThat(exchangeRaw(null, HttpMethod.POST, "/api/v1/auth/verify-otp", Map.of(
                "identifier", email, "purpose", "EMAIL_VERIFICATION", "code", emailCode)).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(exchangeRaw(null, HttpMethod.POST, "/api/v1/auth/verify-otp", Map.of(
                "identifier", phone, "purpose", "PHONE_VERIFICATION", "code", phoneCode)).getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode login = exchangeRaw(null, HttpMethod.POST, "/api/v1/auth/login", Map.of("identifier", email, "password", PASSWORD)).getBody();
        return new CustomerSession(login.path("data").path("accessToken").asText());
    }

    private Staff createStaff(String role, UUID branchId) {
        String email = role.toLowerCase(Locale.ROOT) + "." + UUID.randomUUID() + "@mfb.test";
        String phone = uniquePhone();
        ResponseEntity<JsonNode> response = exchangeRaw(adminToken, HttpMethod.POST, "/api/v1/admin/users", Map.of(
                "fullName", "Branch Staff",
                "email", email,
                "phoneNumber", phone,
                "password", PASSWORD,
                "roleCodes", List.of(role),
                "branchIds", List.of(branchId)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode mfa = response.getBody().path("data").path("mfaSetup");
        String secret = mfa.isMissingNode() || mfa.isNull() ? null : mfa.path("secret").asText();
        String accessToken = secret == null || secret.isBlank()
                ? exchangeRaw(null, HttpMethod.POST, "/api/v1/admin/auth/login", Map.of("identifier", email, "password", PASSWORD))
                        .getBody().path("data").path("accessToken").asText()
                : staffSession(email, PASSWORD, secret).accessToken();
        return new Staff(accessToken);
    }

    private Session staffSession(String email, String password, String secret) {
        JsonNode challenge = exchangeRaw(null, HttpMethod.POST, "/api/v1/admin/auth/login", Map.of("identifier", email, "password", password))
                .getBody().path("data");
        JsonNode tokens = exchangeRaw(null, HttpMethod.POST, "/api/v1/admin/auth/mfa/verify", Map.of(
                "mfaToken", challenge.path("mfaToken").asText(),
                "code", totp.codeAt(secret, Instant.now()))).getBody().path("data");
        return new Session(tokens.path("accessToken").asText());
    }

    private ResponseEntity<JsonNode> exchangeRaw(String token, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private UUID insertBranch(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO branches (id, code, name, created_by) VALUES (?, ?, ?, ?)", id, code, code, "product-it");
        return id;
    }

    private static String productCode() {
        return "PR" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String uniquePhone() {
        return "+2348" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    private record Staff(String accessToken) {
    }

    private record Session(String accessToken) {
    }

    private record CustomerSession(String accessToken) {
    }
}
