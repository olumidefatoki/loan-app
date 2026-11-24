package com.mfb.lending.module.notification.application;

import java.util.List;

import com.mfb.lending.module.notification.domain.NotificationModels.NotificationPreference;

final class PreferencePolicy {

    private PreferencePolicy() {
    }

    static boolean allows(List<NotificationPreference> preferences, String channel, String eventType) {
        if ("OTP".equals(eventType)) {
            return true;
        }
        NotificationPreference exact = find(preferences, channel, eventType);
        if (exact != null) {
            return exact.enabled();
        }
        NotificationPreference wildcard = find(preferences, channel, "*");
        if (wildcard != null) {
            return wildcard.enabled();
        }
        return !"PUSH".equals(channel);
    }

    private static NotificationPreference find(List<NotificationPreference> preferences, String channel, String eventType) {
        for (NotificationPreference preference : preferences) {
            if (channel.equals(preference.channel()) && eventType.equals(preference.eventType())) {
                return preference;
            }
        }
        return null;
    }
}
