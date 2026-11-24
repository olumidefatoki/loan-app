package com.mfb.lending.module.notification.application;

import java.util.UUID;

public record OutboundNotice(UUID notificationId, String channel, String destination, String subject, String body) {
}
