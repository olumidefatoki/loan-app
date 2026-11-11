package com.mfb.lending.module.integration.corebanking;

import java.time.Duration;

import com.mfb.lending.config.CoreBankingProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class CoreBankingConfiguration {

    public CoreBankingConfiguration(CoreBankingProperties properties) {
        properties.validate();
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.core-banking", name = "provider", havingValue = "http")
    HttpCoreBankingClient httpCoreBankingClient(CoreBankingProperties properties, com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new HttpCoreBankingClient(properties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "app.core-banking", name = "provider", havingValue = "mock", matchIfMissing = true)
    MockCoreBankingClient mockCoreBankingClient(MockScenarioRegistry registry) {
        return new MockCoreBankingClient(registry);
    }

    @Bean
    IntegrationCircuitBreaker integrationCircuitBreaker(CoreBankingProperties properties) {
        return new IntegrationCircuitBreaker(
                properties.getCircuitBreaker().getFailureThreshold(),
                properties.getCircuitBreaker().getOpenDuration());
    }

    @Bean
    SafeRetry coreBankingRetry(CoreBankingProperties properties) {
        return new SafeRetry(
                properties.getRetry().getMaxAttempts(),
                properties.getRetry().getInitialBackoff(),
                properties.getRetry().getMaxBackoff(),
                properties.getRetry().getMultiplier(),
                CoreBankingConfiguration::sleep);
    }

    @Bean
    CoreBankingMetrics coreBankingMetrics(MeterRegistry registry) {
        return new CoreBankingMetrics(registry);
    }

    @Bean
    CoreBankingAdapter coreBankingAdapter(
            CoreBankingClient client,
            CoreBankingMapper mapper,
            CoreBankingStore store,
            IntegrationCircuitBreaker circuit,
            SafeRetry retry,
            CoreBankingMetrics metrics,
            java.time.Clock clock) {
        return new DefaultCoreBankingAdapter(client, mapper, store, circuit, retry, metrics, clock);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CoreTransportException(CoreTransportException.Failure.UNAVAILABLE, "Core banking retry was interrupted");
        }
    }
}
