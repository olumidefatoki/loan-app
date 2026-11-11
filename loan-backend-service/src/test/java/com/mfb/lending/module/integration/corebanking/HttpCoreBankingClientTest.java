package com.mfb.lending.module.integration.corebanking;

import java.time.Duration;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.mfb.lending.config.CoreBankingProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpCoreBankingClientTest {

    private final WireMockServer server = new WireMockServer(wireMockConfig().dynamicPort());

    @AfterEach
    void stop() {
        if (server.isRunning()) {
            server.stop();
        }
    }

    @Test
    void successTimeoutAuthenticationAndMissingPath() {
        server.start();
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/transport/FIND_CUSTOMER"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"outcome\":\"SUCCESSFUL\",\"attributes\":{\"customerReference\":\"c-1\",\"verified\":true}}")));
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/transport/SUBMIT_DISBURSEMENT"))
                .willReturn(aResponse().withStatus(200).withFixedDelay(1_000)
                        .withBody("{\"outcome\":\"SUCCESSFUL\",\"attributes\":{\"posted\":true}}")));
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/transport/SUBMIT_REPAYMENT"))
                .willReturn(aResponse().withStatus(401)));
        server.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/transport/REVERSAL"))
                .willReturn(aResponse().withStatus(503)));

        HttpCoreBankingClient client = client(server.baseUrl(), "BEARER", "token-value", Map.of(
                "FIND_CUSTOMER", "/transport/FIND_CUSTOMER",
                "SUBMIT_DISBURSEMENT", "/transport/SUBMIT_DISBURSEMENT",
                "SUBMIT_REPAYMENT", "/transport/SUBMIT_REPAYMENT",
                "REVERSAL", "/transport/REVERSAL"));

        CoreBankingClient.CoreTransportResponse found = client.exchange(request(CoreOperation.FIND_CUSTOMER, "enquiry-001"));
        assertThat(found.outcome()).isEqualTo(CoreOutcome.SUCCESSFUL);
        server.verify(postRequestedFor(urlEqualTo("/transport/FIND_CUSTOMER"))
                .withHeader("Authorization", equalTo("Bearer token-value")));

        assertThatThrownBy(() -> client.exchange(request(CoreOperation.SUBMIT_DISBURSEMENT, "pay-001")))
                .isInstanceOf(CoreTransportException.class)
                .extracting("failure")
                .isEqualTo(CoreTransportException.Failure.TIMEOUT);
        assertThatThrownBy(() -> client.exchange(request(CoreOperation.SUBMIT_REPAYMENT, "pay-002")))
                .isInstanceOf(CoreTransportException.class)
                .extracting("failure")
                .isEqualTo(CoreTransportException.Failure.AUTHENTICATION);
        assertThatThrownBy(() -> client.exchange(request(CoreOperation.REVERSAL, "pay-003")))
                .isInstanceOf(CoreTransportException.class)
                .extracting("failure")
                .isEqualTo(CoreTransportException.Failure.UNAVAILABLE);
        assertThatThrownBy(() -> client.exchange(request(CoreOperation.GL_TRANSACTIONS, "pay-004")))
                .isInstanceOf(CoreTransportException.class)
                .extracting("failure")
                .isEqualTo(CoreTransportException.Failure.NOT_CONFIGURED);
        server.verify(0, postRequestedFor(urlEqualTo("/transport/GL_TRANSACTIONS")));
    }

    private static HttpCoreBankingClient client(String baseUrl, String method, String token, Map<String, String> paths) {
        CoreBankingProperties properties = new CoreBankingProperties();
        properties.setProvider("http");
        properties.setBaseUrl(baseUrl);
        properties.setAuthenticationMethod(method);
        properties.setApiKey(token);
        properties.setRequestTimeout(Duration.ofMillis(200));
        properties.setConnectTimeout(Duration.ofMillis(200));
        properties.setPaths(paths);
        properties.validate();
        return new HttpCoreBankingClient(properties, new ObjectMapper());
    }

    private static CoreBankingClient.CoreTransportRequest request(CoreOperation operation, String key) {
        return new CoreBankingClient.CoreTransportRequest(operation, key, "CB000000000001", "corr-1", Map.of("amount", "10.00"));
    }
}
