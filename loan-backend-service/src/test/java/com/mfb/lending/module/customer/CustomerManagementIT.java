package com.mfb.lending.module.customer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import com.fasterxml.jackson.databind.JsonNode;
import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.module.auth.application.CapturedOtpStore;
import com.mfb.lending.module.auth.security.AesGcmCipher;
import com.mfb.lending.module.auth.security.TotpVerifier;
import com.mfb.lending.module.customer.application.CustomerEligibilityService;
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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomerManagementIT {

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

    @Autowired
    private CustomerEligibilityService eligibility;

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
    void individualCustomerCanBeOnboardedVerifiedAndClearedForDisbursement() {
        UUID branchId = insertBranch("LAG-" + UUID.randomUUID().toString().substring(0, 8));
        Staff officer = createStaff("RELATIONSHIP_OFFICER", branchId);
        String bvn = identityNumber();
        String nin = identityNumber();
        String email = "ada." + UUID.randomUUID() + "@example.com";
        String phone = uniquePhone();
        JsonNode created = exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers", customerBody(
                "INDIVIDUAL", "Ada Okonkwo", email, phone, branchId), HttpStatus.CREATED).path("data");
        String customerId = created.path("id").asText();
        assertThat(created.path("customerReference").asText()).startsWith("CUS");
        assertThat(created.path("disbursementAllowed").asBoolean()).isFalse();

        HttpHeaders headers = bearer(officer.accessToken());
        headers.set("X-Client-Channel", "MOBILE");
        JsonNode profile = exchange(headers, HttpMethod.POST, "/api/v1/customers/" + customerId + "/individual-profile",
                individualProfile("Ada Okonkwo", email, phone, bvn, nin), HttpStatus.OK).path("data");
        assertThat(profile.path("individual").path("bvnMasked").asText()).endsWith(bvn.substring(7));
        assertThat(profile.toString()).doesNotContain(bvn).doesNotContain(nin);
        String storedBvn = jdbc.queryForObject(
                "SELECT bvn_encrypted FROM individual_profiles WHERE customer_id = ?::uuid",
                String.class,
                customerId);
        assertThat(storedBvn).doesNotContain(bvn);
        assertThat(cipher.decrypt(storedBvn)).isEqualTo(bvn);
        String channel = jdbc.queryForObject(
                "SELECT channel FROM customer_consents WHERE customer_id = ?::uuid AND consent_type = 'DATA_PROCESSING'",
                String.class,
                customerId);
        assertThat(channel).isEqualTo("MOBILE");
        String audit = jdbc.queryForObject(
                "SELECT COALESCE(after_state::text, '') FROM audit_logs WHERE entity_id = ? AND action = 'CUSTOMER_PROFILE_SAVED' ORDER BY occurred_at DESC LIMIT 1",
                String.class,
                customerId);
        assertThat(audit).doesNotContain(bvn);

        upload(officer.accessToken(), customerId, "IDENTITY", pdf("identity"), "identity.pdf", HttpStatus.CREATED);
        upload(officer.accessToken(), customerId, "PROOF_OF_ADDRESS", pdf("address"), "address.pdf", HttpStatus.CREATED);
        JsonNode kyc = exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers/" + customerId + "/kyc", Map.of(), HttpStatus.OK).path("data");
        assertThat(kyc.path("kycStatus").asText()).isEqualTo("VERIFIED");
        assertThat(kyc.path("checks").findValuesAsText("outcome")).contains("PASSED");
        assertThat(kyc.path("disbursementAllowed").asBoolean()).isFalse();

        JsonNode account = exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers/" + customerId + "/accounts/verify",
                Map.of("bankCode", "058", "accountNumber", "0123456789"), HttpStatus.OK).path("data");
        assertThat(account.path("verificationStatus").asText()).isEqualTo("VERIFIED");
        assertThat(account.path("accountNumberMasked").asText()).endsWith("6789");
        assertThat(account.toString()).doesNotContain("0123456789");
        JsonNode accounts = exchange(officer.accessToken(), HttpMethod.GET, "/api/v1/customers/" + customerId + "/accounts", null, HttpStatus.OK);
        assertThat(accounts.path("data")).hasSize(1);

        JsonNode activated = exchange(officer.accessToken(), HttpMethod.PUT, "/api/v1/customers/" + customerId,
                Map.of("status", "ACTIVE"), HttpStatus.OK).path("data");
        assertThat(activated.path("status").asText()).isEqualTo("ACTIVE");
        assertThat(activated.path("disbursementAllowed").asBoolean()).isTrue();
        eligibility.assertDisbursementAllowed(UUID.fromString(customerId));

        JsonNode found = exchange(officer.accessToken(), HttpMethod.GET,
                "/api/v1/customers?reference=" + created.path("customerReference").asText(), null, HttpStatus.OK);
        assertThat(found.path("data").path("items").get(0).path("id").asText()).isEqualTo(customerId);

        byte[] downloaded = download(officer.accessToken(), customerId, upload(
                officer.accessToken(), customerId, "PAYSLIP", pdf("pay"), "pay.pdf", HttpStatus.CREATED));
        assertThat(new String(downloaded, StandardCharsets.US_ASCII)).startsWith("%PDF");
    }

    @Test
    void smeCustomerRequiresPartiesDocumentsAndRetainsBeneficialOwnership() {
        UUID branchId = insertBranch("ABJ-" + UUID.randomUUID().toString().substring(0, 8));
        Staff officer = createStaff("RELATIONSHIP_OFFICER", branchId);
        String email = "sme." + UUID.randomUUID() + "@example.com";
        String phone = uniquePhone();
        String customerId = exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers", customerBody(
                "SME", "Okeke Stores", email, phone, branchId), HttpStatus.CREATED).path("data").path("id").asText();
        JsonNode saved = exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers/" + customerId + "/sme-profile",
                smeProfile("RC" + sixDigits()), HttpStatus.OK).path("data");
        assertThat(saved.path("authorisedRepresentatives")).hasSize(1);
        assertThat(saved.path("sme").path("taxIdentificationNumberMasked").asText()).doesNotContain("12345678");

        exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers/" + customerId + "/directors", director(), HttpStatus.CREATED);
        exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers/" + customerId + "/beneficial-owners", owner(), HttpStatus.CREATED);
        upload(officer.accessToken(), customerId, "CAC", pdf("cac"), "cac.pdf", HttpStatus.CREATED);
        upload(officer.accessToken(), customerId, "PROOF_OF_ADDRESS", pdf("office"), "office.pdf", HttpStatus.CREATED);
        upload(officer.accessToken(), customerId, "DIRECTOR_IDENTIFICATION", pdf("director"), "director.pdf", HttpStatus.CREATED);
        JsonNode kyc = exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers/" + customerId + "/kyc", Map.of(), HttpStatus.OK).path("data");
        assertThat(kyc.path("kycStatus").asText()).isEqualTo("VERIFIED");
        Integer owners = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customer_beneficial_owners WHERE customer_id = ?::uuid",
                Integer.class,
                customerId);
        assertThat(owners).isEqualTo(1);
        JsonNode screening = exchange(officer.accessToken(), HttpMethod.GET, "/api/v1/customers/" + customerId + "/screening", null, HttpStatus.OK).path("data");
        assertThat(screening.path("results").findValuesAsText("screeningType")).contains("SANCTIONS", "PEP", "AML");
    }

    @Test
    void duplicateBvnIsRejected() {
        UUID branchId = insertBranch("PH-" + UUID.randomUUID().toString().substring(0, 8));
        Staff officer = createStaff("RELATIONSHIP_OFFICER", branchId);
        String bvn = identityNumber();
        String first = createIndividual(officer, branchId, "First Person", bvn, identityNumber());
        String second = exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers", customerBody(
                "INDIVIDUAL", "Second Person", "second." + UUID.randomUUID() + "@example.com", uniquePhone(), branchId),
                HttpStatus.CREATED).path("data").path("id").asText();
        JsonNode conflict = exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers/" + second + "/individual-profile",
                individualProfile("Second Person", "second.profile." + UUID.randomUUID() + "@example.com", uniquePhone(), bvn, identityNumber()),
                HttpStatus.CONFLICT);
        assertThat(conflict.path("code").asText()).isEqualTo("DUPLICATE_CUSTOMER");
        assertThat(first).isNotBlank();
    }

    @Test
    void invalidOversizedAndMaliciousDocumentsAreRejected() {
        UUID branchId = insertBranch("KN-" + UUID.randomUUID().toString().substring(0, 8));
        Staff officer = createStaff("RELATIONSHIP_OFFICER", branchId);
        String customerId = exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers", customerBody(
                "INDIVIDUAL", "Doc Customer", "doc." + UUID.randomUUID() + "@example.com", uniquePhone(), branchId),
                HttpStatus.CREATED).path("data").path("id").asText();
        ResponseEntity<JsonNode> text = uploadRaw(officer.accessToken(), customerId, "IDENTITY", "hello".getBytes(StandardCharsets.UTF_8), "note.txt", MediaType.TEXT_PLAIN);
        assertThat(text.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(text.getBody().path("code").asText()).isEqualTo("DOCUMENT_REJECTED");
        byte[] large = new byte[3000];
        byte[] header = pdf("x");
        System.arraycopy(header, 0, large, 0, header.length);
        ResponseEntity<JsonNode> oversized = uploadRaw(officer.accessToken(), customerId, "IDENTITY", large, "big.pdf", MediaType.APPLICATION_PDF);
        assertThat(oversized.getBody().path("code").asText()).isEqualTo("DOCUMENT_REJECTED");
        ResponseEntity<JsonNode> malware = uploadRaw(officer.accessToken(), customerId, "IDENTITY", pdf("EICAR-STANDARD-ANTIVIRUS-TEST-FILE"), "bad.pdf", MediaType.APPLICATION_PDF);
        assertThat(malware.getBody().path("code").asText()).isEqualTo("MALWARE_DETECTED");
    }

    @Test
    void kycFailureTimeoutAndSanctionsMatchOpenReviewCases() {
        UUID branchId = insertBranch("EN-" + UUID.randomUUID().toString().substring(0, 8));
        Staff officer = createStaff("RELATIONSHIP_OFFICER", branchId);
        String failedId = preparedIndividual(officer, branchId, "Failed Person", "99999999999", identityNumber());
        JsonNode failed = exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers/" + failedId + "/kyc", Map.of(), HttpStatus.OK).path("data");
        assertThat(failed.path("kycStatus").asText()).isEqualTo("FAILED");
        assertThat(failed.path("reviewCases")).isNotEmpty();
        assertThat(failed.path("disbursementAllowed").asBoolean()).isFalse();

        String timedOutId = preparedIndividual(officer, branchId, "Timeout Person", "00000000000", identityNumber());
        JsonNode timedOut = exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers/" + timedOutId + "/kyc", Map.of(), HttpStatus.OK).path("data");
        assertThat(timedOut.path("kycStatus").asText()).isEqualTo("REVIEW");
        assertThat(timedOut.path("checks").findValuesAsText("outcome")).contains("TIMED_OUT");
        assertThat(timedOut.path("reviewCases").findValuesAsText("caseType")).contains("PROVIDER_TIMEOUT");

        String sanctionedId = preparedIndividual(officer, branchId, "Ada SANCTIONED Okonkwo", identityNumber(), identityNumber());
        JsonNode sanctioned = exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers/" + sanctionedId + "/kyc", Map.of(), HttpStatus.OK).path("data");
        assertThat(sanctioned.path("complianceHold").asBoolean()).isTrue();
        assertThat(sanctioned.path("reviewCases").findValuesAsText("caseType")).contains("SANCTIONS_MATCH");
        assertThatThrownBy(() -> eligibility.assertDisbursementAllowed(UUID.fromString(sanctionedId)))
                .isInstanceOf(ApiException.class)
                .extracting("errorCode")
                .isEqualTo(ErrorCode.COMPLIANCE_HOLD);
    }

    @Test
    void customerDataIsIsolatedAndUnauthorisedCallersAreRejected() {
        ResponseEntity<JsonNode> anonymous = exchangeRaw(null, HttpMethod.GET, "/api/v1/customers", null);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        CustomerSession first = verifiedCustomer();
        CustomerSession second = verifiedCustomer();
        JsonNode own = exchange(first.accessToken(), HttpMethod.POST, "/api/v1/customers", customerBody(
                "INDIVIDUAL", "Self Customer", first.email(), first.phone(), null), HttpStatus.CREATED).path("data");
        JsonNode me = exchange(first.accessToken(), HttpMethod.GET, "/api/v1/customers/me", null, HttpStatus.OK).path("data");
        assertThat(me.path("id").asText()).isEqualTo(own.path("id").asText());
        JsonNode hidden = exchange(second.accessToken(), HttpMethod.GET, "/api/v1/customers/" + own.path("id").asText(), null, HttpStatus.NOT_FOUND);
        assertThat(hidden.path("code").asText()).isEqualTo("RESOURCE_NOT_FOUND");
        JsonNode listDenied = exchange(first.accessToken(), HttpMethod.GET, "/api/v1/customers", null, HttpStatus.FORBIDDEN);
        assertThat(listDenied.path("code").asText()).isEqualTo("FORBIDDEN");

        UUID branchA = insertBranch("A-" + UUID.randomUUID().toString().substring(0, 8));
        UUID branchB = insertBranch("B-" + UUID.randomUUID().toString().substring(0, 8));
        Staff officerA = createStaff("RELATIONSHIP_OFFICER", branchA);
        Staff officerB = createStaff("RELATIONSHIP_OFFICER", branchB);
        Staff operations = createStaff("OPERATIONS_OFFICER", branchA);
        String customerId = exchange(officerA.accessToken(), HttpMethod.POST, "/api/v1/customers", customerBody(
                "INDIVIDUAL", "Branch Customer", "branch." + UUID.randomUUID() + "@example.com", uniquePhone(), branchA),
                HttpStatus.CREATED).path("data").path("id").asText();
        assertThat(exchangeRaw(officerB.accessToken(), HttpMethod.GET, "/api/v1/customers/" + customerId, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(exchangeRaw(operations.accessToken(), HttpMethod.GET, "/api/v1/customers/" + customerId, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode docs = rest.getForEntity("/v3/api-docs/v1", JsonNode.class).getBody();
        assertThat(docs.toString()).contains("/api/v1/customers");
    }

    private String preparedIndividual(Staff officer, UUID branchId, String name, String bvn, String nin) {
        String customerId = createIndividual(officer, branchId, name, bvn, nin);
        upload(officer.accessToken(), customerId, "IDENTITY", pdf("identity"), "identity.pdf", HttpStatus.CREATED);
        upload(officer.accessToken(), customerId, "PROOF_OF_ADDRESS", pdf("address"), "address.pdf", HttpStatus.CREATED);
        return customerId;
    }

    private String createIndividual(Staff officer, UUID branchId, String name, String bvn, String nin) {
        String email = "person." + UUID.randomUUID() + "@example.com";
        String phone = uniquePhone();
        String customerId = exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers",
                customerBody("INDIVIDUAL", name, email, phone, branchId), HttpStatus.CREATED).path("data").path("id").asText();
        exchange(officer.accessToken(), HttpMethod.POST, "/api/v1/customers/" + customerId + "/individual-profile",
                individualProfile(name, email, phone, bvn, nin), HttpStatus.OK);
        return customerId;
    }

    private Map<String, Object> customerBody(String type, String name, String email, String phone, UUID branchId) {
        Map<String, Object> body = new HashMap<>();
        body.put("customerType", type);
        body.put("displayName", name);
        body.put("email", email);
        body.put("phoneNumber", phone);
        if (branchId != null) {
            body.put("branchId", branchId);
        }
        return body;
    }

    private Map<String, Object> individualProfile(String name, String email, String phone, String bvn, String nin) {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", name);
        body.put("dateOfBirth", "1990-05-14");
        body.put("phoneNumber", phone);
        body.put("email", email);
        body.put("bvn", bvn);
        body.put("nin", nin);
        body.put("residentialAddress", address());
        body.put("employmentStatus", "EMPLOYED");
        body.put("employerName", "Northwind Ltd");
        body.put("occupation", "Analyst");
        body.put("monthlyIncome", 250000);
        body.put("monthlyExpenses", 80000);
        body.put("nextOfKin", Map.of(
                "fullName", "Kin Person",
                "relationship", "Sibling",
                "phoneNumber", uniquePhone(),
                "address", "12 Kin Street, Ikeja"));
        body.put("consents", consents());
        return body;
    }

    private Map<String, Object> smeProfile(String cac) {
        Map<String, Object> body = new HashMap<>();
        body.put("registeredBusinessName", "Okeke Stores Limited");
        body.put("tradingName", "Okeke Stores");
        body.put("cacRegistrationNumber", cac);
        body.put("businessType", "LIMITED_LIABILITY");
        body.put("businessSector", "Retail trade");
        body.put("incorporationDate", "2018-04-01");
        body.put("businessAddress", address());
        body.put("annualTurnover", 12000000);
        body.put("monthlyRevenue", 1000000);
        body.put("operatingExpenses", 400000);
        body.put("taxIdentificationNumber", "12345678");
        body.put("authorisedRepresentatives", List.of(Map.of(
                "fullName", "Ngozi Okeke",
                "phoneNumber", uniquePhone(),
                "email", "ngozi." + UUID.randomUUID() + "@example.com",
                "position", "Managing Director")));
        body.put("consents", consents());
        return body;
    }

    private Map<String, Object> director() {
        Map<String, Object> body = new HashMap<>();
        body.put("fullName", "Ngozi Okeke");
        body.put("dateOfBirth", "1984-02-02");
        body.put("phoneNumber", uniquePhone());
        body.put("position", "Director");
        body.put("bvn", identityNumber());
        return body;
    }

    private Map<String, Object> owner() {
        Map<String, Object> body = director();
        body.put("fullName", "Chinedu Okeke");
        body.put("ownershipPercentage", 60);
        body.put("bvn", identityNumber());
        return body;
    }

    private static Map<String, Object> address() {
        return Map.of("line1", "14 Marina Road", "city", "Lagos", "state", "Lagos", "countryCode", "NG");
    }

    private static List<Map<String, Object>> consents() {
        return List.of(
                Map.of("type", "DATA_PROCESSING", "granted", true, "version", "2026-01"),
                Map.of("type", "IDENTITY_VERIFICATION", "granted", true, "version", "2026-01"));
    }

    private String upload(String token, String customerId, String type, byte[] body, String filename, HttpStatus expected) {
        ResponseEntity<JsonNode> response = uploadRaw(token, customerId, type, body, filename, MediaType.APPLICATION_PDF);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response.getBody().path("data").path("id").asText();
    }

    private ResponseEntity<JsonNode> uploadRaw(String token, String customerId, String type, byte[] body, String filename, MediaType mediaType) {
        HttpHeaders headers = bearer(token);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        HttpHeaders partHeaders = new HttpHeaders();
        partHeaders.setContentType(mediaType);
        ByteArrayResource resource = new ByteArrayResource(body) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("documentType", type);
        parts.add("file", new HttpEntity<>(resource, partHeaders));
        return rest.exchange("/api/v1/customers/" + customerId + "/documents", HttpMethod.POST, new HttpEntity<>(parts, headers), JsonNode.class);
    }

    private byte[] download(String token, String customerId, String documentId) {
        HttpHeaders headers = bearer(token);
        ResponseEntity<byte[]> response = rest.exchange(
                "/api/v1/customers/" + customerId + "/documents/" + documentId,
                HttpMethod.GET,
                new HttpEntity<>(headers),
                byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response.getBody();
    }

    private JsonNode exchange(String token, HttpMethod method, String path, Object body, HttpStatus expected) {
        return exchange(bearer(token), method, path, body, expected);
    }

    private JsonNode exchange(HttpHeaders headers, HttpMethod method, String path, Object body, HttpStatus expected) {
        if (body != null && !headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        ResponseEntity<JsonNode> response = rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(expected);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> exchangeRaw(String token, HttpMethod method, String path, Object body) {
        HttpHeaders headers = bearer(token);
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return headers;
    }

    private static byte[] pdf(String extra) {
        return ("%PDF-1.4\n" + extra + "\n%%EOF").getBytes(StandardCharsets.US_ASCII);
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
        return new CustomerSession(email, phone, login.path("data").path("accessToken").asText());
    }

    private Staff createStaff(String role, UUID branchId) {
        String email = role.toLowerCase() + "." + UUID.randomUUID() + "@mfb.test";
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

    private UUID insertBranch(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO branches (id, code, name, created_by) VALUES (?, ?, ?, ?)", id, code, code, "customer-it");
        return id;
    }

    private static String uniquePhone() {
        return "+2348" + String.format("%09d", ThreadLocalRandom.current().nextInt(1_000_000_000));
    }

    private static String identityNumber() {
        long value = 10_000_000_000L + ThreadLocalRandom.current().nextLong(80_000_000_000L);
        return Long.toString(value);
    }

    private static String sixDigits() {
        int value = ThreadLocalRandom.current().nextInt(100000, 900000);
        return Integer.toString(value);
    }

    private record Staff(String accessToken) {
    }

    private record Session(String accessToken) {
    }

    private record CustomerSession(String email, String phone, String accessToken) {
    }
}
