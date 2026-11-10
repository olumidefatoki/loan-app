package com.mfb.lending.foundation;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.mfb.lending.common.correlation.CorrelationIds;
import com.mfb.lending.config.RabbitMqConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
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
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
class PlatformFoundationIT {

    private static final List<String> FOUNDATION_TABLES = List.of(
            "branches",
            "system_configurations",
            "audit_logs",
            "integration_outbox",
            "integration_inbox");

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
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void flywayCreatesFoundationTablesAndNamedParametersAreUsed() {
        List<String> tables = namedParameterJdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (:names)
                """,
                new MapSqlParameterSource("names", FOUNDATION_TABLES),
                String.class);

        assertThat(tables).containsExactlyInAnyOrderElementsOf(FOUNDATION_TABLES);

        String platformName = namedParameterJdbcTemplate.queryForObject(
                "SELECT config_value FROM system_configurations WHERE config_key = :configKey",
                new MapSqlParameterSource("configKey", "platform.name"),
                String.class);
        assertThat(platformName).isEqualTo("MFB Digital Lending Platform");

        UUID branchId = UUID.randomUUID();
        namedParameterJdbcTemplate.update(
                """
                INSERT INTO branches (id, code, name, created_by)
                VALUES (:id, :code, :name, :createdBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", branchId)
                        .addValue("code", "HQ")
                        .addValue("name", "Head Office")
                        .addValue("createdBy", "foundation-it"));

        namedParameterJdbcTemplate.update(
                "UPDATE branches SET name = :name, updated_by = :updatedBy WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("id", branchId)
                        .addValue("name", "Head Office Updated")
                        .addValue("updatedBy", "foundation-it"));

        Map<String, Object> branch = jdbcTemplate.queryForMap(
                "SELECT name, updated_at FROM branches WHERE id = ?",
                branchId);
        assertThat(branch).containsEntry("name", "Head Office Updated");
        assertThat(branch.get("updated_at")).isNotNull();

        namedParameterJdbcTemplate.update(
                """
                INSERT INTO integration_outbox (aggregate_type, aggregate_id, event_type, payload)
                VALUES (:aggregateType, :aggregateId, :eventType, CAST(:payload AS JSONB))
                """,
                new MapSqlParameterSource()
                        .addValue("aggregateType", "platform")
                        .addValue("aggregateId", "foundation")
                        .addValue("eventType", "FoundationReady")
                        .addValue("payload", "{\"ready\":true}"));

        String outboxStatus = namedParameterJdbcTemplate.queryForObject(
                """
                SELECT status
                FROM integration_outbox
                WHERE aggregate_id = :aggregateId
                """,
                new MapSqlParameterSource("aggregateId", "foundation"),
                String.class);
        assertThat(outboxStatus).isEqualTo("PENDING");

        assertThatThrownBy(() -> namedParameterJdbcTemplate.update(
                """
                INSERT INTO integration_outbox (aggregate_type, aggregate_id, event_type, payload, status)
                VALUES (:aggregateType, :aggregateId, :eventType, CAST(:payload AS JSONB), :status)
                """,
                new MapSqlParameterSource()
                        .addValue("aggregateType", "platform")
                        .addValue("aggregateId", "bad")
                        .addValue("eventType", "FoundationReady")
                        .addValue("payload", "{\"ready\":false}")
                        .addValue("status", "NOPE")))
                .isInstanceOf(DataAccessException.class);

        MapSqlParameterSource inbox = new MapSqlParameterSource()
                .addValue("messageId", "message-1")
                .addValue("source", "core-banking")
                .addValue("eventType", "CustomerSynced")
                .addValue("payload", "{\"id\":\"1\"}");
        String inboxSql = """
                INSERT INTO integration_inbox (message_id, source, event_type, payload)
                VALUES (:messageId, :source, :eventType, CAST(:payload AS JSONB))
                """;
        namedParameterJdbcTemplate.update(inboxSql, inbox);
        assertThatThrownBy(() -> namedParameterJdbcTemplate.update(inboxSql, inbox))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void versionSwaggerAndHealthEndpointsHonorTheFoundationContract() {
        HttpHeaders headers = new HttpHeaders();
        headers.set(CorrelationIds.HEADER, "it-correlation-123");
        ResponseEntity<JsonNode> version = rest.exchange(
                "/api/v1/system/version",
                HttpMethod.GET,
                new HttpEntity<>(headers),
                JsonNode.class);

        assertThat(version.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(version.getHeaders().getFirst(CorrelationIds.HEADER)).isEqualTo("it-correlation-123");
        assertThat(version.getBody().path("success").asBoolean()).isTrue();
        assertThat(version.getBody().path("correlationId").asText()).isEqualTo("it-correlation-123");
        assertThat(version.getBody().path("data").path("version").asText()).isEqualTo("0.1.0");
        assertThat(version.getBody().path("data").path("springBootVersion").asText()).startsWith("3.5");

        HttpHeaders unsafe = new HttpHeaders();
        unsafe.set(CorrelationIds.HEADER, "not-safe!");
        ResponseEntity<JsonNode> replaced = rest.exchange(
                "/api/v1/system/version",
                HttpMethod.GET,
                new HttpEntity<>(unsafe),
                JsonNode.class);
        String generated = replaced.getHeaders().getFirst(CorrelationIds.HEADER);
        assertThat(generated).isNotBlank();
        assertThat(UUID.fromString(generated)).isNotNull();
        assertThat(replaced.getBody().path("correlationId").asText()).isEqualTo(generated);

        ResponseEntity<String> apiDocs = rest.getForEntity("/v3/api-docs/v1", String.class);
        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(apiDocs.getBody()).contains("MFB Digital Lending Platform");
        assertThat(apiDocs.getBody()).contains("/api/v1/system/version");

        ResponseEntity<String> swaggerUi = rest.getForEntity("/swagger-ui/index.html", String.class);
        assertThat(swaggerUi.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(swaggerUi.getBody()).contains("swagger");

        ResponseEntity<String> health = rest.getForEntity("/actuator/health", String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(health.getBody()).contains("\"status\":\"UP\"");
        assertThat(health.getBody()).doesNotContain("components");

        ResponseEntity<String> detailed = rest.withBasicAuth("ops", "test-ops-password")
                .getForEntity("/actuator/health", String.class);
        assertThat(detailed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detailed.getBody()).contains("components");
        assertThat(detailed.getBody()).contains("\"db\"");
        assertThat(detailed.getBody()).contains("\"redis\"");
        assertThat(detailed.getBody()).contains("\"rabbit\"");

        assertThat(rest.getForEntity("/actuator/health/liveness", String.class).getBody()).contains("\"status\":\"UP\"");
        assertThat(rest.getForEntity("/actuator/health/readiness", String.class).getBody()).contains("\"status\":\"UP\"");

        ResponseEntity<String> env = rest.getForEntity("/actuator/env", String.class);
        assertThat(env.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(env.getBody()).contains("UNAUTHORIZED");
        assertThat(env.getBody()).doesNotContain("DB_PASSWORD");

        assertThat(rest.getForEntity("/actuator/info", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(rest.getForEntity("/api/v1/unknown", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void redisAndRabbitMqConnectionsAreUsable() {
        redisTemplate.opsForValue().set("foundation:status", "ok");
        assertThat(redisTemplate.opsForValue().get("foundation:status")).isEqualTo("ok");

        rabbitTemplate.convertAndSend(RabbitMqConfig.PLATFORM_EXCHANGE, "foundation.ready", Map.of("ready", true));
    }
}
