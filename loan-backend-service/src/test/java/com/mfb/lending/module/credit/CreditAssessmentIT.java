package com.mfb.lending.module.credit;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

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
import org.springframework.context.annotation.Import;
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
@Import(ScenarioCreditBureauConfiguration.class)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreditAssessmentIT {

    private static final String ADMIN_EMAIL = "admin@mfb.test";
    private static final String PASSWORD = "Str0ng-Passw0rd!";
    private static final String ADMIN_MFA_SECRET = "JBSWY3DPEHPK3PXP";
    private static final UUID INDIVIDUAL_MODEL = UUID.fromString("a1111111-1111-4111-8111-111111111111");
    private static final UUID INDIVIDUAL_VERSION = UUID.fromString("a2222222-2222-4222-8222-222222222222");

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

    @Autowired
    private ScenarioCreditBureau bureau;

    private String adminToken;

    @BeforeAll
    void signInAdministrator() {
        adminToken = staffSession(ADMIN_EMAIL, PASSWORD, ADMIN_MFA_SECRET).accessToken();
    }

    @BeforeEach
    void resetSharedState() {
        bureau.reset();
        var keys = redisTemplate.keys("auth:rl:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void completeIndividualFileIsApprovedAndKeepsTheModelAndPolicyVersion() {
        Fixture fixture = seed(false);
        String id = createAssessment(fixture.applicationId(), true, false);
        JsonNode bureauCheck = exchange(adminToken, HttpMethod.POST, "/api/v1/credit-assessments/" + id + "/bureau-check",
                Map.of(), HttpStatus.OK).path("data");
        assertThat(bureauCheck.path("providerCode").asText()).isEqualTo("SCENARIO");
        assertThat(bureauCheck.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(bureauCheck.path("bureauScore").asInt()).isEqualTo(800);

        JsonNode decision = evaluate(id);
        assertThat(decision.path("decision").asText()).isEqualTo("AUTO_APPROVE");
        assertThat(decision.path("score").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(decision.path("riskCategory").asText()).isEqualTo("LOW");
        assertThat(decision.path("policyVersion").asText()).isEqualTo("POL-2026.1");
        assertThat(decision.path("scoringModelId").asText()).isEqualTo(INDIVIDUAL_MODEL.toString());
        assertThat(decision.path("scoringModelVersionId").asText()).isEqualTo(INDIVIDUAL_VERSION.toString());
        assertThat(decision.path("calculations").path("dti").path("output").decimalValue()).isEqualByComparingTo("0.100000");
        assertThat(decision.path("calculations").path("dti").path("inputs").path("monthlyDebtObligations").decimalValue())
                .isEqualByComparingTo("20000.00");
        assertThat(decision.path("calculations").path("disposableIncome").path("output").decimalValue())
                .isEqualByComparingTo("120000.00");

        JsonNode comment = exchange(adminToken, HttpMethod.POST, "/api/v1/credit-assessments/" + id + "/comments",
                Map.of("comment", "Salary credit is visible on the statement."), HttpStatus.CREATED).path("data");
        JsonNode report = exchange(adminToken, HttpMethod.GET, "/api/v1/credit-assessments/" + id + "/report", null, HttpStatus.OK)
                .path("data");
        assertThat(report.path("assessment").path("decision").asText()).isEqualTo("AUTO_APPROVE");
        assertThat(report.path("assessment").path("policyVersion").asText()).isEqualTo("POL-2026.1");
        assertThat(report.path("results")).hasSize(1);
        assertThat(report.path("results").get(0).path("modelVersionId").asText()).isEqualTo(INDIVIDUAL_VERSION.toString());
        assertThat(report.path("comments").get(0).path("id").asText()).isEqualTo(comment.path("id").asText());
        assertThat(report.path("bureauChecks").get(0).path("status").asText()).isEqualTo("COMPLETED");

        String customerToken = verifiedCustomer().accessToken();
        assertThat(exchangeRaw(customerToken, HttpMethod.GET, "/api/v1/credit-assessments/" + id, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void missingNetIncomeStaysPendingWhileTheDtiOutputIsPreserved() {
        Fixture fixture = seed(false);
        String id = createAssessment(fixture.applicationId(), false, false);
        exchange(adminToken, HttpMethod.POST, "/api/v1/credit-assessments/" + id + "/bureau-check", Map.of(), HttpStatus.OK);
        JsonNode decision = evaluate(id);
        assertThat(decision.path("decision").asText()).isEqualTo("PENDING_DATA");
        assertThat(decision.path("calculations").path("dti").path("output").decimalValue()).isEqualByComparingTo("0.100000");
        JsonNode score = decision.path("score");
        assertThat(score.isNull() || score.isMissingNode()).isTrue();
        assertThat(decision.path("reasons").findValuesAsText("code")).contains("NET_INCOME");
    }

    @Test
    void bureauFailureDoesNotApproveTheApplication() {
        Fixture fixture = seed(false);
        String id = createAssessment(fixture.applicationId(), true, false);
        bureau.fail("Bureau provider timed out");
        JsonNode check = exchange(adminToken, HttpMethod.POST, "/api/v1/credit-assessments/" + id + "/bureau-check",
                Map.of(), HttpStatus.OK).path("data");
        assertThat(check.path("status").asText()).isEqualTo("FAILED");
        assertThat(check.path("failureReason").asText()).contains("timed out");
        JsonNode decision = evaluate(id);
        assertThat(decision.path("decision").asText()).isEqualTo("PENDING_DATA");
    }

    @Test
    void complianceHoldKeepsAPerfectScoreAndRejectsAnOverride() {
        Fixture fixture = seed(true);
        String id = createAssessment(fixture.applicationId(), true, false);
        exchange(adminToken, HttpMethod.POST, "/api/v1/credit-assessments/" + id + "/bureau-check", Map.of(), HttpStatus.OK);
        JsonNode decision = evaluate(id);
        assertThat(decision.path("decision").asText()).isEqualTo("COMPLIANCE_HOLD");
        assertThat(decision.path("score").decimalValue()).isEqualByComparingTo("100.00");

        ResponseEntity<JsonNode> denied = exchangeRaw(adminToken, HttpMethod.POST, "/api/v1/credit-assessments/" + id + "/recommend",
                Map.of("recommendation", "APPROVE", "justification", "The score is high enough to continue."));
        assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(denied.getBody().path("message").asText()).contains("cannot be overridden");
        assertThat(evaluate(id).path("decision").asText()).isEqualTo("COMPLIANCE_HOLD");
    }

    @Test
    void policyOverrideRequiresASecondOfficerAndThenApproves() {
        Fixture fixture = seed(false);
        Staff officer = createStaff("CREDIT_OFFICER", fixture.branchId());
        String id = createAssessment(fixture.applicationId(), true, true);
        exchange(adminToken, HttpMethod.POST, "/api/v1/credit-assessments/" + id + "/bureau-check", Map.of(), HttpStatus.OK);
        JsonNode rejected = evaluate(id);
        assertThat(rejected.path("decision").asText()).isEqualTo("AUTO_REJECT");
        assertThat(rejected.path("score").decimalValue()).isEqualByComparingTo("80.63");
        assertThat(rejected.path("calculations").path("dti").path("output").decimalValue()).isEqualByComparingTo("0.450000");

        JsonNode advised = exchange(adminToken, HttpMethod.POST, "/api/v1/credit-assessments/" + id + "/recommend",
                Map.of("recommendation", "APPROVE", "justification", "Salary recovery covers the stretched instalment."),
                HttpStatus.OK).path("data");
        assertThat(advised.path("decision").asText()).isEqualTo("AUTO_REJECT");

        JsonNode report = exchange(adminToken, HttpMethod.GET, "/api/v1/credit-assessments/" + id + "/report", null, HttpStatus.OK)
                .path("data");
        JsonNode exception = report.path("exceptions").get(0);
        assertThat(exception.path("ruleCode").asText()).isEqualTo("IND_DTI_MAX");
        assertThat(exception.path("status").asText()).isEqualTo("PENDING");
        String exceptionId = exception.path("id").asText();

        ResponseEntity<JsonNode> selfApproval = exchangeRaw(adminToken, HttpMethod.POST,
                "/api/v1/credit-assessments/" + id + "/recommend",
                Map.of("recommendation", "APPROVE", "justification", "I am confirming my own exception request.",
                        "exceptionId", exceptionId));
        assertThat(selfApproval.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(selfApproval.getBody().path("message").asText()).contains("Another officer");

        JsonNode approved = exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/credit-assessments/" + id + "/recommend",
                Map.of("recommendation", "APPROVE", "justification", "Second officer accepts the DTI exception.",
                        "exceptionId", exceptionId),
                HttpStatus.OK).path("data");
        assertThat(approved.path("decision").asText()).isEqualTo("AUTO_APPROVE");
        assertThat(approved.path("scoringModelVersionId").asText()).isEqualTo(INDIVIDUAL_VERSION.toString());
        assertThat(approved.path("policyVersion").asText()).isEqualTo("POL-2026.1");

        Integer requested = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'CREDIT_EXCEPTION_REQUESTED' AND entity_id = ?",
                Integer.class, id);
        Integer decided = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE action = 'CREDIT_EXCEPTION_APPROVED' AND entity_id = ?",
                Integer.class, exceptionId);
        assertThat(requested).isEqualTo(1);
        assertThat(decided).isEqualTo(1);
    }

    @Test
    void concurrentCreatesConflictAndConcurrentEvaluationsStayStable() throws Exception {
        Fixture createRace = seed(false);
        List<Integer> createStatuses = parallel(2, () -> exchangeRaw(adminToken, HttpMethod.POST, "/api/v1/credit-assessments",
                individualBody(createRace.applicationId(), true, false)).getStatusCode().value());
        assertThat(createStatuses).containsExactlyInAnyOrder(201, 409);

        Fixture evaluateRace = seed(false);
        String id = createAssessment(evaluateRace.applicationId(), true, false);
        exchange(adminToken, HttpMethod.POST, "/api/v1/credit-assessments/" + id + "/bureau-check", Map.of(), HttpStatus.OK);
        List<ResponseEntity<JsonNode>> evaluations = parallel(2, () -> exchangeRaw(adminToken, HttpMethod.POST,
                "/api/v1/credit-assessments/" + id + "/evaluate", null));
        assertThat(evaluations).allSatisfy(response -> {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().path("data").path("decision").asText()).isEqualTo("AUTO_APPROVE");
        });
        JsonNode report = exchange(adminToken, HttpMethod.GET, "/api/v1/credit-assessments/" + id + "/report", null, HttpStatus.OK)
                .path("data");
        assertThat(report.path("results")).hasSize(2);
        assertThat(report.path("results").findValuesAsText("decision")).containsOnly("AUTO_APPROVE");
    }

    @Test
    void productModelRefersThenANewVersionRescoresWithoutRewritingHistory() {
        Fixture fixture = seed(false);
        String code = "DTI" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        JsonNode created = exchange(adminToken, HttpMethod.POST, "/api/v1/admin/credit-scoring/models",
                modelBody(code, fixture.productId(), "0.20", "0.60", "100.01", "50", "POL-PRODUCT.1"), HttpStatus.CREATED)
                .path("data");
        String modelId = created.path("id").asText();
        exchange(adminToken, HttpMethod.POST, "/api/v1/admin/credit-scoring/models/" + modelId + "/activate", null, HttpStatus.OK);
        JsonNode active = exchange(adminToken, HttpMethod.GET, "/api/v1/admin/credit-scoring/models/" + modelId, null, HttpStatus.OK)
                .path("data");
        String versionOne = active.path("currentVersion").path("id").asText();

        String id = createAssessment(fixture.applicationId(), true, false);
        exchange(adminToken, HttpMethod.POST, "/api/v1/credit-assessments/" + id + "/bureau-check", Map.of(), HttpStatus.OK);
        JsonNode referred = evaluate(id);
        assertThat(referred.path("decision").asText()).isEqualTo("REFER");
        assertThat(referred.path("score").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(referred.path("scoringModelVersionId").asText()).isEqualTo(versionOne);

        JsonNode queue = exchange(adminToken, HttpMethod.GET,
                "/api/v1/admin/credit-assessments/queue?decision=REFER&size=100", null, HttpStatus.OK).path("data");
        assertThat(queue.path("items").findValuesAsText("id")).contains(id);

        exchange(adminToken, HttpMethod.POST, "/api/v1/admin/credit-scoring/models/" + modelId + "/versions",
                specBody("0.05", "0.10", "100.01", "50", "POL-PRODUCT.2"), HttpStatus.CREATED);
        JsonNode activated = exchange(adminToken, HttpMethod.POST, "/api/v1/admin/credit-scoring/models/" + modelId + "/activate",
                null, HttpStatus.OK).path("data");
        String versionTwo = activated.path("currentVersion").path("id").asText();
        assertThat(versionTwo).isNotEqualTo(versionOne);

        JsonNode rescored = evaluate(id);
        assertThat(rescored.path("score").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(rescored.path("decision").asText()).isEqualTo("AUTO_REJECT");
        assertThat(rescored.path("scoringModelVersionId").asText()).isEqualTo(versionTwo);
        assertThat(rescored.path("policyVersion").asText()).isEqualTo("POL-PRODUCT.2");

        JsonNode results = exchange(adminToken, HttpMethod.GET, "/api/v1/credit-assessments/" + id + "/report", null, HttpStatus.OK)
                .path("data").path("results");
        assertThat(results).hasSize(2);
        assertThat(results.get(0).path("modelVersionId").asText()).isEqualTo(versionOne);
        assertThat(results.get(0).path("score").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(results.get(0).path("policyVersion").asText()).isEqualTo("POL-PRODUCT.1");
        assertThat(results.get(1).path("modelVersionId").asText()).isEqualTo(versionTwo);
        assertThat(results.get(1).path("score").decimalValue()).isEqualByComparingTo("0.00");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE credit_scoring_model_versions SET policy_version = 'CHANGED' WHERE id = ?",
                UUID.fromString(versionOne)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("published credit scoring model version cannot change");

        ResponseEntity<JsonNode> locked = exchangeRaw(adminToken, HttpMethod.PUT,
                "/api/v1/admin/credit-scoring/models/" + INDIVIDUAL_MODEL,
                specBody("0.20", "0.60", "75", "55", "POL-2026.2"));
        assertThat(locked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void rulesCanBeCreatedUpdatedAndDeleted() {
        String code = "IT_RULE_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        Map<String, Object> body = ruleBody(code, "Disposable smoke rule");
        JsonNode created = exchange(adminToken, HttpMethod.POST, "/api/v1/admin/credit-scoring/rules", body, HttpStatus.CREATED)
                .path("data");
        String id = created.path("id").asText();
        assertThat(created.path("active").asBoolean()).isFalse();

        JsonNode fetched = exchange(adminToken, HttpMethod.GET, "/api/v1/admin/credit-scoring/rules/" + id, null, HttpStatus.OK)
                .path("data");
        assertThat(fetched.path("code").asText()).isEqualTo(code);

        body.put("description", "Updated disposable smoke rule");
        JsonNode updated = exchange(adminToken, HttpMethod.PUT, "/api/v1/admin/credit-scoring/rules/" + id, body, HttpStatus.OK)
                .path("data");
        assertThat(updated.path("description").asText()).isEqualTo("Updated disposable smoke rule");
        assertThat(updated.path("code").asText()).isEqualTo(code);

        assertThat(exchangeRaw(adminToken, HttpMethod.DELETE, "/api/v1/admin/credit-scoring/rules/" + id, null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchangeRaw(adminToken, HttpMethod.GET, "/api/v1/admin/credit-scoring/rules/" + id, null).getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void openApiDocumentsTheCreditAssessmentApi() {
        JsonNode spec = exchange(null, HttpMethod.GET, "/v3/api-docs/v1", null, HttpStatus.OK);
        assertThat(spec.path("paths").path("/api/v1/credit-assessments").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/credit-assessments/{id}/evaluate").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/credit-assessments/{id}/bureau-check").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/credit-assessments/{id}/recommend").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/credit-assessments/{id}/comments").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/credit-assessments/{id}/report").path("get").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/admin/credit-assessments/queue").path("get").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/admin/credit-scoring/models").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/admin/credit-scoring/rules").path("post").isMissingNode()).isFalse();
    }

    private String createAssessment(UUID applicationId, boolean includeNetIncome, boolean stretched) {
        JsonNode created = exchange(adminToken, HttpMethod.POST, "/api/v1/credit-assessments",
                individualBody(applicationId, includeNetIncome, stretched), HttpStatus.CREATED).path("data");
        assertThat(created.path("status").asText()).isEqualTo("OPEN");
        assertThat(created.path("reference").asText()).startsWith("CA");
        return created.path("id").asText();
    }

    private JsonNode evaluate(String id) {
        return exchange(adminToken, HttpMethod.POST, "/api/v1/credit-assessments/" + id + "/evaluate", null, HttpStatus.OK)
                .path("data");
    }

    private Map<String, Object> individualBody(UUID applicationId, boolean includeNetIncome, boolean stretched) {
        Map<String, Object> individual = new HashMap<>();
        individual.put("monthlyGrossIncome", new BigDecimal("200000.00"));
        if (includeNetIncome) {
            individual.put("netIncome", new BigDecimal("180000.00"));
        }
        individual.put("monthlyExpenses", new BigDecimal(stretched ? "30000.00" : "40000.00"));
        individual.put("existingLoanObligations", new BigDecimal(stretched ? "70000.00" : "10000.00"));
        individual.put("employmentMonths", 36);
        individual.put("employmentStatus", "EMPLOYED");
        Map<String, Object> body = new HashMap<>();
        body.put("applicationId", applicationId);
        body.put("proposedMonthlyInstalment", new BigDecimal(stretched ? "20000.00" : "10000.00"));
        body.put("individual", individual);
        body.put("repaymentHistory", "CLEAN");
        body.put("bankAccountTurnover", new BigDecimal("200000.00"));
        return body;
    }

    private Map<String, Object> modelBody(
            String code, UUID productId, String best, String worst, String autoApprove, String refer, String policy) {
        Map<String, Object> body = specBody(best, worst, autoApprove, refer, policy);
        body.put("code", code);
        body.put("name", "Product DTI card");
        body.put("segment", "PRODUCT");
        body.put("productId", productId);
        return body;
    }

    private Map<String, Object> specBody(String best, String worst, String autoApprove, String refer, String policy) {
        Map<String, Object> body = new HashMap<>();
        body.put("policyVersion", policy);
        body.put("components", List.of(Map.of(
                "code", "DTI",
                "weight", new BigDecimal("1.00"),
                "metric", "DTI",
                "direction", "LOWER_IS_BETTER",
                "best", new BigDecimal(best),
                "worst", new BigDecimal(worst))));
        body.put("riskCategories", List.of(
                Map.of("code", "LOW", "minimumScore", new BigDecimal("80")),
                Map.of("code", "VERY_HIGH", "minimumScore", BigDecimal.ZERO)));
        body.put("decisionThresholds", Map.of(
                "autoApproveMinimum", new BigDecimal(autoApprove),
                "referMinimum", new BigDecimal(refer)));
        body.put("changeReason", "Integration test scoring card");
        return body;
    }

    private Map<String, Object> ruleBody(String code, String description) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "Integration disposable rule");
        body.put("description", description);
        body.put("segment", "INDIVIDUAL");
        body.put("ruleType", "DISPOSABLE_INCOME_MIN");
        body.put("mandatory", false);
        body.put("exceptionAllowed", true);
        body.put("threshold", BigDecimal.ZERO);
        body.put("failureOutcome", "AUTO_REJECT");
        body.put("policyVersion", "POL-2026.1");
        body.put("active", false);
        return body;
    }

    private Fixture seed(boolean complianceHold) {
        UUID branchId = insertBranch("CR-" + UUID.randomUUID().toString().substring(0, 8));
        UUID customerId = UUID.randomUUID();
        String customerReference = "CUS" + String.format("%08d", ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000));
        jdbc.update(
                """
                INSERT INTO customers (
                    id, customer_reference, customer_type, status, category, display_name, email, phone_number,
                    branch_id, kyc_status, risk_classification, compliance_hold, compliance_hold_reason, created_by, updated_by)
                VALUES (?, ?, 'INDIVIDUAL', 'ACTIVE', 'RETAIL', 'Ada Okonkwo', ?, ?, ?, 'VERIFIED', 'LOW', ?, ?, 'credit-it', 'credit-it')
                """,
                customerId,
                customerReference,
                "credit." + UUID.randomUUID() + "@example.com",
                uniquePhone(),
                branchId,
                complianceHold,
                complianceHold ? "Sanctions review" : null);

        UUID productId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String productCode = "CR" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update(
                """
                INSERT INTO loan_products (id, product_code, category, status, created_by, updated_by)
                VALUES (?, ?, 'PERSONAL', 'DRAFT', 'credit-it', 'credit-it')
                """,
                productId, productCode);
        jdbc.update(
                """
                INSERT INTO loan_product_versions (
                    id, product_id, version_number, status, product_name, customer_segment, currency_code,
                    minimum_amount, maximum_amount, minimum_tenure, maximum_tenure, tenure_unit,
                    interest_rate, interest_method, interest_period, repayment_frequency, day_count_convention,
                    rounding_precision, rounding_method, grace_period_days, early_repayment_rule,
                    guarantor_required, minimum_guarantors, credit_bureau_required, minimum_bureau_score,
                    penalty_basis, penalty_rate, penalty_amount, penalty_grace_days,
                    effective_from, activated_at, activated_by, created_by, updated_by)
                VALUES (
                    ?, ?, 1, 'ACTIVE', 'Personal loan', 'INDIVIDUAL', 'NGN',
                    1000.00, 5000000.00, 1, 60, 'MONTHS',
                    9.5000, 'REDUCING_BALANCE', 'ANNUAL', 'MONTHLY', 'ACTUAL_365',
                    2, 'HALF_UP', 0, 'ALLOWED',
                    FALSE, 0, TRUE, 500,
                    'PERCENTAGE_OF_OVERDUE', 2.0000, 0.00, 0,
                    NOW(), NOW(), 'credit-it', 'credit-it', 'credit-it')
                """,
                versionId, productId);
        jdbc.update(
                "UPDATE loan_products SET status = 'ACTIVE', current_version_id = ?, updated_by = 'credit-it' WHERE id = ?",
                versionId, productId);

        UUID applicationId = UUID.randomUUID();
        String reference = "LA" + String.format("%012d", ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L));
        jdbc.update(
                """
                INSERT INTO loan_applications (
                    id, reference, customer_id, product_id, product_version_id, branch_id, customer_segment, status,
                    revision_number, correction_open, amount, currency_code, purpose, tenure, tenure_unit,
                    repayment_frequency, channel, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'INDIVIDUAL', 'SUBMITTED', 1, FALSE, 200000.00, 'NGN', 'Working capital',
                    12, 'MONTHS', 'MONTHLY', 'WEB', 'credit-it', 'credit-it')
                """,
                applicationId, reference, customerId, productId, versionId, branchId);
        return new Fixture(branchId, customerId, productId, versionId, applicationId);
    }

    private <T> List<T> parallel(int callers, ConcurrentCall<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CyclicBarrier barrier = new CyclicBarrier(callers);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return call.run();
                }));
            }
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private JsonNode exchange(String token, HttpMethod method, String path, Object body, HttpStatus expected) {
        ResponseEntity<JsonNode> response = exchangeRaw(token, method, path, body);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response.getBody();
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
        JsonNode login = exchangeRaw(null, HttpMethod.POST, "/api/v1/auth/login", Map.of("identifier", email, "password", PASSWORD))
                .getBody();
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
        JsonNode challenge = exchangeRaw(null, HttpMethod.POST, "/api/v1/admin/auth/login",
                Map.of("identifier", email, "password", password)).getBody().path("data");
        JsonNode tokens = exchangeRaw(null, HttpMethod.POST, "/api/v1/admin/auth/mfa/verify", Map.of(
                "mfaToken", challenge.path("mfaToken").asText(),
                "code", totp.codeAt(secret, Instant.now()))).getBody().path("data");
        return new Session(tokens.path("accessToken").asText());
    }

    private UUID insertBranch(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO branches (id, code, name, created_by) VALUES (?, ?, ?, ?)", id, code, code, "credit-it");
        return id;
    }

    private static String uniquePhone() {
        return "+2348" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    @FunctionalInterface
    private interface ConcurrentCall<T> {
        T run() throws Exception;
    }

    private record Fixture(UUID branchId, UUID customerId, UUID productId, UUID versionId, UUID applicationId) {
    }

    private record Staff(String accessToken) {
    }

    private record Session(String accessToken) {
    }

    private record CustomerSession(String accessToken) {
    }
}
