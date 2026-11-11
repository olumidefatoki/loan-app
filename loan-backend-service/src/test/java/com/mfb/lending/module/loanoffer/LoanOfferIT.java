package com.mfb.lending.module.loanoffer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HexFormat;
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
import com.mfb.lending.module.loancalculation.LoanCalculationEngine;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.FeeInput;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.LoanQuote;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.LoanQuoteRequest;
import com.mfb.lending.module.loanproduct.domain.DayCountConvention;
import com.mfb.lending.module.loanproduct.domain.FeeCalculation;
import com.mfb.lending.module.loanproduct.domain.InterestMethod;
import com.mfb.lending.module.loanproduct.domain.InterestPeriod;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;
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
class LoanOfferIT {

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

    @Autowired
    private CapturedOtpStore capturedOtps;

    private String adminToken;
    private String customerToken;
    private UUID customerId;
    private UUID branchId;

    @BeforeAll
    void signIn() {
        adminToken = staffSession(ADMIN_EMAIL, PASSWORD, ADMIN_MFA_SECRET).accessToken();
        CustomerLogin customer = verifiedCustomer();
        customerToken = customer.token();
        customerId = customer.customerId();
        branchId = insertBranch("OF-" + UUID.randomUUID().toString().substring(0, 8));
        jdbc.update("UPDATE customers SET branch_id = ?, kyc_status = 'VERIFIED', display_name = 'Ada Okonkwo' WHERE id = ?", branchId, customerId);
    }

