package com.mfb.lending.module.collections;

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
class CollectionsIT {

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
    void reportsExposeThePortfolioRulesAndTheCollectionsTablesAreProtected() {
        assertThat(exchange(null, HttpMethod.GET, "/api/v1/reports/par", null).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        JsonNode par = exchange(adminToken, HttpMethod.GET, "/api/v1/reports/par", null).getBody();
        assertThat(par.path("success").asBoolean()).isTrue();
        assertThat(par.path("data").path("denominatorRule").asText()).contains("outstanding principal");
        assertThat(par.path("data").path("numeratorRule").asText()).contains("PAR 90");
        assertThat(par.path("data").path("confirmedAllocationRule").asText()).contains("POSTED");
        assertThat(par.path("data").path("par30").path("thresholdDays").asInt()).isEqualTo(30);
        assertThat(par.path("data").path("par60").path("thresholdDays").asInt()).isEqualTo(60);
        assertThat(par.path("data").path("par90").path("thresholdDays").asInt()).isEqualTo(90);
        assertThat(par.path("data").path("denominatorOutstandingPrincipal").decimalValue()).isEqualByComparingTo("0");

        JsonNode collections = exchange(adminToken, HttpMethod.GET, "/api/v1/reports/collections", null).getBody();
        assertThat(collections.path("success").asBoolean()).isTrue();
        assertThat(collections.path("data").path("collectionExposureRule").asText()).contains("fees");

        JsonNode cases = exchange(adminToken, HttpMethod.GET, "/api/v1/collections/cases", null).getBody();
        assertThat(cases.path("data").path("total").asInt()).isZero();

        assertThat(jdbc.queryForList("SELECT code FROM delinquency_buckets ORDER BY sort_order", String.class))
                .containsExactly("CURRENT", "DPD_1_30", "DPD_31_60", "DPD_61_90", "DPD_ABOVE_90");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM collection_message_templates WHERE active = TRUE", Integer.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM permissions
                WHERE code IN ('COLLECTION_VIEW', 'COLLECTION_MANAGE', 'RESTRUCTURING_APPROVE')
                """, Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM pg_trigger WHERE tgname = 'trg_restructuring_approvals_maker'
                """, Integer.class)).isEqualTo(1);
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
