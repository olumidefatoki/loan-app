package com.mfb.lending.module.administration.application;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.mfb.lending.common.api.PageBounds;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.common.security.SensitivePayloads;
import com.mfb.lending.module.administration.domain.AdministrationModels.Branch;
import com.mfb.lending.module.administration.domain.AdministrationModels.BusinessCalendar;
import com.mfb.lending.module.administration.domain.AdministrationModels.ChangeRequest;
import com.mfb.lending.module.administration.domain.AdministrationModels.FeatureFlag;
import com.mfb.lending.module.administration.domain.AdministrationModels.OperationalHealth;
import com.mfb.lending.module.administration.domain.AdministrationModels.PublicHoliday;
import com.mfb.lending.module.administration.domain.AdministrationModels.SystemConfiguration;
import com.mfb.lending.module.administration.persistence.AdministrationStore;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.audit.application.AuditService.AuditEntry;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.complaint.application.ComplaintService;
import com.mfb.lending.module.compliance.application.ComplianceService;
import com.mfb.lending.module.notification.persistence.NotificationStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdministrationService {

    private static final Pattern CODE = Pattern.compile("^[A-Z0-9][A-Z0-9_-]{1,31}$");
    private static final Pattern FLAG = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,98}$");

    private final AdministrationStore store;
    private final NotificationStore notifications;
    private final ComplianceService compliance;
    private final ComplaintService complaints;
    private final AuditService auditTrail;
    private final java.time.Clock clock;

    public AdministrationService(
            AdministrationStore store,
            NotificationStore notifications,
            ComplianceService compliance,
            ComplaintService complaints,
            AuditService auditTrail,
            java.time.Clock clock) {
        this.store = store;
        this.notifications = notifications;
        this.compliance = compliance;
        this.complaints = complaints;
        this.auditTrail = auditTrail;
        this.clock = clock;
    }

    public PageResponse<Branch> branches(AuthenticatedUser actor, int page, int size) {
        configure(actor);
        PageBounds bounds = PageBounds.of(page, size);
        return new PageResponse<>(store.listBranches(bounds.size(), bounds.offset()), bounds.page(), bounds.size(), store.countBranches());
    }

    public Branch branch(AuthenticatedUser actor, UUID id) {
        configure(actor);
        return store.findBranch(id).orElseThrow(() -> new ResourceNotFoundException("Branch"));
    }

    @Transactional
    public Branch createBranch(AuthenticatedUser actor, Branch branch, ClientContext client) {
        configure(actor);
        Branch saved = store.insertBranch(normalize(branch, UUID.randomUUID()), actor.getUsername());
        audit(actor, client, "BRANCH_CREATED", "branch", saved.id(), Map.of("code", saved.code(), "status", saved.status()));
        return saved;
    }

    @Transactional
    public Branch updateBranch(AuthenticatedUser actor, UUID id, Branch branch, ClientContext client) {
        configure(actor);
        branch(actor, id);
        Branch saved = normalize(branch, id);
        store.updateBranch(saved, actor.getUsername());
        audit(actor, client, "BRANCH_UPDATED", "branch", id, Map.of("code", saved.code(), "status", saved.status()));
        return store.findBranch(id).orElseThrow(() -> new ResourceNotFoundException("Branch"));
    }

    @Transactional
    public void deleteBranch(AuthenticatedUser actor, UUID id, ClientContext client) {
        configure(actor);
        branch(actor, id);
        try {
            store.deleteBranch(id);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("Branch is in use");
        }
        audit(actor, client, "BRANCH_DELETED", "branch", id, Map.of("deleted", true));
    }

    public PageResponse<SystemConfiguration> configurations(AuthenticatedUser actor, int page, int size) {
        configure(actor);
        PageBounds bounds = PageBounds.of(page, size);
        List<SystemConfiguration> items = store.listConfigurations(bounds.size(), bounds.offset()).stream().map(this::mask).toList();
        return new PageResponse<>(items, bounds.page(), bounds.size(), store.countConfigurations());
    }

    public SystemConfiguration configuration(AuthenticatedUser actor, UUID id) {
        configure(actor);
        return mask(store.findConfiguration(id).orElseThrow(() -> new ResourceNotFoundException("System configuration")));
    }

    @Transactional
    public ConfigWrite saveConfiguration(AuthenticatedUser actor, ConfigCommand command, ClientContext client) {
        configure(actor);
        ConfigurationPolicy.assertKey(command.key());
        String valueType = ConfigurationPolicy.valueType(command.valueType());
        boolean sensitive = ConfigurationPolicy.requiresApproval(command.key(), command.sensitive());
        if ("DELETE".equals(command.operation())) {
            return deleteConfiguration(actor, command.key(), sensitive, client);
        }
        if (command.value() == null || command.value().isBlank()) {
            throw ValidationException.field("configValue", "configValue is required");
        }
        SystemConfiguration current = store.findConfigurationByKey(command.key()).orElse(null);
        if (sensitive) {
            ChangeRequest request = queue(actor, "SYSTEM_CONFIGURATION", command.key(), current == null ? null : current.value(),
                    command.value(), valueType, command.description(), true, "UPSERT");
            audit(actor, client, "CONFIGURATION_CHANGE_REQUESTED", "system_configuration", request.id(),
                    Map.of("key", command.key(), "sensitive", true, "status", "PENDING"));
            return ConfigWrite.pending(request);
        }
        SystemConfiguration saved = store.upsertConfiguration(command.key(), command.value(), command.description(), valueType, false, actor.getUsername());
        audit(actor, client, "CONFIGURATION_UPDATED", "system_configuration", saved.id(),
                Map.of("key", saved.key(), "value", clip(saved.value())));
        return ConfigWrite.applied(mask(saved));
    }

    @Transactional
    public ConfigWrite deleteConfigurationById(AuthenticatedUser actor, UUID id, ClientContext client) {
        configure(actor);
        SystemConfiguration current = store.findConfiguration(id).orElseThrow(() -> new ResourceNotFoundException("System configuration"));
        return deleteConfiguration(actor, current.key(), ConfigurationPolicy.requiresApproval(current.key(), current.sensitive()), client);
    }

    public PageResponse<ChangeRequest> changes(AuthenticatedUser actor, String status, int page, int size) {
        configure(actor);
        PageBounds bounds = PageBounds.of(page, size);
        String selected = status == null || status.isBlank() ? null : status.trim().toUpperCase(Locale.ROOT);
        return new PageResponse<>(store.listChanges(selected, bounds.size(), bounds.offset()), bounds.page(), bounds.size(), store.countChanges(selected));
    }

    @Transactional
    public ChangeRequest approve(AuthenticatedUser actor, UUID id, String reason, ClientContext client) {
        return decide(actor, id, "APPROVED", reason, client);
    }

    @Transactional
    public ChangeRequest reject(AuthenticatedUser actor, UUID id, String reason, ClientContext client) {
        return decide(actor, id, "REJECTED", reason, client);
    }

    public List<BusinessCalendar> calendars(AuthenticatedUser actor) {
        operate(actor);
        return store.listCalendars();
    }

    public CalendarDetail calendar(AuthenticatedUser actor, UUID id) {
        operate(actor);
        return new CalendarDetail(requireCalendar(id), store.holidays(id));
    }

    @Transactional
    public BusinessCalendar createCalendar(AuthenticatedUser actor, BusinessCalendar calendar, ClientContext client) {
        configure(actor);
        BusinessCalendar saved = store.insertCalendar(normalize(calendar, UUID.randomUUID()), actor.getUsername());
        audit(actor, client, "CALENDAR_CREATED", "business_calendar", saved.id(), Map.of("code", saved.code()));
        return saved;
    }

    @Transactional
    public BusinessCalendar updateCalendar(AuthenticatedUser actor, UUID id, BusinessCalendar calendar, ClientContext client) {
        configure(actor);
        requireCalendar(id);
        BusinessCalendar saved = normalize(calendar, id);
        store.updateCalendar(saved, actor.getUsername());
        audit(actor, client, "CALENDAR_UPDATED", "business_calendar", id, Map.of("code", saved.code()));
        return requireCalendar(id);
    }

    @Transactional
    public void deleteCalendar(AuthenticatedUser actor, UUID id, ClientContext client) {
        configure(actor);
        requireCalendar(id);
        store.deleteCalendar(id);
        audit(actor, client, "CALENDAR_DELETED", "business_calendar", id, Map.of("deleted", true));
    }

    @Transactional
    public PublicHoliday addHoliday(AuthenticatedUser actor, UUID calendarId, LocalDate date, String name, ClientContext client) {
        configure(actor);
        requireCalendar(calendarId);
        if (date == null) {
            throw ValidationException.field("holidayDate", "holidayDate is required");
        }
        if (name == null || name.isBlank()) {
            throw ValidationException.field("name", "name is required");
        }
        PublicHoliday holiday = store.insertHoliday(new PublicHoliday(UUID.randomUUID(), calendarId, date, name.trim()), actor.getUsername());
        audit(actor, client, "HOLIDAY_ADDED", "business_calendar", calendarId, Map.of("date", date.toString()));
        return holiday;
    }

    @Transactional
    public void deleteHoliday(AuthenticatedUser actor, UUID calendarId, UUID holidayId, ClientContext client) {
        configure(actor);
        requireCalendar(calendarId);
        store.deleteHoliday(calendarId, holidayId);
        audit(actor, client, "HOLIDAY_DELETED", "business_calendar", calendarId, Map.of("holidayId", holidayId.toString()));
    }

    public boolean businessDay(UUID calendarId, LocalDate date) {
        BusinessCalendar calendar = requireCalendar(calendarId);
        ZoneId zone = ZoneId.of(calendar.timezone());
        DayOfWeek day = date.atStartOfDay(zone).getDayOfWeek();
        if (calendar.weekendDays().contains(day.name())) {
            return false;
        }
        return !store.holiday(calendarId, date);
    }

    public List<FeatureFlag> flags(AuthenticatedUser actor) {
        operate(actor);
        return store.listFlags();
    }

    @Transactional
    public ConfigWrite saveFlag(AuthenticatedUser actor, String key, boolean enabled, String description, boolean sensitive, ClientContext client) {
        configure(actor);
        if (key == null || !FLAG.matcher(key).matches() || SensitivePayloads.sensitiveKey(key)) {
            throw ValidationException.field("flagKey", "Feature flag key is invalid");
        }
        FeatureFlag current = store.findFlagByKey(key).orElse(null);
        if (sensitive || (current != null && current.sensitive())) {
            ChangeRequest request = queue(actor, "FEATURE_FLAG", key, current == null ? null : Boolean.toString(current.enabled()),
                    Boolean.toString(enabled), "BOOLEAN", description, true, "UPSERT");
            audit(actor, client, "FEATURE_FLAG_CHANGE_REQUESTED", "feature_flag", request.id(), Map.of("key", key, "status", "PENDING"));
            return ConfigWrite.pending(request);
        }
        FeatureFlag saved = store.upsertFlag(key, enabled, description, false, actor.getUsername());
        audit(actor, client, "FEATURE_FLAG_UPDATED", "feature_flag", saved.id(), Map.of("key", key, "enabled", enabled));
        return ConfigWrite.flag(saved);
    }

    @Transactional
    public void deleteFlag(AuthenticatedUser actor, UUID id, ClientContext client) {
        configure(actor);
        FeatureFlag flag = store.findFlag(id).orElseThrow(() -> new ResourceNotFoundException("Feature flag"));
        if (flag.sensitive()) {
            throw new ConflictException("A sensitive feature flag must be changed through approval");
        }
        store.deleteFlag(id);
        audit(actor, client, "FEATURE_FLAG_DELETED", "feature_flag", id, Map.of("key", flag.key()));
    }

    public OperationalHealth health(AuthenticatedUser actor) {
        if (actor.getPrincipalType() != PrincipalType.STAFF
                || !(actor.permissions().contains(PermissionCodes.OPERATIONS_VIEW) || actor.permissions().contains(PermissionCodes.SYSTEM_CONFIGURE))) {
            throw new ForbiddenException("You do not have permission to view operational health");
        }
        return new OperationalHealth(
                store.databaseReady(),
                notifications.countByStatus("PENDING") + notifications.countByStatus("FAILED"),
                notifications.countByStatus("DEAD"),
                compliance.openCases(),
                complaints.breachedCount(Instant.now(clock)),
                store.pendingApprovals());
    }

    private ConfigWrite deleteConfiguration(AuthenticatedUser actor, String key, boolean sensitive, ClientContext client) {
        SystemConfiguration current = store.findConfigurationByKey(key).orElseThrow(() -> new ResourceNotFoundException("System configuration"));
        if (sensitive) {
            ChangeRequest request = queue(actor, "SYSTEM_CONFIGURATION", key, current.value(), null, current.valueType(), current.description(), true, "DELETE");
            audit(actor, client, "CONFIGURATION_DELETE_REQUESTED", "system_configuration", request.id(), Map.of("key", key, "status", "PENDING"));
            return ConfigWrite.pending(request);
        }
        store.deleteConfiguration(current.id());
        audit(actor, client, "CONFIGURATION_DELETED", "system_configuration", current.id(), Map.of("key", key));
        return ConfigWrite.applied(mask(current));
    }

    private ChangeRequest decide(AuthenticatedUser actor, UUID id, String status, String reason, ClientContext client) {
        configure(actor);
        if (reason == null || reason.isBlank()) {
            throw ValidationException.field("reason", "A reason is required");
        }
        ChangeRequest request = store.findChange(id).orElseThrow(() -> new ResourceNotFoundException("Configuration change"));
        if (!"PENDING".equals(request.status())) {
            throw new ConflictException("This configuration change is already decided");
        }
        ConfigurationPolicy.assertApprover(request.requestedBy(), actor.getId());
        if ("APPROVED".equals(status)) {
            apply(request, actor.getUsername());
        }
        store.decideChange(id, status, actor.getId(), actor.getUsername(), reason.trim(), Instant.now(clock));
        audit(actor, client, "CONFIGURATION_" + status, request.targetType().toLowerCase(Locale.ROOT), id,
                Map.of("key", request.targetKey(), "sensitive", request.sensitive(), "status", status));
        return store.findChange(id).orElseThrow(() -> new ResourceNotFoundException("Configuration change"));
    }

    private void apply(ChangeRequest request, String actor) {
        if ("FEATURE_FLAG".equals(request.targetType())) {
            if ("DELETE".equals(request.operation())) {
                store.findFlagByKey(request.targetKey()).ifPresent(flag -> store.deleteFlag(flag.id()));
                return;
            }
            store.upsertFlag(request.targetKey(), Boolean.parseBoolean(request.proposedValue()), request.description(), true, actor);
            return;
        }
        if ("DELETE".equals(request.operation())) {
            store.findConfigurationByKey(request.targetKey()).ifPresent(config -> store.deleteConfiguration(config.id()));
            return;
        }
        store.upsertConfiguration(request.targetKey(), request.proposedValue(), request.description(), request.valueType(), true, actor);
    }

    private ChangeRequest queue(
            AuthenticatedUser actor, String targetType, String key, String current, String proposed, String valueType,
            String description, boolean sensitive, String operation) {
        return store.insertChange(new ChangeRequest(
                UUID.randomUUID(), targetType, key, current, proposed, valueType, description, sensitive, operation, "PENDING",
                actor.getId(), actor.getUsername(), null, null, null, Instant.now(clock), null));
    }

    private SystemConfiguration mask(SystemConfiguration configuration) {
        if (!configuration.sensitive()) {
            return configuration;
        }
        return new SystemConfiguration(configuration.id(), configuration.key(), null, configuration.description(), configuration.valueType(), true, true);
    }

    private static Branch normalize(Branch branch, UUID id) {
        if (branch.code() == null || !CODE.matcher(branch.code().trim().toUpperCase(Locale.ROOT)).matches()) {
            throw ValidationException.field("code", "Branch code is invalid");
        }
        if (branch.name() == null || branch.name().isBlank()) {
            throw ValidationException.field("name", "name is required");
        }
        String status = branch.status() == null ? "ACTIVE" : branch.status().trim().toUpperCase(Locale.ROOT);
        if (!"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
            throw ValidationException.field("status", "Status must be ACTIVE or INACTIVE");
        }
        String country = branch.countryCode() == null || branch.countryCode().isBlank() ? "NG" : branch.countryCode().trim().toUpperCase(Locale.ROOT);
        if (country.length() != 2) {
            throw ValidationException.field("countryCode", "countryCode must be two letters");
        }
        return new Branch(id, branch.code().trim().toUpperCase(Locale.ROOT), branch.name().trim(), blank(branch.addressLine()),
                blank(branch.city()), blank(branch.state()), country, blank(branch.phoneNumber()), blank(branch.email()), status);
    }

    private static BusinessCalendar normalize(BusinessCalendar calendar, UUID id) {
        if (calendar.code() == null || !CODE.matcher(calendar.code().trim().toUpperCase(Locale.ROOT)).matches()) {
            throw ValidationException.field("code", "Calendar code is invalid");
        }
        if (calendar.name() == null || calendar.name().isBlank()) {
            throw ValidationException.field("name", "name is required");
        }
        String zone = calendar.timezone() == null || calendar.timezone().isBlank() ? "Africa/Lagos" : calendar.timezone().trim();
        try {
            ZoneId.of(zone);
        } catch (RuntimeException ex) {
            throw ValidationException.field("timezone", "timezone is invalid");
        }
        String weekend = calendar.weekendDays() == null || calendar.weekendDays().isBlank()
                ? "SATURDAY,SUNDAY" : calendar.weekendDays().trim().toUpperCase(Locale.ROOT);
        return new BusinessCalendar(id, calendar.code().trim().toUpperCase(Locale.ROOT), calendar.name().trim(), zone, weekend, calendar.active());
    }

    private BusinessCalendar requireCalendar(UUID id) {
        return store.findCalendar(id).orElseThrow(() -> new ResourceNotFoundException("Business calendar"));
    }

    private static void configure(AuthenticatedUser actor) {
        if (actor.getPrincipalType() != PrincipalType.STAFF || !actor.permissions().contains(PermissionCodes.SYSTEM_CONFIGURE)) {
            throw new ForbiddenException("You do not have permission to administer the platform");
        }
    }

    private static void operate(AuthenticatedUser actor) {
        if (actor.getPrincipalType() != PrincipalType.STAFF
                || !(actor.permissions().contains(PermissionCodes.OPERATIONS_VIEW) || actor.permissions().contains(PermissionCodes.SYSTEM_CONFIGURE))) {
            throw new ForbiddenException("You do not have permission to view platform operations");
        }
    }

    private void audit(AuthenticatedUser actor, ClientContext client, String action, String entityType, UUID id, Map<String, Object> after) {
        auditTrail.record(new AuditEntry(action, AuditService.moduleFor(entityType), entityType, id.toString(), actor.getId(),
                AuditService.roleOf(actor), actor.getUsername(), "SUCCESS", client, null, after));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String clip(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200);
    }

    public record ConfigCommand(String key, String value, String valueType, String description, boolean sensitive, String operation) {
    }

    public record ConfigWrite(boolean pending, SystemConfiguration configuration, ChangeRequest change, FeatureFlag featureFlag) {
        public static ConfigWrite pending(ChangeRequest change) {
            return new ConfigWrite(true, null, change, null);
        }

        public static ConfigWrite applied(SystemConfiguration configuration) {
            return new ConfigWrite(false, configuration, null, null);
        }

        public static ConfigWrite flag(FeatureFlag featureFlag) {
            return new ConfigWrite(false, null, null, featureFlag);
        }
    }

    public record CalendarDetail(BusinessCalendar calendar, List<PublicHoliday> holidays) {
    }
}
