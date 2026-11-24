package com.mfb.lending.module.notification.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.mfb.lending.common.api.PageBounds;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.audit.application.AuditService.AuditEntry;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import com.mfb.lending.module.notification.domain.NotificationModels;
import com.mfb.lending.module.notification.domain.NotificationModels.MessageQuery;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationMessage;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationPreference;
import com.mfb.lending.module.notification.domain.NotificationModels.NotificationTemplate;
import com.mfb.lending.module.notification.persistence.NotificationStore;
import org.springframework.stereotype.Service;

@Service
public class NotificationDirectory {

    private final NotificationStore store;
    private final NotificationEngine engine;
    private final CustomerRepository customers;
    private final AuditService audit;
    private final java.time.Clock clock;

    public NotificationDirectory(
            NotificationStore store,
            NotificationEngine engine,
            CustomerRepository customers,
            AuditService audit,
            java.time.Clock clock) {
        this.store = store;
        this.engine = engine;
        this.customers = customers;
        this.audit = audit;
        this.clock = clock;
    }

    public PageResponse<NotificationMessage> list(AuthenticatedUser actor, String status, int page, int size) {
        PageBounds bounds = PageBounds.of(page, size);
        String selected = status(status);
        MessageQuery query = scope(actor, selected, bounds);
        return new PageResponse<>(store.listMessages(query), bounds.page(), bounds.size(), store.countMessages(query));
    }

    public NotificationMessage get(AuthenticatedUser actor, UUID id) {
        NotificationMessage message = engine.require(id);
        if (staff(actor)) {
            if (!actor.permissions().contains(PermissionCodes.NOTIFICATION_VIEW)) {
                throw new ForbiddenException("You do not have permission to view notifications");
            }
            return message;
        }
        engine.assertReadable(message, requireCustomer(actor), actor.getId(), false);
        return message;
    }

    public NotificationMessage read(AuthenticatedUser actor, UUID id) {
        NotificationMessage message = get(actor, id);
        if (staff(actor)) {
            throw new ForbiddenException("Only the customer can mark a notification as read");
        }
        engine.markRead(message);
        return engine.require(id);
    }

    public List<NotificationPreference> preferences(AuthenticatedUser actor) {
        return store.preferences(requireCustomer(actor));
    }

    public List<NotificationPreference> replacePreferences(AuthenticatedUser actor, List<PreferenceChange> changes) {
        UUID customerId = requireCustomer(actor);
        Instant now = Instant.now(clock);
        List<NotificationPreference> rows = new ArrayList<>();
        for (PreferenceChange change : changes) {
            if (!NotificationModels.channel(change.channel())) {
                throw ValidationException.field("channel", "Unknown notification channel");
            }
            if (!"*".equals(change.eventType()) && !NotificationModels.event(change.eventType())) {
                throw ValidationException.field("eventType", "Unknown notification event");
            }
            if ("OTP".equals(change.eventType()) && !change.enabled()) {
                throw ValidationException.field("enabled", "Verification codes cannot be turned off");
            }
            rows.add(new NotificationPreference(UUID.randomUUID(), customerId, change.channel(), change.eventType(), change.enabled(), now));
        }
        store.replacePreferences(customerId, rows);
        return store.preferences(customerId);
    }

    public PageResponse<NotificationTemplate> templates(AuthenticatedUser actor, int page, int size) {
        requireManage(actor);
        PageBounds bounds = PageBounds.of(page, size);
        return new PageResponse<>(store.listTemplates(bounds.size(), bounds.offset()), bounds.page(), bounds.size(), store.countTemplates());
    }

    public NotificationTemplate template(AuthenticatedUser actor, UUID id) {
        requireManage(actor);
        return store.findTemplate(id).orElseThrow(() -> new ResourceNotFoundException("Notification template"));
    }

    public NotificationTemplate createTemplate(AuthenticatedUser actor, TemplateCommand command, ClientContext client) {
        requireManage(actor);
        NotificationTemplate template = build(command, 1, actor.getUsername());
        store.insertTemplate(template);
        audit.record(entry("NOTIFICATION_TEMPLATE_CREATED", template.id(), actor, client, java.util.Map.of(
                "eventType", template.eventType(), "channel", template.channel(), "version", template.version())));
        return template;
    }

