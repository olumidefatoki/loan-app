package com.mfb.lending.module.notification.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.correlation.CorrelationIds;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.common.security.SensitivePayloads;
import com.mfb.lending.config.PlatformOperationsProperties;
import com.mfb.lending.module.notification.domain.NotificationModels.Contact;
import com.mfb.lending.module.notification.domain.NotificationModels.DeliveryLog;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationMessage;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationPreference;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationTemplate;
import com.mfb.lending.module.notification.persistence.NotificationStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class NotificationEngine implements NotificationPublisher {

    private final NotificationStore store;
    private final NotificationProviders providers;
    private final ContactDirectory contacts;
    private final RetryPolicy retry;
    private final ObjectMapper objectMapper;
    private final java.time.Clock clock;
    private final int batch;

    @Autowired
    public NotificationEngine(
            NotificationStore store,
            NotificationProviders providers,
            ContactDirectory contacts,
            PlatformOperationsProperties properties,
            ObjectMapper objectMapper,
            java.time.Clock clock) {
        this(store, providers, contacts, RetryPolicy.from(properties), objectMapper, clock, properties.getNotificationBatch());
    }

    NotificationEngine(
            NotificationStore store,
            NotificationProviders providers,
            ContactDirectory contacts,
            RetryPolicy retry,
            ObjectMapper objectMapper,
            java.time.Clock clock,
            int batch) {
        this.store = store;
        this.providers = providers;
        this.contacts = contacts;
        this.retry = retry;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.batch = batch;
    }

    @Override
    public PublishResult publish(NotificationEvent event) {
        assertEvent(event.eventType());
        if (event.dedupeKey() == null || event.dedupeKey().isBlank()) {
            throw ValidationException.field("dedupeKey", "dedupeKey is required");
        }
        List<NotificationPreference> preferences = event.customerId() == null ? List.of() : store.preferences(event.customerId());
        int created = 0;
        int duplicates = 0;
        for (String channel : List.of("SMS", "EMAIL", "PUSH", "IN_APP")) {
            if (!PreferencePolicy.allows(preferences, channel, event.eventType())) {
                continue;
            }
            Optional<NotificationTemplate> template = store.activeTemplate(event.eventType(), channel, "en");
            if (template.isEmpty()) {
                continue;
            }
            if (insert(event, channel, template.get(), false)) {
                created++;
            } else {
                duplicates++;
            }
        }
        return new PublishResult(created, duplicates);
    }

    @Override
    public void publishOtp(OtpNotice notice) {
        if (notice.code() == null || notice.code().isBlank()) {
            throw ValidationException.field("code", "code is required");
        }
        if ("IN_APP".equals(notice.channel()) || "APP".equals(notice.channel())) {
            storeInAppOtp(notice);
            return;
        }
        String channel = "EMAIL".equals(notice.channel()) ? "EMAIL" : "SMS";
        NotificationTemplate template = store.activeTemplate("OTP", channel, "en").orElse(null);
        String storedBody = template == null
                ? "A verification code was sent. It expires shortly."
                : TemplateRenderer.render(template.body(), java.util.Map.of());
        Instant now = Instant.now(clock);
        NotificationMessage message = message(
                notice.customerId(),
                notice.userId(),
                "OTP",
                channel,
                template == null ? "OTP_" + channel : template.code(),
                "Verification code",
                storedBody,
                "{}",
                "PENDING",
                notice.dedupeKey(),
                now);
        if (!store.insertMessage(message)) {
            return;
        }
        DeliveryResult result = providers.provider(channel).send(new OutboundNotice(
                message.id(),
                channel,
                notice.destination(),
                "Verification code",
                "Your verification code is " + notice.code() + ". It expires shortly."));
        int attempt = 1;
        if (result.kind() == DeliveryResult.Kind.ACCEPTED) {
            store.markSent(message.id(), providers.provider(channel).code(), result.providerMessageId(), now);
            log(message.id(), attempt, providers.provider(channel).code(), "ACCEPTED", result.providerMessageId(), null, now);
            return;
        }
        store.markDead(message.id(), retry.maxAttempts(), SensitivePayloads.safeError(result.error()));
        log(message.id(), attempt, providers.provider(channel).code(), "DEAD", null, result.error(), now);
    }

    private void storeInAppOtp(OtpNotice notice) {
        Instant now = Instant.now(clock);
        NotificationTemplate template = store.activeTemplate("OTP", "IN_APP", "en").orElse(null);
        String body = template == null
                ? "A verification code was sent. It expires shortly."
                : TemplateRenderer.render(template.body(), java.util.Map.of());
        NotificationMessage message = message(
                notice.customerId(), notice.userId(), "OTP", "IN_APP",
                template == null ? "OTP_IN_APP" : template.code(), "Verification code", body, "{}", "SENT", notice.dedupeKey(), now);
        if (store.insertMessage(message)) {
            log(message.id(), 1, "in-app", "ACCEPTED", "in-app", null, now);
        }
    }

    public int dispatchDue() {
        Instant now = Instant.now(clock);
        store.recoverStuck(now.minus(Duration.ofMinutes(5)));
        List<NotificationMessage> claimed = store.claimDue(now, batch);
        for (NotificationMessage message : claimed) {
            deliver(message);
        }
        return claimed.size();
    }

    private boolean insert(NotificationEvent event, String channel, NotificationTemplate template, boolean synchronous) {
        Instant now = Instant.now(clock);
        boolean inApp = "IN_APP".equals(channel);
        java.util.Map<String, String> safe = SensitivePayloads.strings(event.variables());
        NotificationMessage message = message(
                event.customerId(),
                event.userId(),
                event.eventType(),
                channel,
                template.code(),
                TemplateRenderer.render(template.subject(), safe),
                TemplateRenderer.render(template.body(), safe),
                json(safe),
                inApp ? "SENT" : "PENDING",
                event.dedupeKey(),
                now);
        if (!inApp) {
            message = new NotificationMessage(
                    message.id(), message.customerId(), message.userId(), message.eventType(), message.channel(),
                    message.templateCode(), message.subject(), message.body(), message.payload(), "PENDING",
                    message.dedupeKey(), 0, retry.maxAttempts(), now, null, null, null, message.correlationId(), now, null, null);
        }
        if (!store.insertMessage(message)) {
            return false;
        }
        if (inApp) {
            log(message.id(), 1, "in-app", "ACCEPTED", "in-app", null, now);
        }
        if (synchronous) {
            deliver(message);
        }
        return true;
    }

    private void deliver(NotificationMessage message) {
        Instant now = Instant.now(clock);
        Contact contact = message.customerId() == null ? null : contacts.find(message.customerId()).orElse(null);
        String destination = switch (message.channel()) {
            case "SMS" -> contact == null ? null : contact.phoneNumber();
            case "EMAIL" -> contact == null ? null : contact.email();
            case "PUSH" -> contact == null ? null : contact.pushToken();
            default -> null;
        };
        NotificationProvider provider = providers.provider(message.channel());
        DeliveryResult result = provider.send(new OutboundNotice(
                message.id(), message.channel(), destination, message.subject(), message.body()));
        int attempt = message.attemptCount() + 1;
        if (result.kind() == DeliveryResult.Kind.ACCEPTED) {
            store.markSent(message.id(), provider.code(), result.providerMessageId(), now);
            log(message.id(), attempt, provider.code(), "ACCEPTED", result.providerMessageId(), null, now);
            return;
        }
        if (result.kind() == DeliveryResult.Kind.SKIPPED) {
            store.markSkipped(message.id(), SensitivePayloads.safeError(result.error()));
            log(message.id(), attempt, provider.code(), "SKIPPED", null, result.error(), now);
            return;
        }
        if (retry.exhausted(attempt)) {
            store.markDead(message.id(), attempt, SensitivePayloads.safeError(result.error()));
            log(message.id(), attempt, provider.code(), "DEAD", null, result.error(), now);
            return;
        }
        store.markFailed(message.id(), attempt, retry.next(attempt, now), SensitivePayloads.safeError(result.error()));
        log(message.id(), attempt, provider.code(), "FAILED", null, result.error(), now);
    }

    private NotificationMessage message(
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
            Instant now) {
        boolean sent = "SENT".equals(status);
        return new NotificationMessage(
                UUID.randomUUID(), customerId, userId, eventType, channel, templateCode, blank(subject), body, payload, status,
                dedupeKey, 0, retry.maxAttempts(), sent ? null : now, sent ? "in-app" : null, sent ? "in-app" : null, null,
                CorrelationIds.current(), now, sent ? now : null, null);
    }

    private void log(UUID notificationId, int attempt, String provider, String status, String providerMessageId, String error, Instant at) {
        store.insertDelivery(new DeliveryLog(
                UUID.randomUUID(), notificationId, Math.max(attempt, 1), provider, status, providerMessageId,
                error == null ? null : SensitivePayloads.safeError(error), at));
    }

    private String json(java.util.Map<String, String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Notification payload could not be written", ex);
        }
    }

    private static void assertEvent(String eventType) {
        if (!com.mfb.lending.module.notification.domain.NotificationModels.event(eventType)) {
            throw ValidationException.field("eventType", "Unknown notification event");
        }
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public NotificationMessage require(UUID id) {
        return store.findMessage(id).orElseThrow(() -> new ResourceNotFoundException("Notification"));
    }

    public void assertReadable(NotificationMessage message, UUID customerId, UUID userId, boolean staff) {
        if (staff) {
            return;
        }
        boolean owns = customerId != null && customerId.equals(message.customerId())
                || userId != null && userId.equals(message.userId());
        if (!owns) {
            throw new ResourceNotFoundException("Notification");
        }
    }

    public void markRead(NotificationMessage message) {
        if (!store.markRead(message.id(), Instant.now(clock))) {
            throw new ConflictException("This notification cannot be marked as read");
        }
    }
}
