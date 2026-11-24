package com.mfb.lending.module.notification.application;

import java.util.Map;
import java.util.UUID;

public record NotificationEvent(
        String eventType,
        UUID customerId,
        UUID userId,
        String dedupeKey,
        Map<String, String> variables) {

    public NotificationEvent {
        variables = variables == null ? Map.of() : Map.copyOf(variables);
    }

    public static NotificationEvent of(
            String eventType, UUID customerId, UUID userId, String dedupeKey, Map<String, String> variables) {
        return new NotificationEvent(eventType, customerId, userId, dedupeKey, variables);
    }
}
