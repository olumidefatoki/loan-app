package com.mfb.lending.module.complaint.application;

import java.time.Instant;
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
import com.mfb.lending.module.complaint.domain.ComplaintModels.Complaint;
import com.mfb.lending.module.complaint.domain.ComplaintModels.ComplaintActivity;
import com.mfb.lending.module.complaint.persistence.ComplaintStore;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import com.mfb.lending.module.notification.application.NotificationEvent;
import com.mfb.lending.module.notification.application.NotificationPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ComplaintService {

    private static final Set<String> CATEGORIES = Set.of("SERVICE", "LOAN", "REPAYMENT", "FEE", "STAFF", "PRIVACY", "OTHER");
    private static final Set<String> OPEN = Set.of("OPEN", "ASSIGNED", "IN_PROGRESS", "ESCALATED");

    private final ComplaintStore store;
    private final CustomerRepository customers;
    private final NotificationPublisher notifications;
    private final AuditService audit;
    private final PlatformOperationsProperties properties;
    private final java.time.Clock clock;

    public ComplaintService(
            ComplaintStore store,
            CustomerRepository customers,
            NotificationPublisher notifications,
            AuditService audit,
            PlatformOperationsProperties properties,
            java.time.Clock clock) {
        this.store = store;
        this.customers = customers;
        this.notifications = notifications;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public Complaint submit(AuthenticatedUser actor, String category, String subject, String description, ClientContext client) {
        CustomerRecord customer = own(actor);
        String selected = category(category);
        String title = required(subject, "subject", 200);
        String body = required(description, "description", 2000);
        Instant now = Instant.now(clock);
        Complaint complaint = store.insert(new Complaint(
                UUID.randomUUID(), store.nextReference(), customer.id(), customer.branchId(), selected, "OPEN", title, body,
                null, now.plus(properties.getComplaintSla()), null, null, null, now));
        activity(complaint.id(), "COMMENT", "Complaint opened", actor);
        notify(customer.id(), complaint, "OPEN");
        audit(actor, client, "COMPLAINT_OPENED", complaint.id(), java.util.Map.of("reference", complaint.reference(), "category", selected));
        return complaint;
    }

    public PageResponse<Complaint> list(AuthenticatedUser actor, String status, int page, int size) {
        PageBounds bounds = PageBounds.of(page, size);
        String selected = status(status);
        Scope scope = scope(actor);
        return new PageResponse<>(
                store.list(scope.customerId(), scope.branchIds(), scope.unrestricted(), selected, bounds.size(), bounds.offset()),
                bounds.page(), bounds.size(),
                store.count(scope.customerId(), scope.branchIds(), scope.unrestricted(), selected));
    }

    public ComplaintDetail get(AuthenticatedUser actor, UUID id) {
        Complaint complaint = requireVisible(actor, id);
        return new ComplaintDetail(complaint, store.activities(id));
    }

    @Transactional
    public ComplaintActivity addActivity(AuthenticatedUser actor, UUID id, String activityType, String note, UUID assigneeId, ClientContext client) {
        Complaint complaint = requireVisible(actor, id);
        String text = required(note, "note", 2000);
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            assertOpen(complaint);
            ComplaintActivity saved = activity(id, "CUSTOMER_RESPONSE", text, actor);
            audit(actor, client, "COMPLAINT_CUSTOMER_RESPONSE", id, java.util.Map.of("reference", complaint.reference()));
            return saved;
        }
        manage(actor);
        String type = activityType == null ? "COMMENT" : activityType.trim().toUpperCase(java.util.Locale.ROOT);
        return switch (type) {
            case "COMMENT" -> {
                assertOpen(complaint);
                yield activity(id, "COMMENT", text, actor);
            }
            case "ASSIGNMENT" -> assign(actor, complaint, assigneeId, text, client);
            case "ESCALATION" -> escalate(actor, complaint, text, client);
            case "RESOLUTION" -> resolve(actor, complaint, text, client);
            default -> throw ValidationException.field("activityType", "Unknown complaint activity");
        };
    }

    @Transactional
    public int escalateBreaches(Instant now) {
        int count = 0;
        for (Complaint complaint : store.breached(now)) {
            store.escalate(complaint.id(), now);
            store.insertActivity(new ComplaintActivity(
                    UUID.randomUUID(), complaint.id(), "ESCALATION", "SLA breached", null, "SYSTEM", now));
            notify(complaint.customerId(), complaint, "ESCALATED");
            count++;
        }
        return count;
    }

    public long breachedCount(Instant now) {
        return store.breachedCount(now);
    }

    private ComplaintActivity assign(AuthenticatedUser actor, Complaint complaint, UUID assigneeId, String note, ClientContext client) {
        assertOpen(complaint);
        if (assigneeId == null || !store.officerCanManage(assigneeId)) {
            throw ValidationException.field("assigneeId", "Assignee must be an active complaints officer");
        }
        store.assign(complaint.id(), assigneeId);
        ComplaintActivity saved = activity(complaint.id(), "ASSIGNMENT", note, actor);
        notify(complaint.customerId(), complaint, "ASSIGNED");
        audit(actor, client, "COMPLAINT_ASSIGNED", complaint.id(), java.util.Map.of("assigneeId", assigneeId.toString()));
        return saved;
    }

    private ComplaintActivity escalate(AuthenticatedUser actor, Complaint complaint, String note, ClientContext client) {
        assertOpen(complaint);
        store.escalate(complaint.id(), Instant.now(clock));
        ComplaintActivity saved = activity(complaint.id(), "ESCALATION", note, actor);
        notify(complaint.customerId(), complaint, "ESCALATED");
        audit(actor, client, "COMPLAINT_ESCALATED", complaint.id(), java.util.Map.of("reference", complaint.reference()));
        return saved;
    }

    private ComplaintActivity resolve(AuthenticatedUser actor, Complaint complaint, String note, ClientContext client) {
        assertOpen(complaint);
        store.resolve(complaint.id(), note, Instant.now(clock));
        ComplaintActivity saved = activity(complaint.id(), "RESOLUTION", note, actor);
        notify(complaint.customerId(), complaint, "RESOLVED");
        audit(actor, client, "COMPLAINT_RESOLVED", complaint.id(), java.util.Map.of("reference", complaint.reference()));
        return saved;
    }

    private void notify(UUID customerId, Complaint complaint, String status) {
        notifications.publish(NotificationEvent.of(
                "COMPLAINT_UPDATE", customerId, null, "complaint:" + complaint.id() + ":" + status,
                java.util.Map.of("reference", complaint.reference(), "status", status)));
    }

    private ComplaintActivity activity(UUID complaintId, String type, String note, AuthenticatedUser actor) {
        ComplaintActivity activity = new ComplaintActivity(
                UUID.randomUUID(), complaintId, type, note, actor.getId(), actor.getUsername(), Instant.now(clock));
        store.insertActivity(activity);
        return activity;
    }

    private Complaint requireVisible(AuthenticatedUser actor, UUID id) {
        Complaint complaint = store.find(id).orElseThrow(() -> new ResourceNotFoundException("Complaint"));
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            CustomerRecord customer = own(actor);
            if (!customer.id().equals(complaint.customerId())) {
                throw new ResourceNotFoundException("Complaint");
            }
            return complaint;
        }
        if (!actor.permissions().contains(PermissionCodes.COMPLAINT_VIEW) && !actor.permissions().contains(PermissionCodes.COMPLAINT_MANAGE)) {
            throw new ForbiddenException("You do not have permission to view complaints");
        }
        if (!actor.unrestrictedBranches() && complaint.branchId() != null && !actor.branchIds().contains(complaint.branchId())) {
            throw new ForbiddenException("You cannot access a complaint outside your branches");
        }
        return complaint;
    }

    private Scope scope(AuthenticatedUser actor) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            return new Scope(own(actor).id(), Set.of(), false);
        }
        if (!actor.permissions().contains(PermissionCodes.COMPLAINT_VIEW) && !actor.permissions().contains(PermissionCodes.COMPLAINT_MANAGE)) {
            throw new ForbiddenException("You do not have permission to view complaints");
        }
        if (actor.unrestrictedBranches()) {
            return new Scope(null, Set.of(), true);
        }
        return new Scope(null, actor.branchIds(), false);
    }

    private CustomerRecord own(AuthenticatedUser actor) {
        if (actor.getPrincipalType() != PrincipalType.CUSTOMER) {
            throw new ForbiddenException("This endpoint is for customer accounts");
        }
        return customers.findByUserId(actor.getId()).orElseThrow(() -> new ResourceNotFoundException("Customer"));
    }

    private static void manage(AuthenticatedUser actor) {
        if (actor.getPrincipalType() != PrincipalType.STAFF || !actor.permissions().contains(PermissionCodes.COMPLAINT_MANAGE)) {
            throw new ForbiddenException("You do not have permission to manage complaints");
        }
    }

    private static void assertOpen(Complaint complaint) {
        if (!OPEN.contains(complaint.status())) {
            throw new ConflictException("This complaint is already resolved");
        }
    }

    private static String category(String category) {
        if (category == null || !CATEGORIES.contains(category.trim().toUpperCase(java.util.Locale.ROOT))) {
            throw ValidationException.field("category", "Unknown complaint category");
        }
        return category.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static String status(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String selected = status.trim().toUpperCase(java.util.Locale.ROOT);
        if (!Set.of("OPEN", "ASSIGNED", "IN_PROGRESS", "ESCALATED", "RESOLVED", "CLOSED").contains(selected)) {
            throw ValidationException.field("status", "Unknown complaint status");
        }
        return selected;
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank()) {
            throw ValidationException.field(field, field + " is required");
        }
        String text = SensitivePayloads.narrative(value.trim());
        if (text.length() > max) {
            throw ValidationException.field(field, field + " is too long");
        }
        return text;
    }

    private void audit(AuthenticatedUser actor, ClientContext client, String action, UUID id, java.util.Map<String, Object> after) {
        audit.record(new AuditEntry(action, "COMPLAINT", "complaint", id.toString(), actor.getId(), AuditService.roleOf(actor),
                actor.getUsername(), "SUCCESS", client, null, after));
    }

    public record ComplaintDetail(Complaint complaint, List<ComplaintActivity> activities) {
    }

    private record Scope(UUID customerId, Set<UUID> branchIds, boolean unrestricted) {
    }
}
