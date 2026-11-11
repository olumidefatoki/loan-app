package com.mfb.lending.module.approval;

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
import com.mfb.lending.module.auth.security.TotpVerifier;
import com.mfb.lending.module.credit.ScenarioCreditBureauConfiguration;
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
class LoanApprovalIT {

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
    private TotpVerifier totp;

    private String adminToken;

    @BeforeAll
    void signInAdministrator() {
        adminToken = staffSession(ADMIN_EMAIL, PASSWORD, ADMIN_MFA_SECRET).accessToken();
    }

    @BeforeEach
    void resetRateLimits() {
        var keys = redisTemplate.keys("auth:rl:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void matricesAreVersionedAndTheContractIsPublished() {
        String code = code();
        Map<String, Object> body = matrixBody(code, null, false, List.of(level("CREDIT_MANAGER", 1, "5000000.00", 1, 24, true, false, false)));
        JsonNode created = exchange(adminToken, HttpMethod.POST, "/api/v1/admin/approval-matrices", body, HttpStatus.CREATED).path("data");
        String id = created.path("id").asText();
        assertThat(created.path("status").asText()).isEqualTo("DRAFT");
        assertThat(created.path("versions").get(0).path("levels")).hasSize(1);

        body.put("name", "Updated approval matrix");
        JsonNode updated = exchange(adminToken, HttpMethod.PUT, "/api/v1/admin/approval-matrices/" + id, body, HttpStatus.OK).path("data");
        assertThat(updated.path("name").asText()).isEqualTo("Updated approval matrix");

        JsonNode active = exchange(adminToken, HttpMethod.POST, "/api/v1/admin/approval-matrices/" + id + "/activate", null, HttpStatus.OK).path("data");
        assertThat(active.path("status").asText()).isEqualTo("ACTIVE");
        body.put("name", "Next draft");
        JsonNode redrafted = exchange(adminToken, HttpMethod.PUT, "/api/v1/admin/approval-matrices/" + id, body, HttpStatus.OK).path("data");
        assertThat(redrafted.path("versions")).hasSize(2);
        assertThat(redrafted.path("status").asText()).isEqualTo("ACTIVE");

        String disposable = code();
        JsonNode draft = exchange(adminToken, HttpMethod.POST, "/api/v1/admin/approval-matrices",
                matrixBody(disposable, null, false, List.of(level("CREDIT_MANAGER", 1, null, 1, 24, true, false, false))),
                HttpStatus.CREATED).path("data");
        assertThat(exchangeRaw(adminToken, HttpMethod.DELETE, "/api/v1/admin/approval-matrices/" + draft.path("id").asText(), null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);
        assertThat(exchangeRaw(adminToken, HttpMethod.DELETE, "/api/v1/admin/approval-matrices/" + id, null).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        JsonNode spec = exchange(null, HttpMethod.GET, "/v3/api-docs/v1", null, HttpStatus.OK);
        assertThat(spec.path("paths").path("/api/v1/approvals/my-queue").path("get").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/approvals/{id}").path("get").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/approvals/{id}/approve").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/approvals/{id}/reject").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/approvals/{id}/return").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/approvals/{id}/request-information").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/approvals/{id}/delegate").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/approvals/{id}/history").path("get").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/admin/approval-matrices").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/admin/approval-matrices/{id}/activate").path("post").isMissingNode()).isFalse();
    }

