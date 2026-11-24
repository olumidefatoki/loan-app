package com.mfb.lending.module.administration.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.common.security.SensitivePayloads;
import com.mfb.lending.config.PlatformOperationsProperties;
import com.mfb.lending.module.administration.application.AdministrationService.ConfigCommand;
import com.mfb.lending.module.administration.domain.AdministrationModels.Branch;
import com.mfb.lending.module.administration.domain.AdministrationModels.BusinessCalendar;
import com.mfb.lending.module.administration.domain.AdministrationModels.ChangeRequest;
import com.mfb.lending.module.administration.domain.AdministrationModels.FeatureFlag;
import com.mfb.lending.module.administration.domain.AdministrationModels.PublicHoliday;
import com.mfb.lending.module.administration.domain.AdministrationModels.SystemConfiguration;
import com.mfb.lending.module.administration.persistence.AdministrationStore;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.audit.persistence.AuditLogRepository;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.SecurityProfile;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.complaint.application.ComplaintService;
import com.mfb.lending.module.complaint.domain.ComplaintModels.Complaint;
import com.mfb.lending.module.complaint.domain.ComplaintModels.ComplaintActivity;
import com.mfb.lending.module.complaint.persistence.ComplaintStore;
import com.mfb.lending.module.compliance.application.ComplianceService;
import com.mfb.lending.module.compliance.domain.ComplianceModels.CaseActivity;
import com.mfb.lending.module.compliance.domain.ComplianceModels.ComplianceAlert;
import com.mfb.lending.module.compliance.domain.ComplianceModels.ComplianceCase;
import com.mfb.lending.module.compliance.persistence.ComplianceStore;
import com.mfb.lending.module.notification.application.NotificationPublisher;
import com.mfb.lending.module.notification.persistence.NotificationStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlatformControlTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-01T09:00:00Z"), ZoneOffset.UTC);
    private final ClientContext client = new ClientContext("127.0.0.1", "test");
    private final AuditLogRepository auditLog = mock(AuditLogRepository.class);
    private final AuditService audit = new AuditService(auditLog, new ObjectMapper());
    private final MemoryCompliance complianceStore = new MemoryCompliance();
    private final ComplianceService compliance = new ComplianceService(complianceStore, audit, new PlatformOperationsProperties(), clock);
    private final MemoryComplaints complaintStore = new MemoryComplaints();
    private final ComplaintService complaints = new ComplaintService(
            complaintStore, null, NotificationPublisher.noop(), audit, new PlatformOperationsProperties(), clock);
    private final MemoryAdministration administrationStore = new MemoryAdministration();
    private final AdministrationService administration = new AdministrationService(
            administrationStore, mock(NotificationStore.class), compliance, complaints, audit, clock);

    @Test
    void auditPayloadsDropSecretsAndASanctionsMatchStaysOpenUntilItIsReviewed() {
        audit.record("CUSTOMER_UPDATED", "customer", "1", "ada", client, Map.of(
                "password", "secret", "bvn", "12345678901", "accessToken", "abc", "status", "ACTIVE"));
        org.mockito.ArgumentCaptor<String> after = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(auditLog).insert(any(), eq("ada"), isNull(), isNull(), eq("CUSTOMER_UPDATED"), eq("CUSTOMER"), eq("customer"), eq("1"),
                isNull(), after.capture(), eq("127.0.0.1"), eq("test"), eq("SUCCESS"));
        assertThat(after.getValue()).contains("ACTIVE").doesNotContain("secret").doesNotContain("12345678901").doesNotContain("abc");
        assertThat(SensitivePayloads.scrub(Map.of("nin", "12345678901", "name", "Ada"))).containsOnlyKeys("name");

        UUID customerId = UUID.randomUUID();
        compliance.record(customerId, UUID.randomUUID(), "SANCTIONS", "MATCH", "Possible match 12345678901");
        compliance.record(customerId, UUID.randomUUID(), "SANCTIONS", "CLEAR", "No match");
        ComplianceCase opened = complianceStore.cases.values().iterator().next();
        assertThat(opened.status()).isEqualTo("OPEN");
        assertThat(opened.summary()).doesNotContain("12345678901");
        assertThat(complianceStore.alerts.values().iterator().next().status()).isEqualTo("OPEN");

        AuthenticatedUser stranger = staff(UUID.randomUUID(), List.of());
        assertThatThrownBy(() -> compliance.cases(stranger, null, null, 0, 20)).isInstanceOf(ForbiddenException.class);
        UUID officerId = UUID.randomUUID();
        AuthenticatedUser officer = staff(officerId, List.of(PermissionCodes.COMPLIANCE_MANAGE, PermissionCodes.COMPLIANCE_DECIDE));
        complianceStore.officers.add(officerId);
        assertThatThrownBy(() -> compliance.assign(officer, opened.id(), UUID.randomUUID(), client)).isInstanceOf(ValidationException.class);
        assertThat(compliance.assign(officer, opened.id(), officerId, client).status()).isEqualTo("ASSIGNED");
        assertThatThrownBy(() -> compliance.resolve(officer, opened.id(), "CLEARED", "Reviewed the screening evidence", null, client))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("sanctions");

        compliance.note(officer, opened.id(), "Compared the customer file with the screening evidence", client);
        ComplianceCase cleared = compliance.resolve(officer, opened.id(), "CLEARED", "No confirmed sanctions match", "LOW", client);
        assertThat(cleared.status()).isEqualTo("CLEARED");
        assertThat(cleared.evidenceRetainedUntil()).isAfter(Instant.now(clock).atZone(ZoneOffset.UTC).plusYears(6).toInstant());
        assertThat(complianceStore.alerts.values().iterator().next().status()).isEqualTo("CLEARED");

        UUID openerId = UUID.randomUUID();
        ComplianceCase owned = complianceStore.insertCase(new ComplianceCase(
                UUID.randomUUID(), "CML00000002", customerId, opened.alertId(), "SANCTIONS", "OPEN", null, null, "Held",
                null, null, openerId, null, null, null, null, Instant.now(clock)));
        complianceStore.activities.computeIfAbsent(owned.id(), ignored -> new ArrayList<>()).add(
                new CaseActivity(UUID.randomUUID(), owned.id(), "NOTE", "Evidence reviewed", officerId, "Ada", Instant.now(clock)));
        assertThatThrownBy(() -> compliance.resolve(staff(openerId, List.of(PermissionCodes.COMPLIANCE_DECIDE)), owned.id(), "CLEARED",
                "The same officer cannot clear this", null, client)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void aComplaintPastItsSlaIsEscalatedOnce() {
        Complaint complaint = complaintStore.insert(new Complaint(
                UUID.randomUUID(), "CMP00000001", UUID.randomUUID(), null, "SERVICE", "OPEN", "Delayed reply",
                "The branch has not replied", null, Instant.now(clock).minusSeconds(60), null, null, null, Instant.now(clock).minusSeconds(3600)));
        assertThat(complaints.escalateBreaches(Instant.now(clock))).isEqualTo(1);
        assertThat(complaints.escalateBreaches(Instant.now(clock))).isZero();
        assertThat(complaintStore.find(complaint.id()).orElseThrow().status()).isEqualTo("ESCALATED");
        assertThat(complaintStore.activities(complaint.id())).extracting(ComplaintActivity::activityType).containsExactly("ESCALATION");
        AuthenticatedUser stranger = staff(UUID.randomUUID(), List.of());
        assertThatThrownBy(() -> complaints.list(stranger, null, 0, 20)).isInstanceOf(ForbiddenException.class);
    }

    @Test
    void sensitiveConfigurationWaitsForADifferentAdministrator() {
        AuthenticatedUser maker = staff(UUID.randomUUID(), List.of(PermissionCodes.SYSTEM_CONFIGURE, PermissionCodes.OPERATIONS_VIEW));
        AuthenticatedUser approver = staff(UUID.randomUUID(), List.of(PermissionCodes.SYSTEM_CONFIGURE));
        assertThatThrownBy(() -> administration.saveConfiguration(maker,
                new ConfigCommand("auth.password.policy", "12", "STRING", null, false, null), client))
                .isInstanceOf(ValidationException.class);

        var pending = administration.saveConfiguration(maker,
                new ConfigCommand("integration.core-banking.provider", "super-secret-value", "STRING", "provider", false, null), client);
        assertThat(pending.pending()).isTrue();
        assertThat(administrationStore.configurations).isEmpty();
        assertThatThrownBy(() -> administration.approve(maker, pending.change().id(), "Reviewed the provider", client))
                .isInstanceOf(ForbiddenException.class);
        assertThat(administrationStore.configurations).isEmpty();

        ChangeRequest approved = administration.approve(approver, pending.change().id(), "Reviewed the provider", client);
        assertThat(approved.status()).isEqualTo("APPROVED");
        SystemConfiguration stored = administrationStore.configurations.values().iterator().next();
        assertThat(stored.value()).isEqualTo("super-secret-value");
        assertThat(administration.configuration(approver, stored.id()).value()).isNull();
        assertThat(administration.configuration(approver, stored.id()).masked()).isTrue();

        var applied = administration.saveConfiguration(maker,
                new ConfigCommand("complaints.desk-name", "Customer desk", "STRING", null, false, null), client);
        assertThat(applied.pending()).isFalse();
        assertThat(applied.configuration().value()).isEqualTo("Customer desk");
        assertThatThrownBy(() -> administration.configurations(staff(UUID.randomUUID(), List.of(PermissionCodes.OPERATIONS_VIEW)), 0, 20))
                .isInstanceOf(ForbiddenException.class);
        NotificationStore notifications = mock(NotificationStore.class);
        when(notifications.countByStatus(any())).thenReturn(0L);
        AdministrationService health = new AdministrationService(administrationStore, notifications, compliance, complaints, audit, clock);
        assertThat(health.health(maker).database()).isTrue();
    }

    private static AuthenticatedUser staff(UUID id, List<String> permissions) {
        UserRecord record = new UserRecord(id, PrincipalType.STAFF, null, "Ada", null, "ada@mfb.test", null, "hash", UserStatus.ACTIVE,
                true, true, 0, null, Instant.EPOCH, null, false, null, null, Instant.EPOCH, Instant.EPOCH, "test", "test");
        return AuthenticatedUser.from(record, UUID.randomUUID(), new SecurityProfile(List.of("SYSTEM_ADMIN"), permissions, List.of(), true, true));
    }

    private static final class MemoryCompliance implements ComplianceStore {
        private final Map<UUID, ComplianceAlert> alerts = new LinkedHashMap<>();
        private final Map<UUID, ComplianceCase> cases = new LinkedHashMap<>();
        private final Map<UUID, List<CaseActivity>> activities = new LinkedHashMap<>();
        private final Set<UUID> officers = new java.util.HashSet<>();
        private int sequence = 1;

        @Override
        public void insertAlert(ComplianceAlert alert) {
            alerts.put(alert.id(), alert);
        }

        @Override
        public ComplianceCase insertCase(ComplianceCase complianceCase) {
            cases.put(complianceCase.id(), complianceCase);
            return complianceCase;
        }

        @Override
        public Optional<ComplianceAlert> findAlert(UUID id) {
            return Optional.ofNullable(alerts.get(id));
        }

        @Override
        public Optional<ComplianceCase> findCase(UUID id) {
            return Optional.ofNullable(cases.get(id));
        }

        @Override
        public long countAlerts(String status, String alertType) {
            return alerts.size();
        }

        @Override
        public List<ComplianceAlert> listAlerts(String status, String alertType, int limit, int offset) {
            return List.copyOf(alerts.values());
        }

        @Override
        public long countCases(String status, String caseType) {
            return cases.size();
        }

        @Override
        public List<ComplianceCase> listCases(String status, String caseType, int limit, int offset) {
            return List.copyOf(cases.values());
        }

        @Override
        public List<CaseActivity> activities(UUID caseId) {
            return List.copyOf(activities.getOrDefault(caseId, List.of()));
        }

        @Override
        public void insertActivity(CaseActivity activity) {
            activities.computeIfAbsent(activity.caseId(), ignored -> new ArrayList<>()).add(activity);
        }

        @Override
        public void assign(UUID id, UUID assigneeId, String status) {
            ComplianceCase current = cases.get(id);
            cases.put(id, copy(current, status, assigneeId, current.decision(), current.decisionReason(), current.decidedBy(), current.decidedAt(), current.evidenceRetainedUntil()));
        }

        @Override
        public void markInReview(UUID id) {
            ComplianceCase current = cases.get(id);
            if (!"ESCALATED".equals(current.status())) {
                assign(id, current.assigneeId(), "IN_REVIEW");
            }
        }

        @Override
        public void escalate(UUID id, Instant escalatedAt) {
            assign(id, cases.get(id).assigneeId(), "ESCALATED");
        }

        @Override
        public void resolve(ComplianceCase decided) {
            cases.put(decided.id(), decided);
        }

        @Override
        public void updateAlertStatus(UUID alertId, String status) {
            ComplianceAlert current = alerts.get(alertId);
            alerts.put(alertId, new ComplianceAlert(current.id(), current.customerId(), current.screeningResultId(), current.alertType(),
                    current.severity(), status, current.summary(), current.riskClassification(), current.openedAt()));
        }

        @Override
        public boolean officerCanWork(UUID userId) {
            return officers.contains(userId);
        }

        @Override
        public void updateRisk(UUID customerId, String classification, int score, String actor) {
        }

        @Override
        public long openCases() {
            return cases.values().stream().filter(item -> Set.of("OPEN", "ASSIGNED", "IN_REVIEW", "ESCALATED").contains(item.status())).count();
        }

        @Override
        public String nextReference() {
            return "CML" + String.format("%08d", sequence++);
        }

        private static ComplianceCase copy(
                ComplianceCase current, String status, UUID assigneeId, String decision, String reason, UUID decidedBy, Instant decidedAt, Instant retained) {
            return new ComplianceCase(current.id(), current.reference(), current.customerId(), current.alertId(), current.caseType(), status,
                    current.riskClassification(), assigneeId, current.summary(), decision, reason, current.openedBy(), decidedBy, decidedAt,
                    current.escalatedAt(), retained, current.openedAt());
        }
    }

    private static final class MemoryComplaints implements ComplaintStore {
        private final Map<UUID, Complaint> complaints = new LinkedHashMap<>();
        private final Map<UUID, List<ComplaintActivity>> activities = new LinkedHashMap<>();

        @Override
        public Complaint insert(Complaint complaint) {
            complaints.put(complaint.id(), complaint);
            return complaint;
        }

        @Override
        public Optional<Complaint> find(UUID id) {
            return Optional.ofNullable(complaints.get(id));
        }

        @Override
        public long count(UUID customerId, Set<UUID> branchIds, boolean unrestricted, String status) {
            return complaints.size();
        }

        @Override
        public List<Complaint> list(UUID customerId, Set<UUID> branchIds, boolean unrestricted, String status, int limit, int offset) {
            return List.copyOf(complaints.values());
        }

        @Override
        public List<ComplaintActivity> activities(UUID complaintId) {
            return List.copyOf(activities.getOrDefault(complaintId, List.of()));
        }

        @Override
        public void insertActivity(ComplaintActivity activity) {
            activities.computeIfAbsent(activity.complaintId(), ignored -> new ArrayList<>()).add(activity);
        }

        @Override
        public void assign(UUID id, UUID assigneeId) {
        }

        @Override
        public void escalate(UUID id, Instant escalatedAt) {
            Complaint current = complaints.get(id);
            complaints.put(id, new Complaint(current.id(), current.reference(), current.customerId(), current.branchId(), current.category(),
                    "ESCALATED", current.subject(), current.description(), current.assigneeId(), current.slaDueAt(), escalatedAt,
                    current.resolvedAt(), current.resolution(), current.createdAt()));
        }

        @Override
        public void resolve(UUID id, String resolution, Instant resolvedAt) {
        }

        @Override
        public List<Complaint> breached(Instant now) {
            return complaints.values().stream()
                    .filter(item -> Set.of("OPEN", "ASSIGNED", "IN_PROGRESS").contains(item.status()) && item.slaDueAt().isBefore(now))
                    .toList();
        }

        @Override
        public long breachedCount(Instant now) {
            return breached(now).size();
        }

        @Override
        public String nextReference() {
            return "CMP00000001";
        }

        @Override
        public boolean officerCanManage(UUID userId) {
            return true;
        }
    }

    private static final class MemoryAdministration implements AdministrationStore {
        private final Map<String, SystemConfiguration> configurations = new LinkedHashMap<>();
        private final Map<UUID, ChangeRequest> changes = new LinkedHashMap<>();

        @Override
        public boolean databaseReady() {
            return true;
        }

        @Override
        public List<Branch> listBranches(int limit, int offset) {
            return List.of();
        }

        @Override
        public long countBranches() {
            return 0;
        }

        @Override
        public Optional<Branch> findBranch(UUID id) {
            return Optional.empty();
        }

        @Override
        public Optional<Branch> findBranchByCode(String code) {
            return Optional.empty();
        }

        @Override
        public Branch insertBranch(Branch branch, String actor) {
            return branch;
        }

        @Override
        public void updateBranch(Branch branch, String actor) {
        }

        @Override
        public void deleteBranch(UUID id) {
        }

        @Override
        public List<SystemConfiguration> listConfigurations(int limit, int offset) {
            return List.copyOf(configurations.values());
        }

        @Override
        public long countConfigurations() {
            return configurations.size();
        }

        @Override
        public Optional<SystemConfiguration> findConfiguration(UUID id) {
            return configurations.values().stream().filter(item -> item.id().equals(id)).findFirst();
        }

        @Override
        public Optional<SystemConfiguration> findConfigurationByKey(String key) {
            return Optional.ofNullable(configurations.get(key));
        }

        @Override
        public SystemConfiguration upsertConfiguration(String key, String value, String description, String valueType, boolean sensitive, String actor) {
            SystemConfiguration existing = configurations.get(key);
            SystemConfiguration saved = new SystemConfiguration(existing == null ? UUID.randomUUID() : existing.id(), key, value, description, valueType, sensitive, false);
            configurations.put(key, saved);
            return saved;
        }

        @Override
        public void deleteConfiguration(UUID id) {
            configurations.values().removeIf(item -> item.id().equals(id));
        }

        @Override
        public ChangeRequest insertChange(ChangeRequest request) {
            changes.put(request.id(), request);
            return request;
        }

        @Override
        public Optional<ChangeRequest> findChange(UUID id) {
            return Optional.ofNullable(changes.get(id));
        }

        @Override
        public List<ChangeRequest> listChanges(String status, int limit, int offset) {
            return List.copyOf(changes.values());
        }

        @Override
        public long countChanges(String status) {
            return changes.size();
        }

        @Override
        public void decideChange(UUID id, String status, UUID decidedBy, String decidedByName, String reason, Instant decidedAt) {
            ChangeRequest current = changes.get(id);
            changes.put(id, new ChangeRequest(current.id(), current.targetType(), current.targetKey(), current.currentValue(), current.proposedValue(),
                    current.valueType(), current.description(), current.sensitive(), current.operation(), status, current.requestedBy(),
                    current.requestedByName(), decidedBy, decidedByName, reason, current.requestedAt(), decidedAt));
        }

        @Override
        public long pendingApprovals() {
            return changes.values().stream().filter(item -> "PENDING".equals(item.status())).count();
        }

        @Override
        public List<BusinessCalendar> listCalendars() {
            return List.of();
        }

        @Override
        public Optional<BusinessCalendar> findCalendar(UUID id) {
            return Optional.empty();
        }

        @Override
        public BusinessCalendar insertCalendar(BusinessCalendar calendar, String actor) {
            return calendar;
        }

        @Override
        public void updateCalendar(BusinessCalendar calendar, String actor) {
        }

        @Override
        public void deleteCalendar(UUID id) {
        }

        @Override
        public List<PublicHoliday> holidays(UUID calendarId) {
            return List.of();
        }

        @Override
        public PublicHoliday insertHoliday(PublicHoliday holiday, String actor) {
            return holiday;
        }

        @Override
        public void deleteHoliday(UUID calendarId, UUID holidayId) {
        }

        @Override
        public boolean holiday(UUID calendarId, LocalDate date) {
            return false;
        }

        @Override
        public List<FeatureFlag> listFlags() {
            return List.of();
        }

        @Override
        public Optional<FeatureFlag> findFlag(UUID id) {
            return Optional.empty();
        }

        @Override
        public Optional<FeatureFlag> findFlagByKey(String key) {
            return Optional.empty();
        }

        @Override
        public FeatureFlag upsertFlag(String key, boolean enabled, String description, boolean sensitive, String actor) {
            return new FeatureFlag(UUID.randomUUID(), key, enabled, description, sensitive);
        }

        @Override
        public void deleteFlag(UUID id) {
        }
    }
}
