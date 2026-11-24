package com.mfb.lending.module.notification.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.SecurityProfile;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.domain.CustomerCategory;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.CustomerStatus;
import com.mfb.lending.module.customer.domain.KycStatus;
import com.mfb.lending.module.customer.domain.RiskClassification;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import com.mfb.lending.module.notification.domain.NotificationModels.Contact;
import com.mfb.lending.module.notification.domain.NotificationModels.DeliveryLog;
import com.mfb.lending.module.notification.domain.NotificationModels.MessageQuery;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationMessage;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationPreference;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationTemplate;
import com.mfb.lending.module.notification.persistence.NotificationStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NotificationDeliveryTest {

    private final MutableClock clock = new MutableClock(Instant.parse("2026-06-01T09:00:00Z"));
    private final MemoryNotifications store = new MemoryNotifications();
    private final ScriptedProvider sms = new ScriptedProvider("SMS", "mock-sms", true);
    private final ScriptedProvider push = new ScriptedProvider("PUSH", "mock-push", false);
    private final NotificationEngine engine = new NotificationEngine(
            store,
            channel -> "PUSH".equals(channel) ? push : sms,
            customerId -> Optional.of(new Contact("ada@mfb.test", "08030000000", null)),
            new RetryPolicy(3, Duration.ofMinutes(1), Duration.ofHours(1), 2),
            new ObjectMapper(),
            clock,
            20);

    @Test
    void aFailedDeliveryRetriesThenBecomesADeadLetterAndADuplicateIsNotSentAgain() {
        store.template("PAYMENT_CONFIRMATION", "SMS", "Payment {{reference}}");
        UUID customerId = UUID.randomUUID();
        NotificationEvent event = NotificationEvent.of(
                "PAYMENT_CONFIRMATION", customerId, null, "payment:1",
                Map.of("reference", "PAY1", "bvn", "12345678901", "password", "secret"));

        assertThat(engine.publish(event)).isEqualTo(new PublishResult(1, 0));
        assertThat(engine.publish(event)).isEqualTo(new PublishResult(0, 1));
        assertThat(store.messages).hasSize(1);
        assertThat(store.messages.values().iterator().next().payload()).doesNotContain("12345678901").doesNotContain("secret");
        assertThat(store.messages.values().iterator().next().body()).isEqualTo("Payment PAY1");

        assertThat(engine.dispatchDue()).isEqualTo(1);
        assertThat(engine.dispatchDue()).isZero();
        clock.plus(Duration.ofMinutes(1));
        assertThat(engine.dispatchDue()).isEqualTo(1);
        clock.plus(Duration.ofMinutes(2));
        assertThat(engine.dispatchDue()).isEqualTo(1);
        assertThat(engine.dispatchDue()).isZero();

        NotificationMessage message = store.messages.values().iterator().next();
        assertThat(message.status()).isEqualTo("DEAD");
        assertThat(message.attemptCount()).isEqualTo(3);
        assertThat(message.lastError()).doesNotContain("08030000000");
        assertThat(sms.calls).isEqualTo(3);
        assertThat(store.logs).extracting(DeliveryLog::status).containsExactly("FAILED", "FAILED", "DEAD");
    }

    @Test
    void anOtpFailureIsDeadImmediatelyAndTheCodeIsNotStoredOrSentTwice() {
        PublishResult ignored = engine.publish(NotificationEvent.of("OTP", null, UUID.randomUUID(), "unused", Map.of()));
        assertThat(ignored.created()).isZero();
        OtpNotice notice = new OtpNotice(UUID.randomUUID(), null, "SMS", "08031111111", "654321", "otp:once");
        engine.publishOtp(notice);
        engine.publishOtp(notice);

        NotificationMessage message = store.messages.values().iterator().next();
        assertThat(message.status()).isEqualTo("DEAD");
        assertThat(message.body()).doesNotContain("654321");
        assertThat(message.payload()).doesNotContain("654321");
        assertThat(store.logs).extracting(DeliveryLog::errorMessage).allSatisfy(error -> assertThat(error).doesNotContain("654321"));
        assertThat(sms.calls).isEqualTo(1);
        assertThat(PreferencePolicy.allows(List.of(preference("SMS", "OTP", false)), "SMS", "OTP")).isTrue();
        assertThat(PreferencePolicy.allows(List.of(preference("SMS", "*", false)), "SMS", "PAYMENT_CONFIRMATION")).isFalse();
    }

    @Test
    void pushWithoutADeviceTokenIsSkippedAndVerificationPreferencesCannotBeDisabled() {
        store.template("LOAN_OFFER", "PUSH", "Offer {{reference}}");
        UUID customerId = UUID.randomUUID();
        store.preferences.put(customerId, List.of(preference(customerId, "PUSH", "LOAN_OFFER", true)));
        engine.publish(NotificationEvent.of("LOAN_OFFER", customerId, null, "offer:1", Map.of("reference", "OFF1")));
        assertThat(engine.dispatchDue()).isEqualTo(1);

        NotificationMessage message = store.messages.values().iterator().next();
        assertThat(message.status()).isEqualTo("SKIPPED");
        assertThat(message.lastError()).isEqualTo("No device token");
        assertThat(push.calls).isEqualTo(1);

        CustomerRepository customers = mock(CustomerRepository.class);
        UUID userId = UUID.randomUUID();
        when(customers.findByUserId(userId)).thenReturn(Optional.of(customer(customerId, userId)));
        NotificationDirectory directory = new NotificationDirectory(store, engine, customers, mock(com.mfb.lending.module.audit.application.AuditService.class), clock);
        AuthenticatedUser customer = user(userId, PrincipalType.CUSTOMER, List.of());
        assertThatThrownBy(() -> directory.replacePreferences(customer, List.of(new NotificationDirectory.PreferenceChange("SMS", "OTP", false))))
                .isInstanceOf(ValidationException.class);
        AuthenticatedUser staff = user(UUID.randomUUID(), PrincipalType.STAFF, List.of());
        assertThatThrownBy(() -> directory.list(staff, null, 0, 20)).isInstanceOf(ForbiddenException.class);
        assertThat(directory.list(user(UUID.randomUUID(), PrincipalType.STAFF, List.of(PermissionCodes.NOTIFICATION_VIEW)), null, 0, 20).total())
                .isEqualTo(1);
    }

    private static NotificationPreference preference(String channel, String eventType, boolean enabled) {
        return preference(UUID.randomUUID(), channel, eventType, enabled);
    }

    private static NotificationPreference preference(UUID customerId, String channel, String eventType, boolean enabled) {
        return new NotificationPreference(UUID.randomUUID(), customerId, channel, eventType, enabled, Instant.EPOCH);
    }

    private static CustomerRecord customer(UUID id, UUID userId) {
        return new CustomerRecord(id, "CU000000000001", CustomerKind.INDIVIDUAL, CustomerStatus.ACTIVE, CustomerCategory.RETAIL,
                "Ada", null, null, userId, null, KycStatus.VERIFIED, RiskClassification.LOW, false, null, false, null,
                Instant.EPOCH, Instant.EPOCH, "test", "test");
    }

    private static AuthenticatedUser user(UUID id, PrincipalType type, List<String> permissions) {
        UserRecord record = new UserRecord(id, type, null, "Ada", null, "ada@mfb.test", null, "hash", UserStatus.ACTIVE,
                true, true, 0, null, Instant.EPOCH, null, false, null, null, Instant.EPOCH, Instant.EPOCH, "test", "test");
        return AuthenticatedUser.from(record, UUID.randomUUID(), new SecurityProfile(List.of("STAFF"), permissions, List.of(), false, true));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        private void plus(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class ScriptedProvider implements NotificationProvider {
        private final String channel;
        private final String code;
        private final boolean fail;
        private int calls;

        private ScriptedProvider(String channel, String code, boolean fail) {
            this.channel = channel;
            this.code = code;
            this.fail = fail;
        }

        @Override
        public String channel() {
            return channel;
        }

        @Override
        public String code() {
            return code;
        }

        @Override
        public DeliveryResult send(OutboundNotice notice) {
            calls++;
            if (notice.destination() == null || notice.destination().isBlank()) {
                return DeliveryResult.skipped("PUSH".equals(channel) ? "No device token" : "No destination");
            }
            if (fail) {
                return DeliveryResult.failed("gateway timeout for 08030000000");
            }
            return DeliveryResult.accepted(code);
        }
    }

    private static final class MemoryNotifications implements NotificationStore {
        private final Map<String, NotificationTemplate> templates = new LinkedHashMap<>();
        private final Map<UUID, NotificationMessage> messages = new LinkedHashMap<>();
        private final Map<UUID, List<NotificationPreference>> preferences = new LinkedHashMap<>();
        private final List<DeliveryLog> logs = new ArrayList<>();

        private void template(String eventType, String channel, String body) {
            templates.put(eventType + "|" + channel, new NotificationTemplate(
                    UUID.randomUUID(), eventType + "_" + channel, channel, eventType, "en", null, body, true, 1,
                    Instant.EPOCH, Instant.EPOCH, "test", "test"));
        }

        @Override
        public Optional<NotificationTemplate> activeTemplate(String eventType, String channel, String locale) {
            return Optional.ofNullable(templates.get(eventType + "|" + channel));
        }

        @Override
        public boolean insertMessage(NotificationMessage message) {
            boolean duplicate = messages.values().stream()
                    .anyMatch(existing -> existing.dedupeKey().equals(message.dedupeKey()) && existing.channel().equals(message.channel()));
            if (duplicate) {
                return false;
            }
            messages.put(message.id(), message);
            return true;
        }

        @Override
        public void recoverStuck(Instant staleBefore) {
            messages.replaceAll((id, message) -> "DISPATCHING".equals(message.status()) && message.nextAttemptAt() != null
                    && message.nextAttemptAt().isBefore(staleBefore) ? copy(message, "FAILED", message.attemptCount(), message.nextAttemptAt(), message.lastError()) : message);
        }

        @Override
        public List<NotificationMessage> claimDue(Instant now, int limit) {
            List<NotificationMessage> due = new ArrayList<>();
            for (NotificationMessage message : messages.values()) {
                if (due.size() == limit) {
                    break;
                }
                if (("PENDING".equals(message.status()) || "FAILED".equals(message.status()))
                        && message.nextAttemptAt() != null && !message.nextAttemptAt().isAfter(now)) {
                    NotificationMessage claimed = copy(message, "DISPATCHING", message.attemptCount(), now, message.lastError());
                    messages.put(message.id(), claimed);
                    due.add(claimed);
                }
            }
            return due;
        }

        @Override
        public void markSent(UUID id, String providerCode, String providerMessageId, Instant sentAt) {
            messages.put(id, copy(messages.get(id), "SENT", messages.get(id).attemptCount(), null, null));
        }

        @Override
        public void markFailed(UUID id, int attemptCount, Instant nextAttemptAt, String error) {
            messages.put(id, copy(messages.get(id), "FAILED", attemptCount, nextAttemptAt, error));
        }

        @Override
        public void markDead(UUID id, int attemptCount, String error) {
            messages.put(id, copy(messages.get(id), "DEAD", attemptCount, null, error));
        }

        @Override
        public void markSkipped(UUID id, String error) {
            messages.put(id, copy(messages.get(id), "SKIPPED", messages.get(id).attemptCount() + 1, null, error));
        }

        @Override
        public boolean markRead(UUID id, Instant readAt) {
            return false;
        }

        @Override
        public void insertDelivery(DeliveryLog log) {
            logs.add(log);
        }

        @Override
        public Optional<NotificationMessage> findMessage(UUID id) {
            return Optional.ofNullable(messages.get(id));
        }

        @Override
        public long countMessages(MessageQuery query) {
            return messages.size();
        }

        @Override
        public List<NotificationMessage> listMessages(MessageQuery query) {
            return List.copyOf(messages.values());
        }

        @Override
        public List<NotificationPreference> preferences(UUID customerId) {
            return preferences.getOrDefault(customerId, List.of());
        }

        @Override
        public void replacePreferences(UUID customerId, List<NotificationPreference> rows) {
            preferences.put(customerId, List.copyOf(rows));
        }

        @Override
        public NotificationTemplate insertTemplate(NotificationTemplate template) {
            return template;
        }

        @Override
        public Optional<NotificationTemplate> findTemplate(UUID id) {
            return Optional.empty();
        }

        @Override
        public List<NotificationTemplate> listTemplates(int limit, int offset) {
            return List.of();
        }

        @Override
        public long countTemplates() {
            return templates.size();
        }

        @Override
        public void deactivateTemplate(UUID id, String actor) {
        }

        @Override
        public long countByStatus(String status) {
            return messages.values().stream().filter(message -> message.status().equals(status)).count();
        }

        private static NotificationMessage copy(NotificationMessage message, String status, int attempts, Instant nextAttemptAt, String error) {
            return new NotificationMessage(
                    message.id(), message.customerId(), message.userId(), message.eventType(), message.channel(), message.templateCode(),
                    message.subject(), message.body(), message.payload(), status, message.dedupeKey(), attempts, message.maxAttempts(),
                    nextAttemptAt, message.providerCode(), message.providerMessageId(), error, message.correlationId(), message.createdAt(),
                    message.sentAt(), message.readAt());
        }
    }
}