    @BeforeEach
    void resetRateLimits() {
        var keys = redisTemplate.keys("auth:rl:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void generatedOfferMatchesTheCalculationEngineAndCanBeAcceptedOnce() throws Exception {
        UUID applicationId = seedApplication(new BigDecimal("120000.00"), "APPROVED", true);
        JsonNode offer = exchange(adminToken, HttpMethod.POST, "/api/v1/loan-applications/" + applicationId + "/generate-offer", Map.of(), HttpStatus.CREATED)
                .path("data");
        JsonNode again = exchange(adminToken, HttpMethod.POST, "/api/v1/loan-applications/" + applicationId + "/generate-offer", Map.of(), HttpStatus.CREATED)
                .path("data");
        assertThat(again.path("id").asText()).isEqualTo(offer.path("id").asText());
        assertThat(again.path("offerVersion").asInt()).isEqualTo(1);
        assertThat(offer.path("status").asText()).isEqualTo("ISSUED");
        assertThat(offer.path("engineVersion").asText()).isEqualTo(LoanCalculationEngine.VERSION);
        assertThat(offer.path("customerName").asText()).isEqualTo("Ada Okonkwo");
        assertThat(offer.path("terms").path("processingFees").decimalValue()).isEqualByComparingTo("1200.00");
        assertThat(offer.path("terms").path("taxes").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(offer.path("signatureDisclaimer").asText()).contains("not a qualified electronic signature");
        assertThat(applicationStatus(applicationId)).isEqualTo("OFFER_ISSUED");

        LoanQuote expected = engineQuote(offer.path("terms"));
        JsonNode schedule = exchange(customerToken, HttpMethod.GET, "/api/v1/loan-offers/" + offer.path("id").asText() + "/schedule", null, HttpStatus.OK)
                .path("data");
        assertThat(schedule).hasSize(expected.schedule().size());
        BigDecimal principal = BigDecimal.ZERO;
        BigDecimal payments = BigDecimal.ZERO;
        for (int index = 0; index < schedule.size(); index++) {
            JsonNode row = schedule.get(index);
            var item = expected.schedule().get(index);
            assertThat(row.path("principalDue").decimalValue()).isEqualByComparingTo(item.principalDue());
            assertThat(row.path("interestDue").decimalValue()).isEqualByComparingTo(item.interestDue());
            assertThat(row.path("instalmentAmount").decimalValue()).isEqualByComparingTo(item.instalmentAmount());
            assertThat(row.path("dueDate").asText()).isEqualTo(item.dueDate().toString());
            principal = principal.add(row.path("principalDue").decimalValue());
            payments = payments.add(row.path("instalmentAmount").decimalValue());
        }
        assertThat(principal).isEqualByComparingTo(offer.path("terms").path("principal").decimalValue());
        assertThat(payments).isEqualByComparingTo(offer.path("terms").path("totalRepayment").decimalValue());
        assertThat(offer.path("calculationFingerprint").asText()).isEqualTo(expected.fingerprint());

        JsonNode mine = exchange(customerToken, HttpMethod.GET, "/api/v1/customers/me/loan-offers", null, HttpStatus.OK).path("data");
        assertThat(mine.path("items")).anySatisfy(item -> assertThat(item.path("id").asText()).isEqualTo(offer.path("id").asText()));

        String offerId = offer.path("id").asText();
        byte[] pdf = download(customerToken, "/api/v1/loan-offers/" + offerId + "/agreement/document");
        assertThat(new String(pdf, StandardCharsets.US_ASCII)).contains("Ada Okonkwo");
        assertThat(sha256(pdf)).isEqualTo(offer.path("agreementChecksum").asText());

        JsonNode changed = exchange(adminToken, HttpMethod.POST, "/api/v1/loan-applications/" + applicationId + "/generate-offer",
                Map.of("principal", "100000.00"), HttpStatus.CREATED).path("data");
        assertThat(changed.path("offerVersion").asInt()).isEqualTo(2);
        assertThat(changed.path("calculationFingerprint").asText()).isNotEqualTo(offer.path("calculationFingerprint").asText());
        assertThat(changed.path("agreementChecksum").asText()).isNotEqualTo(offer.path("agreementChecksum").asText());
        assertThat(changed.path("terms").path("principal").decimalValue()).isEqualByComparingTo("100000.00");
        JsonNode previous = exchange(customerToken, HttpMethod.GET, "/api/v1/loan-offers/" + offerId + "/agreement?version=1", null, HttpStatus.OK)
                .path("data");
        assertThat(previous.path("checksumSha256").asText()).isEqualTo(offer.path("agreementChecksum").asText());
        assertThat(previous.path("supersededAt").isNull()).isFalse();
        byte[] previousPdf = download(customerToken, "/api/v1/loan-offers/" + offerId + "/agreement/document?version=1");
        assertThat(sha256(previousPdf)).isEqualTo(offer.path("agreementChecksum").asText());

        ResponseEntity<JsonNode> stale = exchangeRaw(customerToken, HttpMethod.POST, "/api/v1/loan-offers/" + offerId + "/accept", Map.of(
                "version", offer.path("version").asInt(),
                "identityVerificationReference", "KYC-REF-1001",
                "agreementRead", true));
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        JsonNode accepted = exchange(customerToken, HttpMethod.POST, "/api/v1/loan-offers/" + offerId + "/accept", Map.of(
                "version", changed.path("version").asInt(),
                "identityVerificationReference", "KYC-REF-1001",
                "agreementRead", true,
                "expectedChecksum", changed.path("agreementChecksum").asText()), HttpStatus.OK).path("data");
        assertThat(accepted.path("status").asText()).isEqualTo("ACCEPTED");
        assertThat(applicationStatus(applicationId)).isEqualTo("OFFER_ACCEPTED");
        JsonNode agreement = exchange(customerToken, HttpMethod.GET, "/api/v1/loan-offers/" + offerId + "/agreement", null, HttpStatus.OK).path("data");
        assertThat(agreement.path("acceptance").path("signatureAssurance").asText()).isEqualTo("RECORDED_ACCEPTANCE");
        assertThat(agreement.path("acceptance").path("kycStatus").asText()).isEqualTo("VERIFIED");
        assertThat(agreement.path("acceptance").path("disclaimer").asText()).contains("not a qualified electronic signature");
        assertThat(exchangeRaw(customerToken, HttpMethod.POST, "/api/v1/loan-offers/" + offerId + "/accept", Map.of(
                "version", accepted.path("version").asInt(),
                "identityVerificationReference", "KYC-REF-1001",
                "agreementRead", true)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        String storageKey = jdbc.queryForObject(
                """
                SELECT agreement.storage_key
                FROM loan_agreements agreement
                JOIN loan_offer_versions version ON version.id = agreement.offer_version_id
                WHERE version.offer_id = ?::uuid
                  AND agreement.agreement_kind = 'LOAN'
                  AND agreement.superseded_at IS NULL
                """,
                String.class,
                offerId);
        Path file = Path.of(System.getProperty("java.io.tmpdir"), "mfb-loan-agreements-it").toAbsolutePath().normalize().resolve(storageKey);
        Files.write(file, "tampered".getBytes(StandardCharsets.US_ASCII));
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(customerToken);
        headers.setAccept(List.of(MediaType.APPLICATION_PDF, MediaType.APPLICATION_JSON));
        ResponseEntity<byte[]> tampered = rest.exchange(
                "/api/v1/loan-offers/" + offerId + "/agreement/document",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
        assertThat(tampered.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void unapprovedApplicationsAndAmountsAboveTheApprovalAreRejected() {
        UUID pending = seedApplication(new BigDecimal("50000.00"), "CREDIT_ASSESSMENT", false);
        assertThat(exchangeRaw(adminToken, HttpMethod.POST, "/api/v1/loan-applications/" + pending + "/generate-offer", Map.of()).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        UUID approved = seedApplication(new BigDecimal("80000.00"), "APPROVED", true);
        ResponseEntity<JsonNode> over = exchangeRaw(adminToken, HttpMethod.POST, "/api/v1/loan-applications/" + approved + "/generate-offer",
                Map.of("principal", "80000.01"));
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(over.getBody().path("errors").toString()).contains("approved amount");
    }

    @Test
    void expiredAndDeclinedOffersCannotBeAccepted() {
        UUID declining = seedApplication(new BigDecimal("40000.00"), "APPROVED", true);
        JsonNode declinedOffer = exchange(adminToken, HttpMethod.POST, "/api/v1/loan-applications/" + declining + "/generate-offer", Map.of(), HttpStatus.CREATED)
                .path("data");
        JsonNode declined = exchange(customerToken, HttpMethod.POST, "/api/v1/loan-offers/" + declinedOffer.path("id").asText() + "/decline",
                Map.of("version", declinedOffer.path("version").asInt(), "reason", "The customer chose another product"), HttpStatus.OK).path("data");
        assertThat(declined.path("status").asText()).isEqualTo("DECLINED");
        assertThat(applicationStatus(declining)).isEqualTo("WITHDRAWN");
        assertThat(exchangeRaw(customerToken, HttpMethod.POST, "/api/v1/loan-offers/" + declinedOffer.path("id").asText() + "/accept", Map.of(
                "version", declined.path("version").asInt(),
                "identityVerificationReference", "KYC-REF-1001",
                "agreementRead", true)).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        UUID expiring = seedApplication(new BigDecimal("30000.00"), "APPROVED", true);
        JsonNode expiringOffer = exchange(adminToken, HttpMethod.POST, "/api/v1/loan-applications/" + expiring + "/generate-offer", Map.of(), HttpStatus.CREATED)
                .path("data");
        jdbc.update("UPDATE loan_offers SET expires_at = now() - interval '1 minute' WHERE id = ?::uuid", expiringOffer.path("id").asText());
        ResponseEntity<JsonNode> expired = exchangeRaw(customerToken, HttpMethod.POST, "/api/v1/loan-offers/" + expiringOffer.path("id").asText() + "/accept",
                Map.of("version", expiringOffer.path("version").asInt(), "identityVerificationReference", "KYC-REF-1001", "agreementRead", true));
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(jdbc.queryForObject("SELECT status FROM loan_offers WHERE id = ?::uuid", String.class, expiringOffer.path("id").asText()))
                .isEqualTo("EXPIRED");
        assertThat(applicationStatus(expiring)).isEqualTo("EXPIRED");
    }

    @Test
    void concurrentAcceptanceAllowsOneWinner() throws Exception {
        UUID applicationId = seedApplication(new BigDecimal("25000.00"), "APPROVED", true);
        JsonNode offer = exchange(adminToken, HttpMethod.POST, "/api/v1/loan-applications/" + applicationId + "/generate-offer", Map.of(), HttpStatus.CREATED)
                .path("data");
        Map<String, Object> body = Map.of(
                "version", offer.path("version").asInt(),
                "identityVerificationReference", "KYC-REF-RACE",
                "agreementRead", true,
                "expectedChecksum", offer.path("agreementChecksum").asText());
        List<Integer> statuses = parallel(2, () -> exchangeRaw(customerToken, HttpMethod.POST,
                "/api/v1/loan-offers/" + offer.path("id").asText() + "/accept", body).getStatusCode().value());
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        Integer acceptances = jdbc.queryForObject(
                "SELECT COUNT(*) FROM loan_agreement_acceptances WHERE offer_id = ?::uuid",
                Integer.class,
                offer.path("id").asText());
        assertThat(acceptances).isEqualTo(1);
    }

    @Test
    void templatesAreVersionedAndTheContractIsPublished() {
        JsonNode created = exchange(adminToken, HttpMethod.POST, "/api/v1/admin/loan-agreement-templates", Map.of(
                "code", "OFFER_IT_" + UUID.randomUUID().toString().substring(0, 8).replace("-", "").toUpperCase(Locale.ROOT),
                "name", "Offer IT template",
                "kind", "GUARANTOR",
                "body", "Agreement for {{customerName}} principal {{principal}}"), HttpStatus.CREATED).path("data");
        assertThat(created.path("versionNumber").asInt()).isEqualTo(1);
        assertThat(created.path("checksumSha256").asText()).hasSize(64);
        JsonNode revised = exchange(adminToken, HttpMethod.PUT, "/api/v1/admin/loan-agreement-templates/" + created.path("id").asText(), Map.of(
                "code", created.path("code").asText(),
                "name", "Offer IT template",
                "kind", "GUARANTOR",
                "body", "Revised agreement for {{customerName}}"), HttpStatus.OK).path("data");
        assertThat(revised.path("versionNumber").asInt()).isEqualTo(2);
        assertThat(revised.path("id").asText()).isNotEqualTo(created.path("id").asText());
        assertThat(exchangeRaw(adminToken, HttpMethod.DELETE, "/api/v1/admin/loan-agreement-templates/" + created.path("id").asText(), null).getStatusCode())
                .isEqualTo(HttpStatus.NO_CONTENT);

        JsonNode spec = exchange(null, HttpMethod.GET, "/v3/api-docs/v1", null, HttpStatus.OK);
        assertThat(spec.path("paths").path("/api/v1/loan-applications/{id}/generate-offer").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/loan-offers/{id}").path("get").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/customers/me/loan-offers").path("get").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/loan-offers/{id}/accept").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/loan-offers/{id}/decline").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/loan-offers/{id}/agreement").path("get").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/loan-offers/{id}/schedule").path("get").isMissingNode()).isFalse();
        assertThat(spec.path("paths").path("/api/v1/admin/loan-agreement-templates").path("post").isMissingNode()).isFalse();
        assertThat(spec.path("info").path("description").asText()).contains("not a qualified digital signature");
    }

    private LoanQuote engineQuote(JsonNode terms) {
        List<FeeInput> fees = new ArrayList<>();
        terms.path("fees").forEach(fee -> fees.add(new FeeInput(
                fee.path("type").asText(),
                fee.path("name").asText(),
                FeeCalculation.valueOf(fee.path("calculation").asText()),
                fee.path("amount").isNull() || fee.path("amount").isMissingNode() ? null : fee.path("amount").decimalValue(),
                fee.path("rate").isNull() || fee.path("rate").isMissingNode() ? null : fee.path("rate").decimalValue())));
        return LoanCalculationEngine.quote(new LoanQuoteRequest(
                terms.path("principal").decimalValue(),
                terms.path("interestRate").decimalValue(),
                InterestMethod.valueOf(terms.path("interestMethod").asText()),
                InterestPeriod.valueOf(terms.path("interestPeriod").asText()),
                terms.path("tenure").asInt(),
                TenureUnit.valueOf(terms.path("tenureUnit").asText()),
                RepaymentFrequency.valueOf(terms.path("repaymentFrequency").asText()),
                DayCountConvention.valueOf(terms.path("dayCountConvention").asText()),
                terms.path("roundingPrecision").asInt(),
                RoundingMode.valueOf(terms.path("roundingMethod").asText()),
                terms.path("gracePeriodDays").asInt(),
                LocalDate.parse(terms.path("disbursementDate").asText()),
                terms.path("taxRate").decimalValue(),
                fees));
    }

    private UUID seedApplication(BigDecimal amount, String status, boolean approved) {
        UUID productId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String productCode = "OF" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update("INSERT INTO loan_products (id, product_code, category, status, created_by, updated_by) VALUES (?, ?, 'PERSONAL', 'DRAFT', 'offer-it', 'offer-it')",
                productId, productCode);
        jdbc.update(
                """
                INSERT INTO loan_product_versions (
                    id, product_id, version_number, status, product_name, customer_segment, currency_code,
                    minimum_amount, maximum_amount, minimum_tenure, maximum_tenure, tenure_unit,
                    interest_rate, interest_method, interest_period, repayment_frequency, day_count_convention,
                    rounding_precision, rounding_method, grace_period_days, early_repayment_rule,
                    guarantor_required, minimum_guarantors, credit_bureau_required,
                    penalty_basis, penalty_rate, penalty_amount, penalty_grace_days,
                    created_by, updated_by)
                VALUES (
                    ?, ?, 1, 'DRAFT', 'Offer personal loan', 'INDIVIDUAL', 'NGN',
                    1000.00, 5000000.00, 1, 60, 'MONTHS',
                    12.0000, 'REDUCING_BALANCE', 'ANNUAL', 'MONTHLY', 'ACTUAL_365',
                    2, 'HALF_UP', 0, 'ALLOWED',
                    FALSE, 0, FALSE,
                    'PERCENTAGE_OF_OVERDUE', 2.0000, 0.00, 3,
                    'offer-it', 'offer-it')
                """,
                versionId, productId);
        jdbc.update(
                """
                INSERT INTO loan_product_fees (id, version_id, fee_type, name, calculation, rate)
                VALUES (?, ?, 'PROCESSING', 'Processing', 'PERCENTAGE', 1.0000)
                """,
                UUID.randomUUID(), versionId);
        jdbc.update(
                """
                UPDATE loan_product_versions
                SET status = 'ACTIVE', effective_from = NOW(), activated_at = NOW(), activated_by = 'offer-it'
                WHERE id = ?
                """,
                versionId);
        jdbc.update("UPDATE loan_products SET status = 'ACTIVE', current_version_id = ?, updated_by = 'offer-it' WHERE id = ?", versionId, productId);
        UUID applicationId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO loan_applications (
                    id, reference, customer_id, product_id, product_version_id, branch_id, customer_segment, status,
                    revision_number, correction_open, amount, currency_code, purpose, tenure, tenure_unit,
                    repayment_frequency, channel, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'INDIVIDUAL', ?, 1, FALSE, ?, 'NGN', 'Working capital',
                    12, 'MONTHS', 'MONTHLY', 'WEB', 'offer-it', 'offer-it')
                """,
                applicationId,
                "LA" + String.format("%012d", ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L)),
                customerId, productId, versionId, branchId, status, amount);
        if (approved) {
            approve(applicationId, productId, versionId, amount);
        }
        return applicationId;
    }

    private void approve(UUID applicationId, UUID productId, UUID versionId, BigDecimal amount) {
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
                    '{}'::jsonb, '{}'::jsonb, 1, 'offer-it', 'offer-it')
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
        UUID matrixId = UUID.randomUUID();
        UUID matrixVersionId = UUID.randomUUID();
        String matrixCode = "MX" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        jdbc.update(
                "INSERT INTO approval_matrices (id, code, name, status, created_by, updated_by) VALUES (?, ?, 'Offer matrix', 'DRAFT', 'offer-it', 'offer-it')",
                matrixId, matrixCode);
        jdbc.update(
                """
                INSERT INTO approval_matrix_versions (
                    id, matrix_id, version_number, status, amount_min, automated_allowed, activated_by, activated_at, created_by)
                VALUES (?, ?, 1, 'ACTIVE', 0, FALSE, 'offer-it', NOW(), 'offer-it')
                """,
                matrixVersionId, matrixId);
        jdbc.update("UPDATE approval_matrices SET status = 'ACTIVE', current_version_id = ?, updated_by = 'offer-it' WHERE id = ?", matrixVersionId, matrixId);
        jdbc.update(
                """
                INSERT INTO loan_approvals (
                    id, reference, application_id, application_revision, credit_assessment_id, credit_result_id,
                    matrix_id, matrix_version_id, amount, currency_code, stage, stage_sequence, status, decision,
                    decision_reason, decided_at, automated, correlation_id, version)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?, 'NGN', 'CREDIT_MANAGER', 1, 'APPROVED', 'APPROVE',
                    'Approved', NOW(), FALSE, ?, 0)
                """,
                UUID.randomUUID(),
                "AP" + String.format("%012d", ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L)),
                applicationId, assessmentId, resultId, matrixId, matrixVersionId, amount, "offer-" + UUID.randomUUID().toString().substring(0, 8));
    }

    private String applicationStatus(UUID applicationId) {
        return jdbc.queryForObject("SELECT status FROM loan_applications WHERE id = ?", String.class, applicationId);
    }

    private CustomerLogin verifiedCustomer() {
        String email = "offer." + UUID.randomUUID() + "@example.com";
        String phone = uniquePhone();
        ResponseEntity<JsonNode> response = exchangeRaw(null, HttpMethod.POST, "/api/v1/auth/register", Map.of(
                "customerType", "INDIVIDUAL",
                "fullName", "Ada Okonkwo",
                "email", email,
                "phoneNumber", phone,
                "password", PASSWORD));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(exchangeRaw(null, HttpMethod.POST, "/api/v1/auth/verify-otp", Map.of(
                "identifier", email, "purpose", "EMAIL_VERIFICATION", "code", capturedOtps.latest(email, "EMAIL_VERIFICATION"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(exchangeRaw(null, HttpMethod.POST, "/api/v1/auth/verify-otp", Map.of(
                "identifier", phone, "purpose", "PHONE_VERIFICATION", "code", capturedOtps.latest(phone, "PHONE_VERIFICATION"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        JsonNode login = exchangeRaw(null, HttpMethod.POST, "/api/v1/auth/login", Map.of("identifier", email, "password", PASSWORD)).getBody();
        UUID userId = jdbc.queryForObject("SELECT id FROM system_users WHERE email = ?", (rs, row) -> rs.getObject(1, UUID.class), email);
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO customers (
                    id, customer_reference, customer_type, status, category, display_name, email, phone_number,
                    user_id, kyc_status, risk_classification, compliance_hold, created_by, updated_by)
                VALUES (?, ?, 'INDIVIDUAL', 'ACTIVE', 'RETAIL', 'Ada Okonkwo', ?, ?, ?, 'VERIFIED', 'LOW', FALSE, 'offer-it', 'offer-it')
                """,
                id,
                "CUS" + String.format("%08d", ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000)),
                email,
                phone,
                userId);
        return new CustomerLogin(login.path("data").path("accessToken").asText(), id);
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
        jdbc.update("INSERT INTO branches (id, code, name, created_by) VALUES (?, ?, ?, ?)", id, code, code, "offer-it");
        return id;
    }

    private static String uniquePhone() {
        return "+2348" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    private byte[] download(String token, String path) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<byte[]> response = rest.exchange(path, HttpMethod.GET, new HttpEntity<>(headers), byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private static String sha256(byte[] body) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
    }

    private List<Integer> parallel(int callers, ConcurrentCall call) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CyclicBarrier barrier = new CyclicBarrier(callers);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int index = 0; index < callers; index++) {
                futures.add(pool.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return call.run();
                }));
            }
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
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

    private record CustomerLogin(String token, UUID customerId) {
    }

    private record Session(String accessToken) {
    }

    @FunctionalInterface
    private interface ConcurrentCall {
        int run() throws Exception;
    }
}
