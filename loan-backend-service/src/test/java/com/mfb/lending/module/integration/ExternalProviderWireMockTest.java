package com.mfb.lending.module.integration;

import java.time.LocalDate;
import java.util.UUID;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.mfb.lending.module.credit.bureau.BureauSubject;
import com.mfb.lending.module.credit.bureau.UnconfiguredCreditBureau;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.kyc.MockBvnVerifier;
import com.mfb.lending.module.customer.kyc.MockNinVerifier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

class ExternalProviderWireMockTest {

    private final WireMockServer bureau = new WireMockServer(wireMockConfig().dynamicPort());
    private final WireMockServer identity = new WireMockServer(wireMockConfig().dynamicPort());
    private final WireMockServer notifications = new WireMockServer(wireMockConfig().dynamicPort());
    private final WireMockServer payments = new WireMockServer(wireMockConfig().dynamicPort());

    @AfterEach
    void stop() {
        stop(bureau);
        stop(identity);
        stop(notifications);
        stop(payments);
    }

    @Test
    void unconfiguredProvidersDoNotCallWireMock() {
        bureau.start();
        identity.start();
        notifications.start();
        payments.start();

        new UnconfiguredCreditBureau().enquire(new BureauSubject(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "CUS00000001",
                CustomerKind.INDIVIDUAL,
                "Ada Lovelace"));
        new MockBvnVerifier().verify("12345678901", "Ada Lovelace", LocalDate.of(1990, 1, 1));
        new MockNinVerifier().verify("12345678901", "Ada Lovelace", LocalDate.of(1990, 1, 1));

        assertThat(bureau.getAllServeEvents()).isEmpty();
        assertThat(identity.getAllServeEvents()).isEmpty();
        assertThat(notifications.getAllServeEvents()).isEmpty();
        assertThat(payments.getAllServeEvents()).isEmpty();
    }

    private static void stop(WireMockServer server) {
        if (server.isRunning()) {
            server.stop();
        }
    }
}