    @Test
    void automatedApprovalRunsOnlyWhenEveryGateIsClear() {
        Fixture manual = seed(false);
        publish(manual, false, List.of(level("CREDIT_MANAGER", 1, null, 1, 24, false, true, false)));
        JsonNode held = request(adminToken, manual).path("data");
        assertThat(held.path("status").asText()).isEqualTo("PENDING");
        assertThat(held.path("automated").asBoolean()).isFalse();
        assertThat(applicationStatus(manual.applicationId())).isEqualTo("PENDING_APPROVAL");

        Fixture automatic = seed(true);
        publish(automatic, true, List.of(level("CREDIT_MANAGER", 1, null, 1, 24, false, true, false)));
        JsonNode approved = request(adminToken, automatic).path("data");
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("automated").asBoolean()).isTrue();
        assertThat(approved.path("decision").asText()).isEqualTo("APPROVE");
        assertThat(applicationStatus(automatic.applicationId())).isEqualTo("APPROVED");
        JsonNode history = exchange(adminToken, HttpMethod.GET, "/api/v1/approvals/" + approved.path("id").asText() + "/history", null, HttpStatus.OK)
                .path("data");
        assertThat(history).anySatisfy(entry -> assertThat(entry.path("decision").asText()).isEqualTo("APPROVE"));
        assertThat(history.get(0).path("correlationId").asText()).isNotBlank();
    }

    @Test
    void creditEvaluationOpensAnAutomatedApproval() {
        Fixture fixture = seed(true, false);
        publish(fixture, true, List.of(level("CREDIT_MANAGER", 1, null, 1, 24, false, true, false)));
        String assessmentId = exchange(adminToken, HttpMethod.POST, "/api/v1/credit-assessments", individual(fixture.applicationId()), HttpStatus.CREATED)
                .path("data").path("id").asText();
        exchange(adminToken, HttpMethod.POST, "/api/v1/credit-assessments/" + assessmentId + "/bureau-check", Map.of(), HttpStatus.OK);
        JsonNode decision = exchange(adminToken, HttpMethod.POST, "/api/v1/credit-assessments/" + assessmentId + "/evaluate", null, HttpStatus.OK)
                .path("data");
        assertThat(decision.path("decision").asText()).isEqualTo("AUTO_APPROVE");
        String status = jdbc.queryForObject(
                "SELECT status FROM loan_approvals WHERE application_id = ?",
                String.class,
                fixture.applicationId());
        assertThat(status).isEqualTo("APPROVED");
        assertThat(applicationStatus(fixture.applicationId())).isEqualTo("APPROVED");
    }

    @Test
    void selfApprovalAndAuthorityLimitsAreRejected() {
        UUID branchId = insertBranch("AP-" + UUID.randomUUID().toString().substring(0, 8));
        Officer maker = officer("CREDIT_MANAGER", branchId);
        Fixture fixture = seed(false, true, maker.email(), branchId);
        publish(fixture, false, List.of(level("CREDIT_MANAGER", 1, null, 1, 24, true, false, false)));
        grant(maker.id(), fixture.branchId(), "CREDIT_MANAGER", "500000.00");
        JsonNode opened = request(maker.token(), fixture).path("data");

        ResponseEntity<JsonNode> self = exchangeRaw(maker.token(), HttpMethod.POST, "/api/v1/approvals/" + opened.path("id").asText() + "/approve",
                Map.of("version", opened.path("version").asInt(), "reason", "I originated this file"));
        assertThat(self.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        Officer limited = officer("CREDIT_MANAGER", fixture.branchId());
        grant(limited.id(), fixture.branchId(), "CREDIT_MANAGER", "100000.00");
        ResponseEntity<JsonNode> overLimit = exchangeRaw(limited.token(), HttpMethod.POST,
                "/api/v1/approvals/" + opened.path("id").asText() + "/approve",
                Map.of("version", opened.path("version").asInt(), "reason", "Above my limit"));
        assertThat(overLimit.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(overLimit.getBody().path("message").asText()).contains("delegated approval limit");

        Officer checker = officer("CREDIT_MANAGER", fixture.branchId());
        grant(checker.id(), fixture.branchId(), "CREDIT_MANAGER", "500000.00");
        JsonNode approved = exchange(checker.token(), HttpMethod.POST, "/api/v1/approvals/" + opened.path("id").asText() + "/approve",
                Map.of("version", opened.path("version").asInt(), "reason", "Within delegated authority"), HttpStatus.OK).path("data");
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("decidedBy").asText()).isEqualTo(checker.id().toString());
        assertThatThrownBy(() -> jdbc.update("UPDATE loan_approvals SET decision = 'REJECT' WHERE id = ?", UUID.fromString(approved.path("id").asText())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void delegationTransfersOnlyTheDelegatorLimit() {
        Fixture fixture = seed(false);
        publish(fixture, false, List.of(level("CREDIT_MANAGER", 1, null, 1, 24, true, false, false)));
        Officer delegator = officer("CREDIT_MANAGER", fixture.branchId());
        Officer delegate = officer("CREDIT_MANAGER", fixture.branchId());
        grant(delegator.id(), fixture.branchId(), "CREDIT_MANAGER", "500000.00");
        JsonNode opened = request(adminToken, fixture).path("data");
        JsonNode delegated = exchange(delegator.token(), HttpMethod.POST, "/api/v1/approvals/" + opened.path("id").asText() + "/delegate",
                Map.of("version", opened.path("version").asInt(), "delegateUserId", delegate.id(), "reason", "Covering leave"),
                HttpStatus.OK).path("data");
        JsonNode approved = exchange(delegate.token(), HttpMethod.POST, "/api/v1/approvals/" + delegated.path("id").asText() + "/approve",
                Map.of("version", delegated.path("version").asInt(), "reason", "Delegated authority"), HttpStatus.OK).path("data");
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("decidedBy").asText()).isEqualTo(delegate.id().toString());
    }

    @Test
    void simultaneousApprovalsCannotBothSucceed() throws Exception {
        Fixture fixture = seed(false);
        publish(fixture, false, List.of(level("CREDIT_MANAGER", 1, null, 1, 24, true, false, false)));
        Officer first = officer("CREDIT_MANAGER", fixture.branchId());
        Officer second = officer("CREDIT_MANAGER", fixture.branchId());
        grant(first.id(), fixture.branchId(), "CREDIT_MANAGER", "500000.00");
        grant(second.id(), fixture.branchId(), "CREDIT_MANAGER", "500000.00");
        JsonNode opened = request(adminToken, fixture).path("data");
        String path = "/api/v1/approvals/" + opened.path("id").asText() + "/approve";
        Map<String, Object> body = Map.of("version", opened.path("version").asInt(), "reason", "Concurrent decision");
        List<Integer> statuses = parallel(List.of(first.token(), second.token()), token ->
                exchangeRaw(token, HttpMethod.POST, path, body).getStatusCode().value());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(applicationStatus(fixture.applicationId())).isEqualTo("APPROVED");
        Integer votes = jdbc.queryForObject(
                "SELECT COUNT(*) FROM loan_approval_history WHERE approval_id = ? AND decision = 'APPROVE'",
                Integer.class,
                UUID.fromString(opened.path("id").asText()));
        assertThat(votes).isEqualTo(1);
    }

    @Test
    void aRejectedApplicationCannotBeApprovedLater() {
        Fixture fixture = seed(false);
        publish(fixture, false, List.of(level("CREDIT_MANAGER", 1, null, 1, 24, true, false, false)));
        Officer checker = officer("CREDIT_MANAGER", fixture.branchId());
        grant(checker.id(), fixture.branchId(), "CREDIT_MANAGER", "500000.00");
        JsonNode opened = request(adminToken, fixture).path("data");
        JsonNode rejected = exchange(checker.token(), HttpMethod.POST, "/api/v1/approvals/" + opened.path("id").asText() + "/reject",
                Map.of("version", opened.path("version").asInt(), "reason", "Income does not support the instalment"), HttpStatus.OK).path("data");
        assertThat(rejected.path("status").asText()).isEqualTo("REJECTED");
        assertThat(rejected.path("decision").asText()).isEqualTo("REJECT");
        assertThat(applicationStatus(fixture.applicationId())).isEqualTo("REJECTED");
        ResponseEntity<JsonNode> again = exchangeRaw(checker.token(), HttpMethod.POST, "/api/v1/approvals/" + opened.path("id").asText() + "/approve",
                Map.of("version", rejected.path("version").asInt(), "reason", "Second look"));
        assertThat(again.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void expiryClosesTheRequestAndEscalationMovesItForward() {
        Fixture expired = seed(false);
        publish(expired, false, List.of(level("CREDIT_MANAGER", 1, null, 1, 0, true, false, false)));
        JsonNode opened = request(adminToken, expired).path("data");
        JsonNode closed = exchange(adminToken, HttpMethod.GET, "/api/v1/approvals/" + opened.path("id").asText(), null, HttpStatus.OK).path("data");
        assertThat(closed.path("status").asText()).isEqualTo("EXPIRED");
        assertThat(exchangeRaw(adminToken, HttpMethod.POST, "/api/v1/approvals/" + opened.path("id").asText() + "/approve",
                Map.of("version", closed.path("version").asInt(), "reason", "Too late")).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        Fixture escalated = seed(false);
        publish(escalated, false, List.of(
                level("CREDIT_MANAGER", 1, "100000.00", 1, 0, true, false, true),
                level("SENIOR_CREDIT_MANAGER", 2, null, 1, 24, true, false, false)));
        JsonNode waiting = request(adminToken, escalated).path("data");
        assertThat(waiting.path("stage").asText()).isEqualTo("CREDIT_MANAGER");
        JsonNode moved = exchange(adminToken, HttpMethod.GET, "/api/v1/approvals/" + waiting.path("id").asText(), null, HttpStatus.OK).path("data");
        assertThat(moved.path("status").asText()).isEqualTo("PENDING");
        assertThat(moved.path("stage").asText()).isEqualTo("SENIOR_CREDIT_MANAGER");
        JsonNode history = exchange(adminToken, HttpMethod.GET, "/api/v1/approvals/" + waiting.path("id").asText() + "/history", null, HttpStatus.OK).path("data");
        assertThat(history).anySatisfy(entry -> {
            assertThat(entry.path("decision").asText()).isEqualTo("ESCALATE");
            assertThat(entry.path("role").asText()).isEqualTo("SYSTEM");
            assertThat(entry.path("stage").asText()).isEqualTo("CREDIT_MANAGER");
        });
    }

    @Test
    void committeeApprovalNeedsTwoVotes() {
        Fixture fixture = seed(false);
        publish(fixture, false, List.of(level("CREDIT_COMMITTEE", 1, null, 2, 24, true, false, false)));
        Officer first = officer("CREDIT_COMMITTEE", fixture.branchId());
        Officer second = officer("CREDIT_COMMITTEE", fixture.branchId());
        grant(first.id(), fixture.branchId(), "CREDIT_COMMITTEE", "500000.00");
        grant(second.id(), fixture.branchId(), "CREDIT_COMMITTEE", "500000.00");
        JsonNode opened = request(adminToken, fixture).path("data");
        JsonNode pending = exchange(first.token(), HttpMethod.POST, "/api/v1/approvals/" + opened.path("id").asText() + "/approve",
                Map.of("version", opened.path("version").asInt(), "reason", "First committee vote"), HttpStatus.OK).path("data");
        assertThat(pending.path("status").asText()).isEqualTo("PENDING");
        JsonNode approved = exchange(second.token(), HttpMethod.POST, "/api/v1/approvals/" + opened.path("id").asText() + "/approve",
                Map.of("version", pending.path("version").asInt(), "reason", "Second committee vote"), HttpStatus.OK).path("data");
        assertThat(approved.path("status").asText()).isEqualTo("APPROVED");
        assertThat(applicationStatus(fixture.applicationId())).isEqualTo("APPROVED");
    }

    @Test
    void changingAnApprovedApplicationWithdrawsTheDecision() {
        Fixture fixture = seed(false);
        publish(fixture, false, List.of(level("CREDIT_MANAGER", 1, null, 1, 24, true, false, false)));
        Officer checker = officer("CREDIT_MANAGER", fixture.branchId());
        grant(checker.id(), fixture.branchId(), "CREDIT_MANAGER", "500000.00");
        JsonNode opened = request(adminToken, fixture).path("data");
        JsonNode approved = exchange(checker.token(), HttpMethod.POST, "/api/v1/approvals/" + opened.path("id").asText() + "/approve",
                Map.of("version", opened.path("version").asInt(), "reason", "Approved on the assessed terms"), HttpStatus.OK).path("data");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE loan_applications SET amount = amount + 1000, revision_number = revision_number + 1 WHERE id = ?",
                fixture.applicationId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be modified");
        UUID assessmentId = jdbc.queryForObject(
                "SELECT id FROM credit_assessments WHERE application_id = ?",
                UUID.class,
                fixture.applicationId());
        jdbc.update(
                """
                INSERT INTO credit_assessment_results (
                    id, assessment_id, model_id, model_version_id, policy_version, score, risk_category, decision, explanation, calculations, evaluated_at)
                VALUES (?, ?, 'a1111111-1111-4111-8111-111111111111', 'a2222222-2222-4222-8222-222222222222',
                    'POL-2026.1', 80.00, 'LOW', 'REFER', 'Terms changed after approval', '{}'::jsonb, clock_timestamp() + INTERVAL '1 minute')
                """,
                UUID.randomUUID(), assessmentId);
        JsonNode superseded = exchange(adminToken, HttpMethod.GET, "/api/v1/approvals/" + approved.path("id").asText(), null, HttpStatus.OK).path("data");
        assertThat(superseded.path("status").asText()).isEqualTo("SUPERSEDED");
        assertThat(superseded.path("decision").asText()).isEqualTo("APPROVE");
        assertThat(applicationStatus(fixture.applicationId())).isEqualTo("PENDING_APPROVAL");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE loan_approval_history SET reason = 'rewritten' WHERE approval_id = ?",
                UUID.fromString(approved.path("id").asText())))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
    }

    @Test
    void returnCorrectionInformationAndBranchBoundariesAreEnforced() {
        Fixture fixture = seed(false);
        publish(fixture, false, List.of(level("CREDIT_MANAGER", 1, null, 1, 24, true, false, false)));
        Officer checker = officer("CREDIT_MANAGER", fixture.branchId());
        grant(checker.id(), fixture.branchId(), "CREDIT_MANAGER", "500000.00");
        Officer outsider = officer("CREDIT_MANAGER", insertBranch("OUT-" + UUID.randomUUID().toString().substring(0, 8)));
        JsonNode opened = request(adminToken, fixture).path("data");
        assertThat(exchangeRaw(outsider.token(), HttpMethod.GET, "/api/v1/approvals/" + opened.path("id").asText(), null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        JsonNode information = exchange(checker.token(), HttpMethod.POST, "/api/v1/approvals/" + opened.path("id").asText() + "/request-information",
                Map.of("version", opened.path("version").asInt(), "reason", "Need the latest bank statement"), HttpStatus.OK).path("data");
        assertThat(information.path("status").asText()).isEqualTo("INFORMATION_REQUESTED");

        Fixture returned = seed(false);
        publish(returned, false, List.of(level("CREDIT_MANAGER", 1, null, 1, 24, true, false, false)));
        Officer returner = officer("CREDIT_MANAGER", returned.branchId());
        grant(returner.id(), returned.branchId(), "CREDIT_MANAGER", "500000.00");
        JsonNode second = request(adminToken, returned).path("data");
        JsonNode sentBack = exchange(returner.token(), HttpMethod.POST, "/api/v1/approvals/" + second.path("id").asText() + "/return",
                Map.of("version", second.path("version").asInt(), "reason", "Correct the facility amount"), HttpStatus.OK).path("data");
        assertThat(sentBack.path("status").asText()).isEqualTo("RETURNED");
        assertThat(applicationStatus(returned.applicationId())).isEqualTo("DOCUMENTS_PENDING");
        Boolean correction = jdbc.queryForObject("SELECT correction_open FROM loan_applications WHERE id = ?", Boolean.class, returned.applicationId());
        assertThat(correction).isTrue();

        JsonNode queue = exchange(checker.token(), HttpMethod.GET, "/api/v1/approvals/my-queue", null, HttpStatus.OK).path("data");
        assertThat(queue.path("items").toString()).contains(opened.path("id").asText());
        JsonNode hidden = exchange(outsider.token(), HttpMethod.GET, "/api/v1/approvals/my-queue", null, HttpStatus.OK).path("data");
        assertThat(hidden.path("items").toString()).doesNotContain(opened.path("id").asText());
    }

    private JsonNode request(String token, Fixture fixture) {
        return exchange(token, HttpMethod.POST, "/api/v1/approvals", Map.of("applicationId", fixture.applicationId()), HttpStatus.CREATED);
    }

    private void publish(Fixture fixture, boolean automated, List<Map<String, Object>> levels) {
        JsonNode created = exchange(adminToken, HttpMethod.POST, "/api/v1/admin/approval-matrices",
                matrixBody(code(), fixture.productId(), automated, levels), HttpStatus.CREATED).path("data");
        exchange(adminToken, HttpMethod.POST, "/api/v1/admin/approval-matrices/" + created.path("id").asText() + "/activate", null, HttpStatus.OK);
    }

    private void grant(UUID userId, UUID branchId, String role, String maxAmount) {
        exchange(adminToken, HttpMethod.POST, "/api/v1/admin/approval-delegations", Map.of(
                "userId", userId,
                "roleCode", role,
                "stage", role,
                "branchId", branchId,
                "maxAmount", new BigDecimal(maxAmount),
                "validFrom", Instant.now().minusSeconds(120),
                "reason", "Integration test authority"), HttpStatus.CREATED);
    }

    private Map<String, Object> matrixBody(String code, UUID productId, boolean automated, List<Map<String, Object>> levels) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("name", "Matrix " + code);
        body.put("automatedAllowed", automated);
        body.put("amountMin", new BigDecimal("0.00"));
        body.put("amountMax", new BigDecimal("5000000.00"));
        if (productId != null) {
            body.put("productId", productId);
        }
        body.put("levels", levels);
        return body;
    }

    private Map<String, Object> level(
            String stage, int sequence, String max, int quorum, int expiry, boolean manual, boolean automated, boolean escalate) {
        Map<String, Object> body = new HashMap<>();
        body.put("stage", stage);
        body.put("sequence", sequence);
        body.put("roleCode", stage);
        body.put("minAmount", new BigDecimal("0.00"));
        if (max != null) {
            body.put("maxAmount", new BigDecimal(max));
        }
        body.put("quorum", quorum);
        body.put("expiryHours", expiry);
        body.put("manualRequired", manual);
        body.put("automated", automated);
        body.put("escalateOnExpiry", escalate);
        return body;
    }

    private Map<String, Object> individual(UUID applicationId) {
        Map<String, Object> individual = new HashMap<>();
        individual.put("monthlyGrossIncome", new BigDecimal("200000.00"));
        individual.put("netIncome", new BigDecimal("180000.00"));
        individual.put("monthlyExpenses", new BigDecimal("40000.00"));
        individual.put("existingLoanObligations", new BigDecimal("10000.00"));
        individual.put("employmentMonths", 36);
        individual.put("employmentStatus", "EMPLOYED");
        Map<String, Object> body = new HashMap<>();
        body.put("applicationId", applicationId);
        body.put("proposedMonthlyInstalment", new BigDecimal("10000.00"));
        body.put("individual", individual);
        body.put("repaymentHistory", "CLEAN");
        body.put("bankAccountTurnover", new BigDecimal("200000.00"));
        return body;
    }

    private Fixture seed(boolean automatedProduct) {
        return seed(automatedProduct, true);
    }

    private Fixture seed(boolean automatedProduct, boolean withAssessment) {
        return seed(automatedProduct, withAssessment, "approval-it", null);
    }

    private Fixture seed(boolean automatedProduct, boolean withAssessment, String createdBy, UUID branchId) {
        if (branchId == null) {
            branchId = insertBranch("AP-" + UUID.randomUUID().toString().substring(0, 8));
        }
        UUID customerId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO customers (
                    id, customer_reference, customer_type, status, category, display_name, email, phone_number,
                    branch_id, kyc_status, risk_classification, compliance_hold, created_by, updated_by)
                VALUES (?, ?, 'INDIVIDUAL', 'ACTIVE', 'RETAIL', 'Ada Okonkwo', ?, ?, ?, 'VERIFIED', 'LOW', FALSE, 'approval-it', 'approval-it')
                """,
                customerId,
                "CUS" + String.format("%08d", ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000)),
                "approval." + UUID.randomUUID() + "@example.com",
                uniquePhone(),
                branchId);
        UUID productId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String productCode = "AP" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update(
                "INSERT INTO loan_products (id, product_code, category, status, created_by, updated_by) VALUES (?, ?, 'PERSONAL', 'DRAFT', 'approval-it', 'approval-it')",
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
                    effective_from, activated_at, activated_by, created_by, updated_by, automated_approval_allowed)
                VALUES (
                    ?, ?, 1, 'ACTIVE', 'Personal loan', 'INDIVIDUAL', 'NGN',
                    1000.00, 5000000.00, 1, 60, 'MONTHS',
                    9.5000, 'REDUCING_BALANCE', 'ANNUAL', 'MONTHLY', 'ACTUAL_365',
                    2, 'HALF_UP', 0, 'ALLOWED',
                    FALSE, 0, TRUE, 500,
                    'PERCENTAGE_OF_OVERDUE', 2.0000, 0.00, 0,
                    NOW(), NOW(), 'approval-it', 'approval-it', 'approval-it', ?)
                """,
                versionId, productId, automatedProduct);
        jdbc.update("UPDATE loan_products SET status = 'ACTIVE', current_version_id = ?, updated_by = 'approval-it' WHERE id = ?", versionId, productId);
        UUID applicationId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO loan_applications (
                    id, reference, customer_id, product_id, product_version_id, branch_id, customer_segment, status,
                    revision_number, correction_open, amount, currency_code, purpose, tenure, tenure_unit,
                    repayment_frequency, channel, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'INDIVIDUAL', 'CREDIT_ASSESSMENT', 1, FALSE, 200000.00, 'NGN', 'Working capital',
                    12, 'MONTHS', 'MONTHLY', 'WEB', ?, 'approval-it')
                """,
                applicationId,
                "LA" + String.format("%012d", ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L)),
                customerId, productId, versionId, branchId, createdBy);
        if (withAssessment) {
            UUID assessmentId = UUID.randomUUID();
            UUID resultId = UUID.randomUUID();
            jdbc.update(
                    """
                    INSERT INTO credit_assessments (
                        id, reference, application_id, customer_id, product_id, product_version_id, branch_id, segment,
                        status, decision, score, risk_category, scoring_model_id, scoring_model_version_id, policy_version,
                        inputs, calculations, revision, created_by, updated_by)
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'INDIVIDUAL', 'EVALUATED', 'AUTO_APPROVE', 100.00, 'LOW',
                        'a1111111-1111-4111-8111-111111111111', 'a2222222-2222-4222-8222-222222222222', 'POL-2026.1',
                        '{}'::jsonb, '{}'::jsonb, 1, 'approval-it', 'approval-it')
                    """,
                    assessmentId,
                    "CA" + String.format("%012d", ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L)),
                    applicationId, customerId, productId, versionId, branchId);
            jdbc.update(
                    """
                    INSERT INTO credit_assessment_results (
                        id, assessment_id, model_id, model_version_id, policy_version, score, risk_category, decision, explanation, calculations)
                    VALUES (?, ?, 'a1111111-1111-4111-8111-111111111111', 'a2222222-2222-4222-8222-222222222222',
                        'POL-2026.1', 100.00, 'LOW', 'AUTO_APPROVE', 'Clear', '{}'::jsonb)
                    """,
                    resultId, assessmentId);
        }
        return new Fixture(branchId, productId, applicationId);
    }

    private String applicationStatus(UUID applicationId) {
        return jdbc.queryForObject("SELECT status FROM loan_applications WHERE id = ?", String.class, applicationId);
    }

    private Officer officer(String role, UUID branchId) {
        String email = role.toLowerCase(Locale.ROOT) + "." + UUID.randomUUID() + "@mfb.test";
        ResponseEntity<JsonNode> response = exchangeRaw(adminToken, HttpMethod.POST, "/api/v1/admin/users", Map.of(
                "fullName", "Approval Officer",
                "email", email,
                "phoneNumber", uniquePhone(),
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
        UUID id = jdbc.queryForObject("SELECT id FROM system_users WHERE email = ?", (rs, row) -> rs.getObject("id", UUID.class), email);
        return new Officer(id, email, accessToken);
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
        jdbc.update("INSERT INTO branches (id, code, name, created_by) VALUES (?, ?, ?, ?)", id, code, code, "approval-it");
        return id;
    }

    private static String code() {
        return "MX" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    private static String uniquePhone() {
        return "+2348" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    private <T> List<T> parallel(List<String> tokens, ConcurrentCall<T> call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tokens.size());
        CyclicBarrier barrier = new CyclicBarrier(tokens.size());
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (String token : tokens) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return call.run(token);
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
        assertThat(response.getStatusCode()).as(response.getBody() == null ? "" : response.getBody().toString()).isEqualTo(expected);
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

    @FunctionalInterface
    private interface ConcurrentCall<T> {
        T run(String token) throws Exception;
    }

    private record Fixture(UUID branchId, UUID productId, UUID applicationId) {
    }

    private record Officer(UUID id, String email, String token) {
    }

    private record Session(String accessToken) {
    }
}
