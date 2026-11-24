package com.mfb.lending.module.notification.domain;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

public final class NotificationModels {

    public static final Set<String> EVENTS = Set.of(
            "CUSTOMER_REGISTRATION", "OTP", "KYC_STATUS", "APPLICATION_SUBMISSION", "APPLICATION_APPROVAL",
            "APPLICATION_REJECTION", "ADDITIONAL_INFORMATION_REQUEST", "LOAN_OFFER", "LOAN_DISBURSEMENT",
            "UPCOMING_REPAYMENT", "OVERDUE_REPAYMENT", "PAYMENT_CONFIRMATION", "LOAN_SETTLEMENT", "COMPLAINT_UPDATE");
    public static final Set<String> CHANNELS = Set.of("SMS", "EMAIL", "PUSH", "IN_APP");

    private NotificationModels() {
    }

    public static boolean event(String value) {
        return EVENTS.contains(value);
    }

    public static boolean channel(String value) {
        return CHANNELS.contains(value);
    }

    public record NotificationTemplate(
            UUID id,
            String code,
            String channel,
            String eventType,
            String locale,
            String subject,
            String body,
            boolean active,
            int version,
            Instant createdAt,
            Instant updatedAt,
            String createdBy,
            String updatedBy) {
    }

    public record NotificationMessage(
            UUID id,
            UUID customerId,
            UUID userId,
            String eventType,
            String channel,
            String templateCode,
            String subject,
            String body,
            String payload,
            String status,
            String dedupeKey,
            int attemptCount,
            int maxAttempts,
            Instant nextAttemptAt,
            String providerCode,
            String providerMessageId,
            String lastError,
            String correlationId,
            Instant createdAt,
            Instant sentAt,
            Instant readAt) {
    }

    public record NotificationPreference(
            UUID id,
            UUID customerId,
            String channel,
            String eventType,
            boolean enabled,
            Instant updatedAt) {
    }

    public record DeliveryLog(
            UUID id,
            UUID notificationId,
            int attemptNumber,
            String providerCode,
            String status,
            String providerMessageId,
            String errorMessage,
            Instant attemptedAt) {
    }

    public record MessageQuery(UUID customerId, UUID userId, String status, int limit, int offset) {
    }

    public record Contact(String email, String phoneNumber, String pushToken) {
    }
}
