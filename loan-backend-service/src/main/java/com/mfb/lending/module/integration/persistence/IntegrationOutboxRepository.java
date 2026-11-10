package com.mfb.lending.module.integration.persistence;

import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class IntegrationOutboxRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public IntegrationOutboxRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String aggregateType, String aggregateId, String eventType, String payloadJson, String destination) {
        jdbc.update(
                """
                INSERT INTO integration_outbox (id, aggregate_type, aggregate_id, event_type, payload, destination)
                VALUES (:id, :aggregateType, :aggregateId, :eventType, CAST(:payload AS JSONB), :destination)
                """,
                new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("aggregateType", aggregateType)
                        .addValue("aggregateId", aggregateId)
                        .addValue("eventType", eventType)
                        .addValue("payload", payloadJson)
                        .addValue("destination", destination));
    }
}
