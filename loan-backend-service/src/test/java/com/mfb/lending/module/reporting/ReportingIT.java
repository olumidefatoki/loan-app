package com.mfb.lending.module.reporting;

import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.mfb.lending.module.auth.security.TotpVerifier;
import com.mfb.lending.module.reporting.application.ReportingJobs;
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
class ReportingIT {

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
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TotpVerifier totp;

    @Autowired
    private ReportingJobs jobs;

    private String adminToken;

    @BeforeAll
    void signInAdministrator() {
        adminToken = staffSession();
    }

    @Test
    void emptyBookDashboardsReconcileAndASmallExportDownloadsWithItsToken() {
        assertThat(exchange(null, HttpMethod.GET, "/api/v1/dashboard/customer", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        JsonNode operations = body(adminToken, HttpMethod.GET, "/api/v1/dashboard/operations", null);
        assertThat(operations.path("applications").path("total").asLong()).isZero();
        assertThat(operations.path("outstandingPrincipal").decimalValue()).isEqualByComparingTo("0");
        assertThat(operations.path("collectionRateDefined").asBoolean()).isFalse();

        JsonNode portfolio = body(adminToken, HttpMethod.GET, "/api/v1/reports/loan-portfolio", null);
        assertThat(portfolio.path("par30").decimalValue()).isEqualByComparingTo("0");
        assertThat(portfolio.path("defaultRate").decimalValue()).isEqualByComparingTo("0");
        assertThat(portfolio.path("rules").path("par").asText()).contains("POSTED");

        JsonNode reconciliation = body(adminToken, HttpMethod.GET, "/api/v1/reports/reconciliation", null);
        assertThat(reconciliation.path("measures")).isNotEmpty();
        reconciliation.path("measures").forEach(measure -> assertThat(measure.path("variance").decimalValue()).isEqualByComparingTo("0"));

        JsonNode collectionsPar = body(adminToken, HttpMethod.GET, "/api/v1/reports/par", null);
        assertThat(collectionsPar.path("denominatorRule").asText()).contains("outstanding principal");

        ResponseEntity<JsonNode> created = exchange(adminToken, HttpMethod.POST, "/api/v1/reports/exports",
                Map.of("report", "DISBURSEMENTS", "format", "CSV"));
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode export = created.getBody().path("data");
        assertThat(export.path("status").asText()).isEqualTo("READY");
        String token = export.path("token").asText();
        String id = export.path("id").asText();

        JsonNode status = body(adminToken, HttpMethod.GET, "/api/v1/reports/exports/" + id, null);
        assertThat(status.has("token")).isFalse();

        assertThat(exchange(adminToken, HttpMethod.GET, "/api/v1/reports/exports/" + id + "/download", null).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        ResponseEntity<byte[]> file = rest.exchange("/api/v1/reports/exports/" + id + "/download?token=" + token, HttpMethod.GET,
                new HttpEntity<>(bearer(adminToken)), byte[].class);
        assertThat(file.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(new String(file.getBody())).contains("Journal");

        assertThat(jdbc.queryForObject("SELECT code FROM chart_of_accounts WHERE code = '5100'", String.class)).isEqualTo("5100");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM role_permissions rp
                JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE p.code = 'REPORT_EXPORT' AND r.code = 'AUDITOR'
                """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM role_permissions rp
                JOIN roles r ON r.id = rp.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE p.code = 'REPORT_EXPORT' AND r.code = 'SYSTEM_ADMIN'
                """, Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_matviews WHERE matviewname = 'mv_portfolio_position'", Integer.class)).isEqualTo(1);

        jobs.runSnapshot();
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM reporting_snapshots
                WHERE branch_key = 'ffffffff-ffff-ffff-ffff-ffffffffffff'
                """, Integer.class)).isEqualTo(1);
    }

    private JsonNode body(String token, HttpMethod method, String path, Object payload) {
        ResponseEntity<JsonNode> response = exchange(token, method, path, payload);
        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getBody().path("success").asBoolean()).isTrue();
        return response.getBody().path("data");
    }

    private String staffSession() {
        JsonNode challenge = exchange(null, HttpMethod.POST, "/api/v1/admin/auth/login",
                Map.of("identifier", ADMIN_EMAIL, "password", PASSWORD)).getBody().path("data");
        JsonNode tokens = exchange(null, HttpMethod.POST, "/api/v1/admin/auth/mfa/verify", Map.of(
                "mfaToken", challenge.path("mfaToken").asText(),
                "code", totp.codeAt(ADMIN_MFA_SECRET, Instant.now()))).getBody().path("data");
        return tokens.path("accessToken").asText();
    }

    private ResponseEntity<JsonNode> exchange(String token, HttpMethod method, String path, Object payload) {
        return rest.exchange(path, method, new HttpEntity<>(payload, headers(token, payload != null)), JsonNode.class);
    }

    private static HttpHeaders bearer(String token) {
        return headers(token, false);
    }

    private static HttpHeaders headers(String token, boolean json) {
        HttpHeaders headers = new HttpHeaders();
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (json) {
            headers.setContentType(MediaType.APPLICATION_JSON);
        }
        return headers;
    }
}
