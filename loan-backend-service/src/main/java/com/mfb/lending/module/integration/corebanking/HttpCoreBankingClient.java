package com.mfb.lending.module.integration.corebanking;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.correlation.CorrelationIds;
import com.mfb.lending.config.CoreBankingProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/**
 * Transport for a future provider contract. Paths and credentials come from configuration.
 * A call whose path is empty is refused before any network request.
 */
public class HttpCoreBankingClient implements CoreBankingClient {

    private final CoreBankingProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient client;

    public HttpCoreBankingClient(CoreBankingProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getRequestTimeout());
        this.client = RestClient.builder()
                .baseUrl(trim(properties.getBaseUrl()))
                .requestFactory(factory)
                .build();
    }

    @Override
    public CoreTransportResponse exchange(CoreTransportRequest request) {
        String path = properties.path(request.operation().name());
        if (path.isBlank()) {
            throw new CoreTransportException(
                    CoreTransportException.Failure.NOT_CONFIGURED,
                    "No core banking path is configured for " + request.operation().name());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("operation", request.operation().name());
        body.put("idempotencyKey", request.idempotencyKey());
        body.put("transactionReference", request.transactionReference());
        body.put("attributes", request.attributes());
        try {
            String response = client.post()
                    .uri(path.startsWith("/") ? path : "/" + path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header(CorrelationIds.HEADER, request.correlationId() == null ? "" : request.correlationId())
                    .header("X-Idempotency-Key", request.idempotencyKey() == null ? "" : request.idempotencyKey())
                    .headers(this::authenticate)
                    .body(body)
                    .retrieve()
                    .body(String.class);
            return parse(response);
        } catch (RestClientResponseException ex) {
            throw mapStatus(ex);
        } catch (ResourceAccessException ex) {
            if (causedBy(ex, HttpTimeoutException.class) || causedBy(ex, java.net.SocketTimeoutException.class)) {
                throw new CoreTransportException(CoreTransportException.Failure.TIMEOUT, "Core banking request timed out");
            }
            if (causedBy(ex, ConnectException.class)) {
                throw new CoreTransportException(CoreTransportException.Failure.UNAVAILABLE, "Core banking connection failed");
            }
            throw new CoreTransportException(CoreTransportException.Failure.UNAVAILABLE, "Core banking request failed: " + ex.getMessage());
        }
    }

    private void authenticate(org.springframework.http.HttpHeaders headers) {
        String method = properties.getAuthenticationMethod() == null ? "NONE" : properties.getAuthenticationMethod();
        switch (method) {
            case "API_KEY" -> headers.set(properties.getApiKeyHeader(), properties.getApiKey());
            case "BEARER" -> headers.setBearerAuth(properties.getApiKey());
            case "BASIC" -> {
                String token = properties.getClientId() + ":" + properties.getClientSecret();
                headers.set(org.springframework.http.HttpHeaders.AUTHORIZATION,
                        "Basic " + Base64.getEncoder().encodeToString(token.getBytes(StandardCharsets.UTF_8)));
            }
            case "NONE" -> {
                // Explicitly unauthenticated transport. A real provider must select a configured method.
            }
            default -> throw new CoreTransportException(
                    CoreTransportException.Failure.NOT_CONFIGURED,
                    "Core banking authentication method is not configured");
        }
    }

    private CoreTransportResponse parse(String response) {
        if (response == null || response.isBlank()) {
            throw new CoreTransportException(CoreTransportException.Failure.UNPARSEABLE, "Core banking response was empty");
        }
        try {
            JsonNode node = objectMapper.readTree(response);
            if (node.get("outcome") == null || !node.get("outcome").isTextual()) {
                throw new CoreTransportException(CoreTransportException.Failure.UNPARSEABLE, "Core banking response has no outcome");
            }
            CoreOutcome outcome = CoreOutcome.valueOf(node.get("outcome").asText());
            String providerReference = text(node, "providerReference");
            String message = text(node, "message");
            Map<String, Object> attributes = node.get("attributes") != null && node.get("attributes").isObject()
                    ? objectMapper.convertValue(node.get("attributes"), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    })
                    : Map.of();
            return new CoreTransportResponse(outcome, providerReference, message, attributes);
        } catch (CoreTransportException ex) {
            throw ex;
        } catch (IllegalArgumentException ex) {
            throw new CoreTransportException(CoreTransportException.Failure.UNPARSEABLE, "Core banking outcome was not recognised");
        } catch (Exception ex) {
            throw new CoreTransportException(CoreTransportException.Failure.UNPARSEABLE, "Core banking response could not be read");
        }
    }

    private static CoreTransportException mapStatus(RestClientResponseException ex) {
        int status = ex.getStatusCode().value();
        if (status == 401 || status == 403) {
            return new CoreTransportException(CoreTransportException.Failure.AUTHENTICATION, "Core banking authentication failed");
        }
        if (status == 404) {
            return new CoreTransportException(CoreTransportException.Failure.REJECTED, "Core banking resource was not found");
        }
        if (status >= 400 && status < 500) {
            return new CoreTransportException(CoreTransportException.Failure.REJECTED, "Core banking rejected the instruction");
        }
        return new CoreTransportException(CoreTransportException.Failure.UNAVAILABLE, "Core banking returned " + status);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static boolean causedBy(Throwable throwable, Class<? extends Throwable> type) {
        Throwable current = throwable;
        while (current != null) {
            if (type.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static String trim(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
