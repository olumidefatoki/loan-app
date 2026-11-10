package com.mfb.lending.config;

import com.mfb.lending.common.correlation.CorrelationIds;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI lendingPlatformOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("MFB Digital Lending Platform")
                        .description("Foundation API for the modular monolith lending platform.")
                        .version("v1")
                        .contact(new Contact().name("MFB Platform Engineering")))
                .addServersItem(new Server().url("/").description("Current environment"))
                .components(new Components().addSecuritySchemes("opsBasic", new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("basic")
                        .description("Operational account used for protected actuator endpoints.")));
    }

    @Bean
    public GroupedOpenApi platformV1Api() {
        return GroupedOpenApi.builder()
                .group("v1")
                .pathsToMatch("/api/v1/**")
                .build();
    }

    @Bean
    public OperationCustomizer correlationIdHeaderCustomizer() {
        return (operation, handlerMethod) -> operation.addParametersItem(new Parameter()
                .in("header")
                .name(CorrelationIds.HEADER)
                .description("Caller-supplied correlation id. A UUID is generated when the header is missing or unsafe.")
                .required(false)
                .schema(new StringSchema()));
    }
}
