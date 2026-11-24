package com.mfb.lending.module.notification.application;

import java.util.UUID;

public record OtpNotice(UUID userId, UUID customerId, String channel, String destination, String code, String dedupeKey) {
}
