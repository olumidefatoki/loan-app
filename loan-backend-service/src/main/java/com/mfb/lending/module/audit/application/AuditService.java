package com.mfb.lending.module.audit.application;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.correlation.CorrelationIds;
import com.mfb.lending.common.security.SensitivePayloads;
import com.mfb.lending.module.audit.persistence.AuditLogRepository;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
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
        write(new AuditEntry(action, moduleFor(entityType), entityType, entityId, null, null, actor, "SUCCESS", client, before, after));
    }

    public void record(AuditEntry entry) {
        write(entry);
    }

    public static String roleOf(AuthenticatedUser actor) {
        if (actor == null || actor.roles().isEmpty()) {
            return null;
        }
        return String.join(",", actor.roles());
    }

    private void write(AuditEntry entry) {
        repository.insert(
                CorrelationIds.current(),
                entry.actor(),
                entry.actorUserId(),
                entry.actorRole(),
                entry.action(),
                entry.module() == null ? moduleFor(entry.entityType()) : entry.module(),
                entry.entityType(),
                entry.entityId(),
                json(entry.before()),
                json(entry.after()),
                entry.client() == null ? null : entry.client().ipAddress(),
                entry.client() == null ? null : entry.client().userAgent(),
                entry.outcome() == null ? "SUCCESS" : entry.outcome());
    }

    public static String moduleFor(String entityType) {
        if (entityType == null || entityType.isBlank()) {
            return "SYSTEM";
        }
        return switch (entityType) {
            case "customer" -> "CUSTOMER";
            case "loan_application" -> "LOAN_APPLICATION";
            case "loan_approval" -> "APPROVAL";
            case "loan_offer" -> "LOAN_OFFER";
            case "loan_account" -> "LOAN_ACCOUNT";
            case "core_banking_transaction" -> "DISBURSEMENT";
            case "notification_template" -> "NOTIFICATION";
            case "system_configuration", "feature_flag" -> "SYSTEM_CONFIGURATION";
            case "branch" -> "BRANCH";
            case "business_calendar" -> "CALENDAR";
            case "compliance_case" -> "COMPLIANCE";
            case "complaint" -> "COMPLAINT";
            default -> entityType.toUpperCase(Locale.ROOT);
        };
    }

    private String json(Map<String, Object> value) {
        Map<String, Object> safe = SensitivePayloads.scrub(value);
        if (safe.isEmpty()) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(safe);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Audit payload could not be written", ex);
        }
    }

    public record AuditEntry(
            String action,
            String module,
            String entityType,
            String entityId,
            UUID actorUserId,
            String actorRole,
            String actor,
            String outcome,
            ClientContext client,
            Map<String, Object> before,
            Map<String, Object> after) {
    }
}
