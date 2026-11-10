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
                        .description("""
                                Lending platform API. Customer and staff authentication use one contract for React and Flutter clients: \
                                send the access token as Authorization: Bearer, and send the refresh token in the JSON body. \
                                There is no separate mobile authentication flow. Access tokens are short-lived. \
                                Refresh tokens rotate on every use, and reuse of a rotated token revokes the session family. \
                                Errors use the same envelope: success=false, code, message, errors, correlationId, timestamp, and path.""")
                        .version("v1")
                        .contact(new Contact().name("MFB Platform Engineering")))
                .addServersItem(new Server().url("/").description("Current environment"))
                .components(new Components()
                        .addSecuritySchemes("bearerAuth", new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Short-lived access token issued by the customer or staff login endpoints."))
                        .addSecuritySchemes("opsBasic", new SecurityScheme()
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
