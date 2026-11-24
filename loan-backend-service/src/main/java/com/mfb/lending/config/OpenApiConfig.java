package com.mfb.lending.config;

import com.mfb.lending.common.correlation.CorrelationIds;
import java.util.List;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.BooleanSchema;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.DateTimeSchema;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.ObjectSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
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
                                A loan account becomes active only after core banking confirms disbursement. Interest is recognised from the contractual schedule with BigDecimal. Daily pieces, including 29 February, add back to the contractual instalment interest. A repayment, settlement, or reversal changes the loan balance only after the core banking adapter confirms the cash movement. Repayments are idempotent. Posted journals cannot be edited or deleted; a correction is a reversal plus a replacement. Every posted journal balances by currency. Interest adjustments stay in draft until a different officer approves them. \
                                Collections delinquency uses the current approved repayment schedule and repayment allocations whose repayment status is POSTED. An instalment due on the as-of date is not past due. Portfolio at Risk divides the outstanding principal of included loans whose days past due meet the threshold by the outstanding principal of ACTIVE and OVERDUE loans with principal still outstanding. PAR 30, PAR 60, and PAR 90 use thresholds of 30, 60, and 90 days, and a loan in PAR 90 is also in PAR 60 and PAR 30. Pending activation, closed, and zero-principal loans are outside the denominator. Collection messages use approved templates and do not carry phone numbers, email addresses, or identity numbers. Restructuring needs a documented justification and a different authorised officer. Approval keeps the previous schedule, writes a new schedule version, and does not clear accrued interest, fees, penalties, or stored delinquency history. \
                                Management reports aggregate in SQL. Customer dashboards return only the authenticated customer's own loans, applications, repayments, and statement. Staff results stay inside assigned branches unless the role is unrestricted. Days past due for management PAR use the current schedule and POSTED allocations, and an instalment due on the as-of date is not past due. Disbursements, repayment collections, interest income, and fee income are posted journal totals. Reconciliation compares those journals with the loan subledger, interest accruals, fee charges, and confirmed recoveries. Write-off expense is general-ledger account 5100. An uncollectible collection closure is an operational count, not a journal. Large extracts stream to CSV, Excel, or PDF asynchronously, expire, and can be downloaded only by the requester with the export token. Report requests and downloads are audited. Identity numbers, phone numbers, email addresses, and account numbers are not selected. Customer names on staff extracts are masked. View access does not grant export access. \
                                Notifications use versioned templates for SMS, email, push, and in-app delivery. Phone numbers, email addresses, and verification codes are not stored on the notification. Failed deliveries retry with backoff and then become dead letters. Duplicate events are ignored. Customers can set channel preferences, except verification codes, which stay on. Push delivery needs a device token; without one the message is skipped. \
                                Audit records are append-only and sealed with a hash chain. Passwords, tokens, and raw BVN or NIN values are removed before they are written. \
                                Sanctions, PEP, and AML matches open a compliance case. A potential sanctions match is not cleared unless an officer has recorded an investigation note and a different reviewer decides. Screening a clear result does not close an open sanctions case. \
                                Complaints keep a response history and an SLA. A breach escalates the case and keeps the earlier notes. \
                                Sensitive system configuration, integration settings, and sensitive feature flags stay pending until a different administrator approves them. Secrets stay in the environment, not in configuration values. \

                                Errors use the same envelope: success=false, code, message, errors, correlationId, timestamp, and path.""")
                        .version("v1")
                        .contact(new Contact().name("MFB Platform Engineering")))
                .addServersItem(new Server().url("/").description("Current environment"))
                .components(new Components()
                        .addSchemas("ApiError", apiErrorSchema())
                        .addSchemas("FieldViolation", new ObjectSchema()
                                .addProperty("field", new StringSchema().description("Request field or header name"))
                                .addProperty("message", new StringSchema())
                                .required(List.of("field", "message")))
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
    public GroupedOpenApi platformV1Api(
            OperationCustomizer correlationIdHeaderCustomizer,
            OperationCustomizer errorEnvelopeCustomizer) {
        return GroupedOpenApi.builder()
                .group("v1")
                .pathsToMatch("/api/v1/**")
                .addOperationCustomizer(correlationIdHeaderCustomizer)
                .addOperationCustomizer(errorEnvelopeCustomizer)
                .build();
    }

    @Bean
    public OperationCustomizer correlationIdHeaderCustomizer() {
        return (operation, handlerMethod) -> {
            boolean present = operation.getParameters() != null && operation.getParameters().stream()
                    .anyMatch(parameter -> CorrelationIds.HEADER.equals(parameter.getName()));
            if (!present) {
                operation.addParametersItem(new Parameter()
                        .in("header")
                        .name(CorrelationIds.HEADER)
                        .description("Caller-supplied correlation id. A UUID is generated when the header is missing or unsafe.")
                        .required(false)
                        .schema(new StringSchema()));
            }
            return operation;
        };
    }

    @Bean
    public OperationCustomizer errorEnvelopeCustomizer() {
        Content error = new Content().addMediaType("application/json", new MediaType()
                .schema(new Schema<>().$ref("#/components/schemas/ApiError")));
        return (operation, handlerMethod) -> {
            ApiResponses responses = operation.getResponses() == null ? new ApiResponses() : operation.getResponses();
            addError(responses, error, "400", "The request is invalid. code is VALIDATION_ERROR, BAD_REQUEST, DOCUMENT_REJECTED, or MALWARE_DETECTED.");
            addError(responses, error, "401", "Authentication failed. code is UNAUTHORIZED, TOKEN_EXPIRED, INVALID_CREDENTIALS, OTP_INVALID, OTP_EXPIRED, OTP_REUSED, or MFA_INVALID.");
            addError(responses, error, "403", "The caller is authenticated but not allowed. code is FORBIDDEN, ACCOUNT_DISABLED, ACCOUNT_NOT_VERIFIED, PASSWORD_EXPIRED, or MFA_ENROLLMENT_REQUIRED.");
            addError(responses, error, "404", "The resource does not exist or is hidden from this caller. code is RESOURCE_NOT_FOUND.");
            addError(responses, error, "409", "The resource state conflicts. code is CONFLICT, DUPLICATE_CUSTOMER, COMPLIANCE_HOLD, TOKEN_REVOKED, or TOKEN_REUSE.");
            addError(responses, error, "423", "The account is temporarily locked. code is ACCOUNT_LOCKED.");
            addError(responses, error, "429", "The caller exceeded the attempt limit. code is RATE_LIMITED.");
            addError(responses, error, "500", "The request could not be completed. code is INTERNAL_ERROR or DATABASE_ERROR.");
            addError(responses, error, "503", "A required dependency is unavailable. code is SERVICE_UNAVAILABLE.");
            operation.setResponses(responses);
            return operation;
        };
    }

    private static void addError(ApiResponses responses, Content error, String status, String description) {
        if (responses.containsKey(status)) {
            return;
        }
        responses.addApiResponse(status, new ApiResponse().description(description).content(error));
    }

    private static Schema<?> apiErrorSchema() {
        Schema<?> violation = new ObjectSchema()
                .addProperty("field", new StringSchema())
                .addProperty("message", new StringSchema());
        return new ObjectSchema()
                .description("Error envelope. Success responses use a different shape: success, code, message, data, correlationId, and timestamp.")
                .addProperty("success", new BooleanSchema().example(false))
                .addProperty("code", new StringSchema().example("VALIDATION_ERROR"))
                .addProperty("message", new StringSchema().example("Request validation failed"))
                .addProperty("errors", new ArraySchema().items(violation))
                .addProperty("correlationId", new StringSchema().example("4f3c2a10-6b1e-4d2a-9c11-0a9b8c7d6e5f"))
                .addProperty("timestamp", new DateTimeSchema().example("2026-11-24T13:00:00Z"))
                .addProperty("path", new StringSchema().example("/api/v1/auth/login"))
                .required(List.of("success", "code", "message", "errors", "correlationId", "timestamp", "path"));
    }
}
