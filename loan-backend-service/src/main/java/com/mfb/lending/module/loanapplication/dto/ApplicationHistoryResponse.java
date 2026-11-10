package com.mfb.lending.module.loanapplication.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;

public record ApplicationHistoryResponse(
        List<StatusChange> statuses,
        List<Event> events
) {

    public record StatusChange(
            UUID id,
            String fromStatus,
            String toStatus,
            String reason,
            UUID actorId,
            Instant occurredAt
    ) {
    }

    public record Event(
            UUID id,
            String eventType,
            JsonNode payload,
            UUID actorId,
            Instant occurredAt
    ) {
    }
}
