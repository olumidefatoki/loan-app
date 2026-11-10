package com.mfb.lending.module.customer.api;

import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CustomerApiConfiguration {

    @Bean
    public OperationCustomizer customerChannelHeader() {
        return (operation, handler) -> {
            if (!handler.getBeanType().getPackageName().startsWith("com.mfb.lending.module.customer")) {
                return operation;
            }
            operation.addParametersItem(new Parameter()
                    .in("header")
                    .name("X-Client-Channel")
                    .description("WEB or MOBILE. Web and mobile clients share this API. Defaults to WEB.")
                    .required(false)
                    .schema(new StringSchema()));
            return operation;
        };
    }
}
