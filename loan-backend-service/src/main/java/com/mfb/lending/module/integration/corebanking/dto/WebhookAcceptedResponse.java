package com.mfb.lending.module.integration.corebanking.dto;

import java.util.UUID;

public record WebhookAcceptedResponse(boolean duplicate, UUID transactionId, String state) {
}
