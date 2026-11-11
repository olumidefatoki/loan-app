package com.mfb.lending.module.servicing;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.mfb.lending.module.auth.security.TotpVerifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
class ServicingIT {

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
    private TotpVerifier totp;

    private String adminToken;

    @BeforeAll
    void signInAdministrator() {
        adminToken = staffSession();
    }

    @Test
    void servicingAndAccountingApisExposeTheSeededLedger() {
        assertThat(exchange(null, HttpMethod.GET, "/api/v1/loan-accounts", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        JsonNode accounts = exchange(adminToken, HttpMethod.GET, "/api/v1/loan-accounts", null).getBody();
        assertThat(accounts.path("success").asBoolean()).isTrue();
        assertThat(accounts.path("data").path("total").asInt()).isZero();

        JsonNode journals = exchange(adminToken, HttpMethod.GET, "/api/v1/admin/accounting/journals", null).getBody();
        assertThat(journals.path("success").asBoolean()).isTrue();
        assertThat(journals.path("data").path("items").isArray()).isTrue();

        JsonNode reconciliation = exchange(adminToken, HttpMethod.GET, "/api/v1/admin/accounting/reconciliation", null).getBody();
        assertThat(reconciliation.path("data").path("matched").asBoolean()).isTrue();
        assertThat(reconciliation.path("data").path("difference").decimalValue()).isEqualByComparingTo("0");
        assertThat(reconciliation.path("data").path("currencyCode").asText()).isEqualTo("NGN");

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM chart_of_accounts", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounting_posting_rules", Integer.class)).isPositive();
        assertThat(jdbc.queryForObject(
                "SELECT priority FROM repayment_allocation_policies WHERE version_number = 1", String.class))
                .isEqualTo("PENALTY,FEE,INTEREST,PRINCIPAL");
    }

    @Test
    void postedJournalsAreProtectedByDatabaseTriggers() {
        assertThat(jdbc.queryForList("""
                SELECT tgname FROM pg_trigger
                WHERE tgname IN ('trg_journal_entries_immutable', 'trg_journal_entry_lines_immutable', 'trg_journal_entries_balanced')
                """, String.class)).containsExactlyInAnyOrder(
                "trg_journal_entries_immutable", "trg_journal_entry_lines_immutable", "trg_journal_entries_balanced");
        assertThat(jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid) FROM pg_constraint
                WHERE conname = 'ck_loan_accounts_closed'
                """, String.class)).contains("CLOSED");
    }

    private String staffSession() {
        JsonNode challenge = exchange(null, HttpMethod.POST, "/api/v1/admin/auth/login",
                Map.of("identifier", ADMIN_EMAIL, "password", PASSWORD)).getBody().path("data");
        JsonNode tokens = exchange(null, HttpMethod.POST, "/api/v1/admin/auth/mfa/verify", Map.of(
                "mfaToken", challenge.path("mfaToken").asText(),
                "code", totp.codeAt(ADMIN_MFA_SECRET, Instant.now()))).getBody().path("data");
        return tokens.path("accessToken").asText();
    }

    private ResponseEntity<JsonNode> exchange(String token, HttpMethod method, String path, Object body) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (body != null) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return rest.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
