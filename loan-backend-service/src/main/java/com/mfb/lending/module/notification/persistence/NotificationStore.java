package com.mfb.lending.module.notification.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.notification.domain.NotificationModels.DeliveryLog;
import com.mfb.lending.module.notification.domain.NotificationModels.MessageQuery;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationMessage;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationPreference;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationTemplate;

public interface NotificationStore {

    Optional<NotificationTemplate> activeTemplate(String eventType, String channel, String locale);

    boolean insertMessage(NotificationMessage message);

    void recoverStuck(Instant staleBefore);

    List<NotificationMessage> claimDue(Instant now, int limit);

    void markSent(UUID id, String providerCode, String providerMessageId, Instant sentAt);

    void markFailed(UUID id, int attemptCount, Instant nextAttemptAt, String error);

    void markDead(UUID id, int attemptCount, String error);

    void markSkipped(UUID id, String error);

    boolean markRead(UUID id, Instant readAt);

    void insertDelivery(DeliveryLog log);

    Optional<NotificationMessage> findMessage(UUID id);

    long countMessages(MessageQuery query);

    List<NotificationMessage> listMessages(MessageQuery query);

    List<NotificationPreference> preferences(UUID customerId);

    void replacePreferences(UUID customerId, List<NotificationPreference> preferences);

    NotificationTemplate insertTemplate(NotificationTemplate template);

    Optional<NotificationTemplate> findTemplate(UUID id);

    List<NotificationTemplate> listTemplates(int limit, int offset);

    long countTemplates();

    void deactivateTemplate(UUID id, String actor);

    long countByStatus(String status);
}
