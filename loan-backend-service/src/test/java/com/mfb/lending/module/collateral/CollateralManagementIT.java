package com.mfb.lending.module.collateral;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
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
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
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
class CollateralManagementIT {

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
        adminToken = staffSession(ADMIN_EMAIL, PASSWORD, ADMIN_MFA_SECRET);
    }

    @BeforeEach
    void resetRateLimits() {
        var keys = redisTemplate.keys("auth:rl:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    @Test
    void valuationHistoryIsAuditableAndOnlyACollateralOfficerCanApprove() {
        Fixture fixture = seed();
        Staff originator = createStaff("RELATIONSHIP_OFFICER", fixture.branchId());
        Staff officer = createStaff("CREDIT_OFFICER", fixture.branchId());
        Staff secondOfficer = createStaff("CREDIT_OFFICER", fixture.branchId());

        JsonNode created = exchange(originator.token(), HttpMethod.POST, "/api/v1/collaterals",
                collateralBody(fixture.customerId(), "1000000.00", "800000.00", null), HttpStatus.CREATED).path("data");
        String id = created.path("collateral").path("id").asText();
        assertThat(created.path("collateral").path("reference").asText()).startsWith("CL");
        assertThat(created.path("collateral").path("collateralType").asText()).isEqualTo("RESIDENTIAL_PROPERTY");
        JsonNode eligible = created.path("collateral").path("eligibleLendingValue");
        assertThat(eligible.isNull() || eligible.isMissingNode()).isTrue();
        assertThat(created.path("valuations")).hasSize(1);
        assertThat(created.path("valuations").get(0).path("calculatedEligibleValue").decimalValue()).isEqualByComparingTo("700000.00");
        assertThat(created.path("valuations").get(0).path("status").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(created.toString()).doesNotContain("storageKey");

        String valuationId = created.path("valuations").get(0).path("id").asText();
        assertThat(exchangeRaw(originator.token(), HttpMethod.POST, "/api/v1/collaterals/" + id + "/verify",
                Map.of("check", "OWNERSHIP", "outcome", "VERIFIED")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchangeRaw(officer.token(), HttpMethod.POST, "/api/v1/collaterals/" + id + "/valuations/" + valuationId + "/decision",
                Map.of("decision", "APPROVE")).getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode revaluation = exchange(officer.token(), HttpMethod.POST, "/api/v1/collaterals/" + id + "/valuations",
                valuationBody("REVALUATION", "1200000.00", "900000.00"), HttpStatus.CREATED).path("data");
        assertThat(exchangeRaw(officer.token(), HttpMethod.POST,
                "/api/v1/collaterals/" + id + "/valuations/" + revaluation.path("id").asText() + "/decision",
                Map.of("decision", "APPROVE")).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        exchange(secondOfficer.token(), HttpMethod.POST,
                "/api/v1/collaterals/" + id + "/valuations/" + revaluation.path("id").asText() + "/decision",
                Map.of("decision", "APPROVE"), HttpStatus.OK);

        JsonNode detail = exchange(officer.token(), HttpMethod.GET, "/api/v1/collaterals/" + id, null, HttpStatus.OK).path("data");
        assertThat(detail.path("valuations")).hasSize(2);
        assertThat(detail.path("collateral").path("eligibleLendingValue").decimalValue()).isEqualByComparingTo("840000.00");
        assertThat(detail.path("collateral").path("marketValue").decimalValue()).isEqualByComparingTo("1200000.00");
        String audit = jdbc.queryForObject(
                "SELECT COALESCE(after_state::text, '') FROM audit_logs WHERE entity_id = ? AND action = 'COLLATERAL_VALUATION_DECIDED' ORDER BY occurred_at DESC LIMIT 1",
                String.class,
                id);
        assertThat(audit).contains("APPROVE");
    }

    @Test
    void sharedSecurityIsAllowedUntilTheEligibleValueIsExhausted() throws Exception {
        Fixture fixture = seed();
        Staff originator = createStaff("RELATIONSHIP_OFFICER", fixture.branchId());
        Staff officer = createStaff("CREDIT_OFFICER", fixture.branchId());
        String collateralId = approveCollateral(originator, officer, fixture.customerId());

        JsonNode exclusive = exchange(originator.token(), HttpMethod.POST, "/api/v1/loan-applications/" + fixture.firstApplicationId() + "/collaterals",
                Map.of("collateralId", collateralId, "allocatedAmount", new BigDecimal("700000.00"), "sharedSecurity", false),
                HttpStatus.CREATED).path("data");
        assertThat(exclusive.path("coverageRatio").decimalValue()).isEqualByComparingTo("1.400000");
        assertThat(exclusive.path("availableAmount").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(exchangeRaw(originator.token(), HttpMethod.POST, "/api/v1/loan-applications/" + fixture.secondApplicationId() + "/collaterals",
                Map.of("collateralId", collateralId, "allocatedAmount", new BigDecimal("1.00"), "sharedSecurity", true)).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        String sharedId = approveCollateral(originator, officer, fixture.customerId());
        exchange(originator.token(), HttpMethod.POST, "/api/v1/loan-applications/" + fixture.firstApplicationId() + "/collaterals",
                Map.of("collateralId", sharedId, "allocatedAmount", new BigDecimal("400000.00"), "sharedSecurity", true), HttpStatus.CREATED);
        JsonNode second = exchange(originator.token(), HttpMethod.POST, "/api/v1/loan-applications/" + fixture.secondApplicationId() + "/collaterals",
                Map.of("collateralId", sharedId, "allocatedAmount", new BigDecimal("300000.00"), "sharedSecurity", true), HttpStatus.CREATED).path("data");
        assertThat(second.path("availableAmount").decimalValue()).isEqualByComparingTo("0.00");
        JsonNode position = exchange(officer.token(), HttpMethod.GET, "/api/v1/loan-applications/" + fixture.firstApplicationId() + "/collaterals",
                null, HttpStatus.OK).path("data");
        assertThat(position.path("totalAllocated").decimalValue()).isEqualByComparingTo("1100000.00");
        assertThat(position.path("coverageRatio").decimalValue()).isEqualByComparingTo("2.200000");
        ResponseEntity<JsonNode> over = exchangeRaw(originator.token(), HttpMethod.POST,
                "/api/v1/loan-applications/" + fixture.thirdApplicationId() + "/collaterals",
                Map.of("collateralId", sharedId, "allocatedAmount", new BigDecimal("1.00"), "sharedSecurity", true));
        assertThat(over.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(over.getBody().path("message").asText()).contains("over-allocated");

        String raced = approveCollateral(originator, officer, fixture.customerId());
        List<Integer> results = parallel(2, () -> exchangeRaw(originator.token(), HttpMethod.POST,
                "/api/v1/loan-applications/" + (ThreadLocalRandom.current().nextBoolean()
                        ? fixture.firstApplicationId() : fixture.secondApplicationId()) + "/collaterals",
                Map.of("collateralId", raced, "allocatedAmount", new BigDecimal("700000.00"), "sharedSecurity", true))
                .getStatusCode().value());
        assertThat(results).containsExactlyInAnyOrder(201, 409);

        jdbc.update("UPDATE collaterals SET valuation_expires_on = CURRENT_DATE - 1 WHERE id = ?", UUID.fromString(sharedId));
        ResponseEntity<JsonNode> expired = exchangeRaw(originator.token(), HttpMethod.POST,
                "/api/v1/loan-applications/" + fixture.thirdApplicationId() + "/collaterals",
                Map.of("collateralId", sharedId, "allocatedAmount", new BigDecimal("1.00"), "sharedSecurity", true));
        assertThat(expired.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(expired.getBody().path("message").asText()).contains("expired");
    }

    @Test
    void guarantorIsNotApprovedSecurityUntilASecondOfficerAccepts() {
        Fixture fixture = seed();
        Staff originator = createStaff("RELATIONSHIP_OFFICER", fixture.branchId());
        Staff verifier = createStaff("CREDIT_OFFICER", fixture.branchId());
        Staff acceptor = createStaff("CREDIT_OFFICER", fixture.branchId());
        String bvn = randomDigits(11);
        assertThat(exchangeRaw(originator.token(), HttpMethod.POST, "/api/v1/guarantors", guarantorBody(bvn, false)).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode created = exchange(originator.token(), HttpMethod.POST, "/api/v1/guarantors", guarantorBody(bvn, true), HttpStatus.CREATED)
                .path("data");
        String id = created.path("id").asText();
        assertThat(created.path("approvedSecurity").asBoolean()).isFalse();
        assertThat(created.path("consentRecorded").asBoolean()).isTrue();
        assertThat(created.path("bvnMasked").asText()).endsWith(bvn.substring(7));
        assertThat(created.toString()).doesNotContain(bvn);
        String audit = jdbc.queryForObject(
                "SELECT COALESCE(after_state::text, '') FROM audit_logs WHERE entity_id = ? AND action = 'GUARANTOR_CREATED'",
                String.class,
                id);
        assertThat(audit).doesNotContain(bvn);

        JsonNode linked = exchange(originator.token(), HttpMethod.POST, "/api/v1/loan-applications/" + fixture.firstApplicationId() + "/guarantors",
                Map.of("guarantorId", id, "relationship", "Sibling"), HttpStatus.CREATED).path("data");
        assertThat(linked.path("approvedSecurity").asBoolean()).isFalse();

        exchange(verifier.token(), HttpMethod.POST, "/api/v1/guarantors/" + id + "/verify", Map.of("decision", "VERIFY"), HttpStatus.OK);
        assertThat(exchange(verifier.token(), HttpMethod.GET, "/api/v1/guarantors/" + id, null, HttpStatus.OK)
                .path("data").path("approvedSecurity").asBoolean()).isFalse();
        assertThat(exchangeRaw(acceptor.token(), HttpMethod.POST, "/api/v1/guarantors/" + id + "/accept", Map.of("decision", "ACCEPT"))
                .getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        upload(originator.token(), "/api/v1/guarantors/" + id + "/documents", "GUARANTEE_AGREEMENT", pdf("guarantee"), "guarantee.pdf");
        assertThat(exchangeRaw(verifier.token(), HttpMethod.POST, "/api/v1/guarantors/" + id + "/accept", Map.of("decision", "ACCEPT"))
                .getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode accepted = exchange(acceptor.token(), HttpMethod.POST, "/api/v1/guarantors/" + id + "/accept",
                Map.of("decision", "ACCEPT"), HttpStatus.OK).path("data");
        assertThat(accepted.path("approvedSecurity").asBoolean()).isTrue();
        assertThat(accepted.path("verificationStatus").asText()).isEqualTo("VERIFIED");
        assertThat(accepted.path("acceptanceStatus").asText()).isEqualTo("ACCEPTED");
        Boolean pledged = jdbc.queryForObject(
                "SELECT approved_security FROM loan_guarantors WHERE guarantor_id = ?",
                Boolean.class,
                UUID.fromString(id));
        assertThat(pledged).isTrue();
        assertThat(exchange(verifier.token(), HttpMethod.GET, "/api/v1/loan-applications/" + fixture.firstApplicationId() + "/guarantors",
                null, HttpStatus.OK).path("data").get(0).path("approvedSecurity").asBoolean()).isTrue();
    }

    @Test
    void rejectedCollateralCannotBePledgedAndReleaseFollowsSettlement() {
        Fixture fixture = seed();
        Staff originator = createStaff("RELATIONSHIP_OFFICER", fixture.branchId());
        Staff approver = createStaff("CREDIT_OFFICER", fixture.branchId());
        Staff releaser = createStaff("CREDIT_OFFICER", fixture.branchId());

        JsonNode rejected = exchange(originator.token(), HttpMethod.POST, "/api/v1/collaterals",
                collateralBody(fixture.customerId(), "500000.00", "400000.00", null), HttpStatus.CREATED).path("data");
        String rejectedId = rejected.path("collateral").path("id").asText();
        exchange(approver.token(), HttpMethod.POST, "/api/v1/collaterals/" + rejectedId + "/valuations/"
                + rejected.path("valuations").get(0).path("id").asText() + "/decision", Map.of("decision", "APPROVE"), HttpStatus.OK);
        exchange(approver.token(), HttpMethod.POST, "/api/v1/collaterals/" + rejectedId + "/verify",
                Map.of("check", "REJECT", "notes", "The title search shows an adverse claim."), HttpStatus.OK);
        ResponseEntity<JsonNode> blocked = exchangeRaw(originator.token(), HttpMethod.POST,
                "/api/v1/loan-applications/" + fixture.firstApplicationId() + "/collaterals",
                Map.of("collateralId", rejectedId, "allocatedAmount", new BigDecimal("100000.00"), "sharedSecurity", false));
        assertThat(blocked.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(blocked.getBody().path("message").asText()).contains("not approved");

        String collateralId = approveCollateral(originator, approver, fixture.customerId());
        exchange(originator.token(), HttpMethod.POST, "/api/v1/loan-applications/" + fixture.firstApplicationId() + "/collaterals",
                Map.of("collateralId", collateralId, "allocatedAmount", new BigDecimal("200000.00"), "sharedSecurity", false),
                HttpStatus.CREATED);
        ResponseEntity<JsonNode> open = exchangeRaw(releaser.token(), HttpMethod.POST, "/api/v1/collaterals/" + collateralId + "/release",
                Map.of("settlementReference", "SET-1", "dischargeReference", "DIS-1", "reason", "Facility still open"));
        assertThat(open.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);

        jdbc.update("UPDATE loan_applications SET status = 'DISBURSED' WHERE id = ?", fixture.firstApplicationId());
        assertThat(exchangeRaw(approver.token(), HttpMethod.POST, "/api/v1/collaterals/" + collateralId + "/release",
                Map.of("settlementReference", "SET-1", "dischargeReference", "DIS-1", "reason", "Loan settled")).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode released = exchange(releaser.token(), HttpMethod.POST, "/api/v1/collaterals/" + collateralId + "/release",
                Map.of("settlementReference", "SET-1", "dischargeReference", "DIS-1", "reason", "Loan settled"), HttpStatus.OK)
                .path("data");
        assertThat(released.path("collateral").path("verificationStatus").asText()).isEqualTo("RELEASED");
        assertThat(released.path("releases")).hasSize(1);
        assertThat(released.path("allocations").get(0).path("status").asText()).isEqualTo("RELEASED");
        assertThat(exchangeRaw(releaser.token(), HttpMethod.POST, "/api/v1/collaterals/" + collateralId + "/release",
                Map.of("settlementReference", "SET-2", "dischargeReference", "DIS-2", "reason", "Again")).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);
        Integer releases = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_logs WHERE entity_id = ? AND action = 'COLLATERAL_RELEASED'",
                Integer.class,
                collateralId);
        assertThat(releases).isEqualTo(1);

        byte[] file = download(originator.token(), collateralId);
        assertThat(new String(file, StandardCharsets.US_ASCII)).startsWith("%PDF");
        assertThat(exchangeRaw(null, HttpMethod.GET, "/api/v1/collaterals/" + collateralId + "/documents/" + documentId(originator.token(), collateralId), null)
                .getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private String approveCollateral(Staff originator, Staff officer, UUID customerId) {
        JsonNode created = exchange(originator.token(), HttpMethod.POST, "/api/v1/collaterals",
                collateralBody(customerId, "1000000.00", "800000.00", null), HttpStatus.CREATED).path("data");
        String id = created.path("collateral").path("id").asText();
        exchange(officer.token(), HttpMethod.POST, "/api/v1/collaterals/" + id + "/valuations/"
                + created.path("valuations").get(0).path("id").asText() + "/decision", Map.of("decision", "APPROVE"), HttpStatus.OK);
        upload(originator.token(), "/api/v1/collaterals/" + id + "/documents", "TITLE", pdf("title"), "title.pdf");
        upload(originator.token(), "/api/v1/collaterals/" + id + "/documents", "LEGAL_OPINION", pdf("legal"), "legal.pdf");
        upload(originator.token(), "/api/v1/collaterals/" + id + "/documents", "INSURANCE_POLICY", pdf("policy"), "policy.pdf");
        exchange(officer.token(), HttpMethod.POST, "/api/v1/collaterals/" + id + "/verify", Map.of("check", "OWNERSHIP", "outcome", "VERIFIED"), HttpStatus.OK);
        exchange(officer.token(), HttpMethod.POST, "/api/v1/collaterals/" + id + "/verify", Map.of("check", "DOCUMENTS", "outcome", "VERIFIED"), HttpStatus.OK);
        exchange(officer.token(), HttpMethod.POST, "/api/v1/collaterals/" + id + "/verify", Map.of("check", "LEGAL", "outcome", "VERIFIED"), HttpStatus.OK);
        exchange(officer.token(), HttpMethod.POST, "/api/v1/collaterals/" + id + "/verify", Map.of("check", "INSURANCE", "outcome", "VERIFIED"), HttpStatus.OK);
        exchange(officer.token(), HttpMethod.POST, "/api/v1/collaterals/" + id + "/verify", Map.of("check", "ENCUMBRANCE", "outcome", "CLEAR"), HttpStatus.OK);
        JsonNode approved = exchange(officer.token(), HttpMethod.POST, "/api/v1/collaterals/" + id + "/verify",
                Map.of("check", "APPROVE"), HttpStatus.OK).path("data");
        assertThat(approved.path("collateral").path("verificationStatus").asText()).isEqualTo("APPROVED");
        assertThat(approved.path("collateral").path("eligibleLendingValue").decimalValue()).isEqualByComparingTo("700000.00");
        assertThat(approved.path("collateral").path("availableAmount").decimalValue()).isEqualByComparingTo("700000.00");
        return id;
    }

    private Map<String, Object> collateralBody(UUID customerId, String market, String forcedSale, String eligible) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerId", customerId);
        body.put("collateralType", "RESIDENTIAL_PROPERTY");
        body.put("description", "Four bedroom house at Lekki");
        body.put("ownerName", "Ada Okonkwo");
        body.put("identificationNumber", "LAG-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("location", "Lekki Phase 1, Lagos");
        body.put("marketValue", new BigDecimal(market));
        body.put("forcedSaleValue", new BigDecimal(forcedSale));
        if (eligible != null) {
            body.put("eligibleLendingValue", new BigDecimal(eligible));
        }
        body.put("valuationDate", LocalDate.now().toString());
        body.put("valuerName", "Prime Valuers Limited");
        body.put("valuerReference", "PV-100");
        body.put("insurance", Map.of(
                "insurerName", "Leadway Assurance",
                "policyNumber", "POL-" + UUID.randomUUID().toString().substring(0, 8),
                "coverAmount", new BigDecimal("1500000.00"),
                "startDate", LocalDate.now().minusMonths(1).toString(),
                "expiryDate", LocalDate.now().plusYears(1).toString()));
        return body;
    }

    private Map<String, Object> valuationBody(String kind, String market, String forcedSale) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        body.put("marketValue", new BigDecimal(market));
        body.put("forcedSaleValue", new BigDecimal(forcedSale));
        body.put("valuationDate", LocalDate.now().toString());
        body.put("valuerName", "Prime Valuers Limited");
        body.put("valuerReference", "PV-200");
        return body;
    }

    private Map<String, Object> guarantorBody(String bvn, boolean consent) {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Tunde Balogun");
        body.put("phoneNumber", uniquePhone());
        body.put("email", "guarantor." + UUID.randomUUID() + "@example.com");
        body.put("addressLine", "12 Allen Avenue, Ikeja");
        body.put("bvn", bvn);
        body.put("nin", randomDigits(11));
        body.put("bvnVerificationReference", "BVN-REF-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("ninVerificationReference", "NIN-REF-" + UUID.randomUUID().toString().substring(0, 8));
        body.put("employmentStatus", "EMPLOYED");
        body.put("employerName", "Northwind Trading");
        body.put("jobTitle", "Supervisor");
        body.put("monthlyIncome", new BigDecimal("450000.00"));
        body.put("monthlyObligations", new BigDecimal("80000.00"));
        body.put("consentGiven", consent);
        body.put("consentStatement", "I agree to guarantee this facility and to the bank verifying my identity.");
        return body;
    }

    private String documentId(String token, String collateralId) {
        return exchange(token, HttpMethod.GET, "/api/v1/collaterals/" + collateralId, null, HttpStatus.OK)
                .path("data").path("documents").get(0).path("id").asText();
    }

    private byte[] download(String token, String collateralId) {
        String documentId = documentId(token, collateralId);
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        ResponseEntity<byte[]> response = rest.exchange(
                "/api/v1/collaterals/" + collateralId + "/documents/" + documentId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).contains("no-store");
        return response.getBody();
    }

    private void upload(String token, String path, String code, byte[] body, String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(MediaType.APPLICATION_PDF);
        ByteArrayResource resource = new ByteArrayResource(body) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("documentCode", code);
        parts.add("file", new HttpEntity<>(resource, partHeaders));
        ResponseEntity<JsonNode> response = rest.exchange(path, HttpMethod.POST, new HttpEntity<>(parts, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().toString()).doesNotContain("storageKey");
    }

    private Fixture seed() {
        UUID branchId = insertBranch("CL-" + UUID.randomUUID().toString().substring(0, 8));
        UUID customerId = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO customers (
                    id, customer_reference, customer_type, status, category, display_name, email, phone_number,
                    branch_id, kyc_status, risk_classification, created_by, updated_by)
                VALUES (?, ?, 'INDIVIDUAL', 'ACTIVE', 'RETAIL', 'Ada Okonkwo', ?, ?, ?, 'VERIFIED', 'LOW', 'collateral-it', 'collateral-it')
                """,
                customerId,
                "CUS" + String.format("%08d", ThreadLocalRandom.current().nextInt(10_000_000, 100_000_000)),
                "collateral." + UUID.randomUUID() + "@example.com",
                uniquePhone(),
                branchId);
        UUID productId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO loan_products (id, product_code, category, status, created_by, updated_by) VALUES (?, ?, 'PERSONAL', 'DRAFT', 'collateral-it', 'collateral-it')",
                productId,
                "CL" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT));
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
                    FALSE, 0, FALSE, NULL,
                    'PERCENTAGE_OF_OVERDUE', 2.0000, 0.00, 0,
                    NOW(), NOW(), 'collateral-it', 'collateral-it', 'collateral-it')
                """,
                versionId, productId);
        jdbc.update("UPDATE loan_products SET status = 'ACTIVE', current_version_id = ? WHERE id = ?", versionId, productId);
        UUID first = application(customerId, productId, versionId, branchId);
        UUID second = application(customerId, productId, versionId, branchId);
        UUID third = application(customerId, productId, versionId, branchId);
        return new Fixture(branchId, customerId, first, second, third);
    }

    private UUID application(UUID customerId, UUID productId, UUID versionId, UUID branchId) {
        UUID id = UUID.randomUUID();
        jdbc.update(
                """
                INSERT INTO loan_applications (
                    id, reference, customer_id, product_id, product_version_id, branch_id, customer_segment, status,
                    revision_number, correction_open, amount, currency_code, purpose, tenure, tenure_unit,
                    repayment_frequency, channel, created_by, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, 'INDIVIDUAL', 'SUBMITTED', 1, FALSE, 500000.00, 'NGN', 'Working capital',
                    12, 'MONTHS', 'MONTHLY', 'WEB', 'collateral-it', 'collateral-it')
                """,
                id,
                "LA" + String.format("%012d", ThreadLocalRandom.current().nextLong(1, 1_000_000_000_000L)),
                customerId,
                productId,
                versionId,
                branchId);
        return id;
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

    private Staff createStaff(String role, UUID branchId) {
        String email = role.toLowerCase(Locale.ROOT) + "." + UUID.randomUUID() + "@mfb.test";
        ResponseEntity<JsonNode> response = exchangeRaw(adminToken, HttpMethod.POST, "/api/v1/admin/users", Map.of(
                "fullName", "Collateral Staff",
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
                : staffSession(email, PASSWORD, secret);
        assertThat(accessToken).isNotBlank();
        return new Staff(accessToken);
    }

    private String staffSession(String email, String password, String secret) {
        JsonNode challenge = exchangeRaw(null, HttpMethod.POST, "/api/v1/admin/auth/login",
                Map.of("identifier", email, "password", password)).getBody().path("data");
        return exchangeRaw(null, HttpMethod.POST, "/api/v1/admin/auth/mfa/verify", Map.of(
                "mfaToken", challenge.path("mfaToken").asText(),
                "code", totp.codeAt(secret, Instant.now()))).getBody().path("data").path("accessToken").asText();
    }

    private UUID insertBranch(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO branches (id, code, name, created_by) VALUES (?, ?, ?, ?)", id, code, code, "collateral-it");
        return id;
    }

    private static String uniquePhone() {
        return "+2348" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    private static String randomDigits(int length) {
        StringBuilder digits = new StringBuilder(length);
        digits.append(ThreadLocalRandom.current().nextInt(1, 10));
        for (int index = 1; index < length; index++) {
            digits.append(ThreadLocalRandom.current().nextInt(10));
        }
        return digits.toString();
    }

    private static byte[] pdf(String extra) {
        return ("%PDF-1.4\n" + extra + "\n%%EOF").getBytes(StandardCharsets.US_ASCII);
    }

    @FunctionalInterface
    private interface ConcurrentCall<T> {
        T run() throws Exception;
    }

    private record Fixture(UUID branchId, UUID customerId, UUID firstApplicationId, UUID secondApplicationId, UUID thirdApplicationId) {
    }

    private record Staff(String token) {
    }
}
