package com.mfb.lending.common.correlation;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CorrelationPropagationConfig {

    @Bean
    public CorrelationIdMessagePostProcessor correlationIdMessagePostProcessor() {
        return new CorrelationIdMessagePostProcessor();
    }

    @Bean
    public CorrelationIdListenerAdvice correlationIdListenerAdvice() {
        return new CorrelationIdListenerAdvice();
    }

    @Bean
    public RestClientCustomizer correlationRestClientCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            CorrelationPropagator.apply(request.getHeaders());
            return execution.execute(request, body);
        });
    }

    @Bean
    public RestTemplateCustomizer correlationRestTemplateCustomizer() {
        return restTemplate -> restTemplate.getInterceptors().add((request, body, execution) -> {
            CorrelationPropagator.apply(request.getHeaders());
            return execution.execute(request, body);
        });
    }
}
