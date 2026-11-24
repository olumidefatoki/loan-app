package com.mfb.lending.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exports the springdoc group {@code v1} so the published specification matches the running controllers.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class OpenApiExportIT {

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
    private ObjectMapper objectMapper;

    @Test
    void exportV1Specification() throws Exception {
        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs/v1", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode spec = objectMapper.readTree(response.getBody());
        assertThat(spec.path("openapi").asText()).startsWith("3.");
        assertThat(spec.path("paths").size()).isGreaterThan(50);
        assertThat(spec.path("components").path("schemas").size()).isGreaterThan(20);
        assertThat(spec.path("components").path("schemas").path("ApiError").isObject()).isTrue();
        JsonNode login = spec.path("paths").path("/api/v1/auth/login").path("post");
        assertThat(login.path("parameters").findValuesAsText("name")).contains("X-Correlation-Id");
        assertThat(login.path("responses").path("400").path("content").path("application/json").path("schema").path("$ref").asText())
                .isEqualTo("#/components/schemas/ApiError");

        Path output = Path.of("target", "openapi", "lending-v1.json");
        Files.createDirectories(output.getParent());
        ObjectMapper pretty = objectMapper.copy().enable(SerializationFeature.INDENT_OUTPUT);
        Files.writeString(output, pretty.writeValueAsString(spec) + System.lineSeparator());
    }
}
