package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.UnauthorizedException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.config.CoreBankingProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class WebhookIngestionService {

    private final CoreBankingProperties properties;
    private final CoreBankingStore store;
    private final CoreRepaymentService repayments;
    private final CoreDisbursementService disbursements;
    private final UnitOfWork unitOfWork;
    private final ObjectMapper objectMapper;
    private final java.time.Clock clock;

    public WebhookIngestionService(
            CoreBankingProperties properties,
            CoreBankingStore store,
            CoreRepaymentService repayments,
            CoreDisbursementService disbursements,
            UnitOfWork unitOfWork,
            ObjectMapper objectMapper,
            java.time.Clock clock) {
        this.properties = properties;
        this.store = store;
        this.repayments = repayments;
        this.disbursements = disbursements;
        this.unitOfWork = unitOfWork;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    public WebhookResult accept(byte[] body, String messageId, String timestamp, String signature) {
        if (properties.getWebhook().getSecret() == null || properties.getWebhook().getSecret().isBlank()) {
            throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "Core banking webhook secret is not configured");
        }
        if (messageId == null || messageId.isBlank() || messageId.length() > 128) {
            throw ValidationException.field("messageId", "messageId is required");
        }
        if (!WebhookSignature.matches(properties.getWebhook().getSecret(), timestamp, body, signature)) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED, "Webhook signature is invalid");
        }
        Instant sent = parseTimestamp(timestamp);
        Duration skew = Duration.between(sent, Instant.now(clock)).abs();
        if (skew.compareTo(properties.getWebhook().getReplayWindow()) > 0) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED, "Webhook replay rejected");
        }
        WebhookPayload payload = read(body);
        if (!messageId.equals(payload.messageId())) {
            throw ValidationException.field("messageId", "messageId does not match the payload");
        }
        boolean stored = unitOfWork.required(() -> {
            boolean inserted = store.insertWebhook(UUID.randomUUID(), messageId, payload.eventType(), new String(body, java.nio.charset.StandardCharsets.UTF_8));
            if (!inserted) {
                return false;
            }
            store.insertInbox(messageId, payload.eventType(), new String(body, java.nio.charset.StandardCharsets.UTF_8));
            return true;
        });
        if (!stored) {
            return new WebhookResult(true, null);
        }
        CoreTransaction transaction = switch (payload.eventType()) {
            case "REPAYMENT_POSTED" -> repayments.acceptExternal(new RepaymentInstruction(
                    payload.applicationId(),
                    payload.idempotencyKey() == null ? messageId : payload.idempotencyKey(),
                    payload.amount(),
                    payload.providerReference(),
                    null,
                    "core-banking-webhook",
                    null));
            case "REPAYMENT_REVERSED" -> repayments.acceptReversal(payload.transactionReference());
            case "DISBURSEMENT_POSTED" -> disbursements.acceptNotification(payload.transactionReference(), payload.providerReference());
            default -> throw ValidationException.field("eventType", "Unsupported core banking event");
        };
        return new WebhookResult(false, transaction);
    }

    private WebhookPayload read(byte[] body) {
        try {
            WebhookPayload payload = objectMapper.readValue(body, WebhookPayload.class);
            if (payload.eventType() == null || payload.messageId() == null) {
                throw ValidationException.field("payload", "eventType and messageId are required");
            }
            return payload;
        } catch (ValidationException ex) {
            throw ex;
        } catch (Exception ex) {
            throw ValidationException.field("payload", "Webhook payload could not be read");
        }
    }

    private static Instant parseTimestamp(String timestamp) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(timestamp));
        } catch (Exception ex) {
            throw new UnauthorizedException(ErrorCode.UNAUTHORIZED, "Webhook timestamp is invalid");
        }
    }

    public record WebhookPayload(
            String messageId,
            String eventType,
            UUID applicationId,
            String transactionReference,
            String idempotencyKey,
            BigDecimal amount,
            String currencyCode,
            String providerReference
    ) {
    }

    public record WebhookResult(boolean duplicate, CoreTransaction transaction) {
    }
}
