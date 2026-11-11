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
                                Lending platform API. Customer, KYC, and staff authentication use one contract for React and Flutter clients: \
                                send the access token as Authorization: Bearer, and send the refresh token in the JSON body. \
                                There is no separate mobile flow. Optional header X-Client-Channel is WEB or MOBILE. \
                                Access tokens are short-lived. Refresh tokens rotate on every use, and reuse of a rotated token revokes the session family. \
                                Customer identity numbers are encrypted at rest and masked in responses. \
                                Loan products are versioned. A new version never rewrites a previous version, and a booked loan keeps the version it was opened on. \
                                Product activation uses maker-checker: the administrator who submitted a version cannot approve or activate it. \
                                Credit assessments keep the scoring model version, policy version, and calculation inputs that produced the decision. \
                                A credit score cannot override a compliance hold or a mandatory policy failure. Policy exceptions need a second officer. \
                                Credit bureau calls go through a provider port. This API does not publish a bureau vendor URL. \
                                Collateral is approved security only after ownership, documents, legal status, insurance, and encumbrance checks, and an approved valuation. \
                                Pledges cannot exceed the approved eligible value. Release needs a different authorised officer and a settled or discharged facility. \
                                Guarantor identity numbers are encrypted. A guarantor is not approved security until consent, verification, a guarantee agreement, and acceptance by a second officer are on record. \
                                Loan approval uses a versioned matrix. Amount, product, segment, branch, risk, collateral, security, and exception type select the stages. Approval limits are stored as delegations, and a user cannot approve a request they made, an application they originated, or an amount above that delegation. \
                                An approval decision is immutable. A later change to the application amount, revision, or credit result supersedes it. Automated approval runs only after credit assessment when the product, matrix, eligibility, and compliance gates all allow it. \
                                A loan offer can be generated only after final approval. Interest, instalments, fees, and the repayment schedule come from the loan calculation engine and the product version pinned to the application. A material change creates a new offer version and requires a fresh acceptance. The agreement PDF checksum is retained and checked on download. Customer acceptance is an electronic record of a verified KYC identity. It is not a qualified digital signature unless an approved signature provider is configured and legally approved. \
                                Core banking calls go through a provider-neutral adapter. The mock provider is the default and does not call a bank. The HTTP provider sends this platform's transport document only to paths supplied in configuration. A financial timeout remains UNKNOWN until enquiry resolves it, and that instruction is not retried. Webhooks use HMAC-SHA256 over the raw body and reject stale timestamps. \

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
