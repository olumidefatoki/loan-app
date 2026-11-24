package com.mfb.lending.module.administration;

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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PlatformOperationsIT {

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

    private String adminToken;

    @BeforeAll
    void signInAdministrator() {
        adminToken = staffSession();
    }

    @Test
    void administrationSurfacesAreProtectedAndTheAuditChainIsAppendOnly() {
        assertThat(exchange(null, HttpMethod.GET, "/api/v1/notifications", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(null, HttpMethod.GET, "/api/v1/complaints", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(exchange(null, HttpMethod.GET, "/api/v1/admin/audit-logs", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        JsonNode templates = exchange(adminToken, HttpMethod.GET, "/api/v1/admin/notification-templates?size=100", null).getBody();
        assertThat(templates.path("success").asBoolean()).isTrue();
        assertThat(templates.path("data").path("total").asInt()).isEqualTo(56);

        JsonNode audits = exchange(adminToken, HttpMethod.GET, "/api/v1/admin/audit-logs", null).getBody();
        assertThat(audits.path("success").asBoolean()).isTrue();
        assertThat(audits.path("data").path("chainIntact").asBoolean()).isTrue();

        JsonNode cases = exchange(adminToken, HttpMethod.GET, "/api/v1/admin/compliance/cases", null).getBody();
        assertThat(cases.path("success").asBoolean()).isTrue();
        assertThat(cases.path("data").path("total").asInt()).isZero();

        JsonNode calendars = exchange(adminToken, HttpMethod.GET, "/api/v1/admin/business-calendars", null).getBody();
        assertThat(calendars.path("success").asBoolean()).isTrue();
        assertThat(calendars.path("data").findValuesAsText("code")).contains("NG-DEFAULT");

        JsonNode health = exchange(adminToken, HttpMethod.GET, "/api/v1/admin/operations/health", null).getBody();
        assertThat(health.path("success").asBoolean()).isTrue();
        assertThat(health.path("data").path("database").asBoolean()).isTrue();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM permissions
                WHERE code IN (
                    'NOTIFICATION_VIEW', 'NOTIFICATION_MANAGE', 'AUDIT_VIEW', 'COMPLIANCE_VIEW', 'COMPLIANCE_MANAGE',
                    'COMPLIANCE_DECIDE', 'COMPLAINT_VIEW', 'COMPLAINT_MANAGE', 'OPERATIONS_VIEW')
                """, Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT audit_chain_intact()", Boolean.class)).isTrue();
        assertThatThrownBy(() -> jdbc.update("UPDATE audit_logs SET action = action"))
                .hasStackTraceContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM audit_logs"))
                .hasStackTraceContaining("append-only");
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
