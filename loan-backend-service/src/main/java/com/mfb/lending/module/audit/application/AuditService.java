package com.mfb.lending.module.audit.application;

import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.correlation.CorrelationIds;
import com.mfb.lending.module.audit.persistence.AuditLogRepository;
import com.mfb.lending.module.auth.domain.ClientContext;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditLogRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void record(String action, String entityType, String entityId, String actor, ClientContext client, Map<String, Object> after) {
        record(action, entityType, entityId, actor, client, null, after);
    }

    public void record(
            String action,
            String entityType,
            String entityId,
            String actor,
            ClientContext client,
            Map<String, Object> before,
            Map<String, Object> after) {
        repository.insert(
                CorrelationIds.current(),
                actor,
                action,
                entityType,
                entityId,
                json(before),
                json(after),
                client == null ? null : client.ipAddress(),
                client == null ? null : client.userAgent());
    }

    private String json(Map<String, Object> value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Audit payload could not be written", ex);
        }
    }
}
