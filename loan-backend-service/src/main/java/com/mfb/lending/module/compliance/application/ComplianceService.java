package com.mfb.lending.module.compliance.application;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.common.api.PageBounds;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.common.security.SensitivePayloads;
import com.mfb.lending.config.PlatformOperationsProperties;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.audit.application.AuditService.AuditEntry;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.compliance.domain.ComplianceModels.CaseActivity;
import com.mfb.lending.module.compliance.domain.ComplianceModels.ComplianceAlert;
import com.mfb.lending.module.compliance.domain.ComplianceModels.ComplianceCase;
import com.mfb.lending.module.compliance.persistence.ComplianceStore;
import com.mfb.lending.module.customer.application.ScreeningAlertSink;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComplianceService implements ScreeningAlertSink {

    private static final Set<String> TYPES = Set.of("AML", "SANCTIONS", "PEP", "RISK");
    private static final Set<String> RISKS = Set.of("UNASSESSED", "LOW", "MEDIUM", "HIGH", "UNACCEPTABLE");
    private static final Set<String> OPEN = Set.of("OPEN", "ASSIGNED", "IN_REVIEW", "ESCALATED");

    private final ComplianceStore store;
    private final AuditService audit;
    private final PlatformOperationsProperties properties;
    private final java.time.Clock clock;

    public ComplianceService(
            ComplianceStore store,
            AuditService audit,
            PlatformOperationsProperties properties,
            java.time.Clock clock) {
        this.store = store;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void record(UUID customerId, UUID screeningId, String screeningType, String outcome, String summary) {
        if (!"MATCH".equals(outcome) && !"REVIEW".equals(outcome)) {
            return;
        }
        if (!TYPES.contains(screeningType)) {
            return;
        }
        Instant now = Instant.now(clock);
        UUID alertId = UUID.randomUUID();
        String severity = "MATCH".equals(outcome) ? "HIGH" : "MEDIUM";
        String narrative = SensitivePayloads.narrative(summary);
        store.insertAlert(new ComplianceAlert(alertId, customerId, screeningId, screeningType, severity, "OPEN", narrative, null, now));
        store.insertCase(new ComplianceCase(
                UUID.randomUUID(), store.nextReference(), customerId, alertId, screeningType, "OPEN", null, null, narrative,
                null, null, null, null, null, null, null, now));
    }

    public PageResponse<ComplianceAlert> alerts(AuthenticatedUser actor, String status, String alertType, int page, int size) {
        view(actor);
        PageBounds bounds = PageBounds.of(page, size);
        String selectedStatus = token(status, "status");
        String selectedType = type(alertType, "alertType");
        return new PageResponse<>(store.listAlerts(selectedStatus, selectedType, bounds.size(), bounds.offset()),
                bounds.page(), bounds.size(), store.countAlerts(selectedStatus, selectedType));
    }

    public PageResponse<ComplianceCase> cases(AuthenticatedUser actor, String status, String caseType, int page, int size) {
        view(actor);
        PageBounds bounds = PageBounds.of(page, size);
        String selectedStatus = token(status, "status");
        String selectedType = type(caseType, "caseType");
        return new PageResponse<>(store.listCases(selectedStatus, selectedType, bounds.size(), bounds.offset()),
                bounds.page(), bounds.size(), store.countCases(selectedStatus, selectedType));
    }

    public CaseDetail get(AuthenticatedUser actor, UUID id) {
        view(actor);
        return new CaseDetail(require(id), store.activities(id));
    }

    @Transactional
    public ComplianceCase assign(AuthenticatedUser actor, UUID id, UUID assigneeId, ClientContext client) {
        manage(actor);
        ComplianceCase current = require(id);
        assertOpen(current);
        if (assigneeId == null || !store.officerCanWork(assigneeId)) {
            throw ValidationException.field("assigneeId", "Assignee must be an active compliance officer");
        }
        String status = "ESCALATED".equals(current.status()) ? "ESCALATED" : "ASSIGNED";
        store.assign(id, assigneeId, status);
        activity(id, "ASSIGNMENT", "Case assigned", actor);
        audit(actor, client, "COMPLIANCE_CASE_ASSIGNED", id, java.util.Map.of("assigneeId", assigneeId.toString()));
        return require(id);
    }

    @Transactional
    public CaseActivity note(AuthenticatedUser actor, UUID id, String note, ClientContext client) {
        manage(actor);
        ComplianceCase current = require(id);
        assertOpen(current);
        String text = requiredNote(note);
        CaseActivity saved = activity(id, "NOTE", text, actor);
        store.markInReview(id);
        audit(actor, client, "COMPLIANCE_NOTE_RECORDED", id, java.util.Map.of("activityId", saved.id().toString()));
        return saved;
    }

    @Transactional
    public ComplianceCase escalate(AuthenticatedUser actor, UUID id, String reason, ClientContext client) {
        manage(actor);
        ComplianceCase current = require(id);
        assertOpen(current);
        Instant now = Instant.now(clock);
        store.escalate(id, now);
        activity(id, "ESCALATION", requiredNote(reason), actor);
        audit(actor, client, "COMPLIANCE_CASE_ESCALATED", id, java.util.Map.of("reference", current.reference()));
        return require(id);
    }

    @Transactional
    public ComplianceCase resolve(AuthenticatedUser actor, UUID id, String decision, String reason, String riskClassification, ClientContext client) {
        decide(actor);
        ComplianceCase current = require(id);
        String selected = decision == null ? "" : decision.trim().toUpperCase(java.util.Locale.ROOT);
        if (!"CLEARED".equals(selected) && !"REJECTED".equals(selected)) {
            throw ValidationException.field("decision", "Decision must be CLEARED or REJECTED");
        }
        String text = requiredNote(reason);
        boolean hasNote = store.activities(id).stream().anyMatch(item -> "NOTE".equals(item.activityType()));
        assertDecision(current, actor.getId(), selected, hasNote);
        String risk = risk(riskClassification);
        Instant now = Instant.now(clock);
        Instant retained = now.atZone(ZoneOffset.UTC).plusYears(Math.max(properties.getEvidenceRetentionYears(), 1)).toInstant();
        store.resolve(new ComplianceCase(
                current.id(), current.reference(), current.customerId(), current.alertId(), current.caseType(), selected, risk,
                current.assigneeId(), current.summary(), selected, text, current.openedBy(), actor.getId(), now, current.escalatedAt(),
                retained, current.openedAt()));
        activity(id, "DECISION", selected + ": " + text, actor);
        store.updateAlertStatus(current.alertId(), selected);
        if (risk != null) {
            store.updateRisk(current.customerId(), risk, score(risk), actor.getUsername());
        }
        audit(actor, client, "COMPLIANCE_CASE_" + selected, id, java.util.Map.of(
                "reference", current.reference(), "caseType", current.caseType(), "riskClassification", risk == null ? "" : risk));
        return require(id);
    }

    public long openCases() {
        return store.openCases();
    }

    static void assertDecision(ComplianceCase current, UUID decider, String decision, boolean hasNote) {
        if (!OPEN.contains(current.status())) {
            throw new ConflictException("This compliance case is already decided");
        }
        if (current.openedBy() != null && current.openedBy().equals(decider)) {
            throw new ForbiddenException("A different officer must decide this case");
        }
        if (("SANCTIONS".equals(current.caseType()) || "PEP".equals(current.caseType()) || "AML".equals(current.caseType())) && !hasNote) {
            throw new ConflictException("SANCTIONS".equals(current.caseType())
                    ? "Potential sanctions matches cannot be cleared or rejected without an investigation note"
                    : "This case needs an investigation note before a decision");
        }
        if ("SANCTIONS".equals(current.caseType()) && "CLEARED".equals(decision) && !hasNote) {
            throw new ConflictException("Potential sanctions matches cannot be cleared without an investigation note");
        }
    }

    private CaseActivity activity(UUID caseId, String type, String note, AuthenticatedUser actor) {
        CaseActivity activity = new CaseActivity(UUID.randomUUID(), caseId, type, note, actor.getId(), actor.getUsername(), Instant.now(clock));
        store.insertActivity(activity);
        return activity;
    }

    private ComplianceCase require(UUID id) {
        return store.findCase(id).orElseThrow(() -> new ResourceNotFoundException("Compliance case"));
    }

    private static void assertOpen(ComplianceCase complianceCase) {
        if (!OPEN.contains(complianceCase.status())) {
            throw new ConflictException("This compliance case is already decided");
        }
    }

    private static String requiredNote(String note) {
        if (note == null || note.isBlank()) {
            throw ValidationException.field("reason", "A reason is required");
        }
        String text = SensitivePayloads.narrative(note);
        if (text.length() < 5) {
            throw ValidationException.field("reason", "Give a short reason for the review");
        }
        return text;
    }

    private static String type(String value, String field) {
        String token = token(value, field);
        if (token != null && !TYPES.contains(token)) {
            throw ValidationException.field(field, "Unknown compliance type");
        }
        return token;
    }

    private static String token(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String risk(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String selected = value.trim().toUpperCase(java.util.Locale.ROOT);
        if (!RISKS.contains(selected)) {
            throw ValidationException.field("riskClassification", "Unknown risk classification");
        }
        return selected;
    }

    private static int score(String risk) {
        return switch (risk) {
            case "LOW" -> 20;
            case "MEDIUM" -> 50;
            case "HIGH" -> 75;
            case "UNACCEPTABLE" -> 100;
            default -> 0;
        };
    }

    private static void view(AuthenticatedUser actor) {
        requireStaff(actor, PermissionCodes.COMPLIANCE_VIEW, "You do not have permission to view compliance cases");
    }

    private static void manage(AuthenticatedUser actor) {
        requireStaff(actor, PermissionCodes.COMPLIANCE_MANAGE, "You do not have permission to work compliance cases");
    }

    private static void decide(AuthenticatedUser actor) {
        requireStaff(actor, PermissionCodes.COMPLIANCE_DECIDE, "You do not have permission to decide a compliance case");
    }

    private static void requireStaff(AuthenticatedUser actor, String permission, String message) {
        if (actor.getPrincipalType() != PrincipalType.STAFF || !actor.permissions().contains(permission)) {
            throw new ForbiddenException(message);
        }
    }

    private void audit(AuthenticatedUser actor, ClientContext client, String action, UUID id, java.util.Map<String, Object> after) {
        audit.record(new AuditEntry(action, "COMPLIANCE", "compliance_case", id.toString(), actor.getId(), AuditService.roleOf(actor),
                actor.getUsername(), "SUCCESS", client, null, after));
    }

    public record CaseDetail(ComplianceCase complianceCase, List<CaseActivity> activities) {
    }
}