    public NotificationTemplate reviseTemplate(AuthenticatedUser actor, UUID id, TemplateCommand command, ClientContext client) {
        requireManage(actor);
        NotificationTemplate current = template(actor, id);
        if (!current.eventType().equals(command.eventType()) || !current.channel().equals(command.channel())) {
            throw ValidationException.field("eventType", "A revision keeps the same event and channel");
        }
        store.deactivateTemplate(current.id(), actor.getUsername());
        NotificationTemplate revised = build(command, current.version() + 1, actor.getUsername());
        try {
            store.insertTemplate(revised);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new ConflictException("That template version already exists");
        }
        audit.record(entry("NOTIFICATION_TEMPLATE_REVISED", revised.id(), actor, client, java.util.Map.of(
                "eventType", revised.eventType(), "channel", revised.channel(), "version", revised.version())));
        return revised;
    }

    public void deleteTemplate(AuthenticatedUser actor, UUID id, ClientContext client) {
        requireManage(actor);
        NotificationTemplate current = template(actor, id);
        store.deactivateTemplate(id, actor.getUsername());
        audit.record(entry("NOTIFICATION_TEMPLATE_RETIRED", id, actor, client, java.util.Map.of(
                "eventType", current.eventType(), "channel", current.channel(), "version", current.version())));
    }

    private NotificationTemplate build(TemplateCommand command, int version, String actor) {
        if (!NotificationModels.event(command.eventType())) {
            throw ValidationException.field("eventType", "Unknown notification event");
        }
        if (!NotificationModels.channel(command.channel())) {
            throw ValidationException.field("channel", "Unknown notification channel");
        }
        TemplateRenderer.assertBody(command.body());
        String locale = command.locale() == null || command.locale().isBlank() ? "en" : command.locale().trim().toLowerCase(Locale.ROOT);
        if (locale.length() > 16) {
            throw ValidationException.field("locale", "locale is too long");
        }
        Instant now = Instant.now(clock);
        String code = command.eventType() + "_" + command.channel() + "_V" + version;
        return new NotificationTemplate(
                UUID.randomUUID(), code, command.channel(), command.eventType(), locale, blank(command.subject()),
                command.body().trim(), true, version, now, now, actor, actor);
    }

    private MessageQuery scope(AuthenticatedUser actor, String status, PageBounds bounds) {
        if (!staff(actor)) {
            return new MessageQuery(requireCustomer(actor), null, status, bounds.size(), bounds.offset());
        }
        if (!actor.permissions().contains(PermissionCodes.NOTIFICATION_VIEW)) {
            throw new ForbiddenException("You do not have permission to view notifications");
        }
        return new MessageQuery(null, null, status, bounds.size(), bounds.offset());
    }

    private UUID requireCustomer(AuthenticatedUser actor) {
        if (staff(actor)) {
            throw new ForbiddenException("This endpoint is for customer accounts");
        }
        return customers.findByUserId(actor.getId()).orElseThrow(() -> new ResourceNotFoundException("Customer")).id();
    }

    private boolean staff(AuthenticatedUser actor) {
        return actor.getPrincipalType() != PrincipalType.CUSTOMER;
    }

    private static void requireManage(AuthenticatedUser actor) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER || !actor.permissions().contains(PermissionCodes.NOTIFICATION_MANAGE)) {
            throw new ForbiddenException("You do not have permission to manage notification templates");
        }
    }

    private static String status(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String selected = status.trim().toUpperCase(Locale.ROOT);
        if (!List.of("PENDING", "DISPATCHING", "SENT", "FAILED", "DEAD", "READ", "SKIPPED").contains(selected)) {
            throw ValidationException.field("status", "Unknown notification status");
        }
        return selected;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static AuditEntry entry(String action, UUID id, AuthenticatedUser actor, ClientContext client, java.util.Map<String, Object> after) {
        return new AuditEntry(action, "NOTIFICATION", "notification_template", id.toString(), actor.getId(), AuditService.roleOf(actor),
                actor.getUsername(), "SUCCESS", client, null, after);
    }

    public record PreferenceChange(String channel, String eventType, boolean enabled) {
    }

    public record TemplateCommand(String eventType, String channel, String locale, String subject, String body) {
    }
}
