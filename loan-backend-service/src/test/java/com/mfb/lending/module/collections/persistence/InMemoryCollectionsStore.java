package com.mfb.lending.module.collections.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.module.collections.application.CollectionMessages;
import com.mfb.lending.module.collections.domain.CollectionModels.BucketBand;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionActivity;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionAssignment;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionCase;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionEscalation;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionNotification;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionPromise;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionsReport;
import com.mfb.lending.module.collections.domain.CollectionModels.DelinquencySnapshot;
import com.mfb.lending.module.collections.domain.CollectionModels.LoanRestructuring;
import com.mfb.lending.module.collections.domain.CollectionModels.MessageTemplate;
import com.mfb.lending.module.collections.domain.CollectionModels.RecoveryTransaction;
import com.mfb.lending.module.collections.domain.CollectionModels.RestructuringApproval;
import com.mfb.lending.module.collections.domain.CollectionModels.RestructuringScheduleVersion;

public class InMemoryCollectionsStore implements CollectionsStore {

    private final AtomicInteger caseSequence = new AtomicInteger();
    private final ConcurrentHashMap<UUID, DelinquencySnapshot> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CollectionCase> cases = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CollectionAssignment> assignments = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CollectionActivity> activities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CollectionPromise> promises = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CollectionEscalation> escalations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, LoanRestructuring> restructurings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RestructuringApproval> approvals = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RestructuringScheduleVersion> scheduleLinks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, RecoveryTransaction> recoveries = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CollectionNotification> notifications = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<LocalDate, CollectionsReport> reports = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<JobRun> jobRuns = new CopyOnWriteArrayList<>();
    private final List<MessageTemplate> templates = CollectionMessages.approvedTemplates();

    @Override
    public String nextCaseReference() {
        return "CC" + String.format("%012d", caseSequence.incrementAndGet());
    }

    @Override
    public List<BucketBand> buckets() {
        return BucketBand.defaults();
    }

    @Override
    public Optional<MessageTemplate> template(String code) {
        return templates.stream().filter(template -> template.code().equals(code)).findFirst();
    }

    @Override
    public void saveSnapshot(DelinquencySnapshot snapshot) {
        DelinquencySnapshot existing = snapshots.values().stream()
                .filter(item -> item.loanAccountId().equals(snapshot.loanAccountId()) && item.asOfDate().equals(snapshot.asOfDate()))
                .findFirst()
                .orElse(null);
        DelinquencySnapshot saved = existing == null ? snapshot : snapshot.withId(existing.id());
        if (existing != null) {
            snapshots.remove(existing.id());
        }
        snapshots.put(saved.id(), saved);
    }

    @Override
    public List<DelinquencySnapshot> snapshotsOn(LocalDate asOf) {
        return snapshots.values().stream().filter(item -> item.asOfDate().equals(asOf)).toList();
    }

    @Override
    public Optional<DelinquencySnapshot> latestSnapshot(UUID loanAccountId) {
        return snapshots.values().stream()
                .filter(item -> item.loanAccountId().equals(loanAccountId))
                .max(Comparator.comparing(DelinquencySnapshot::asOfDate));
    }

    @Override
    public Optional<CollectionCase> openCase(UUID loanAccountId) {
        return cases.values().stream()
                .filter(item -> item.loanAccountId().equals(loanAccountId) && open(item.status()))
                .findFirst();
    }

    @Override
    public Optional<CollectionCase> findCase(UUID id) {
        return Optional.ofNullable(cases.get(id));
    }

    @Override
    public void insertCase(CollectionCase collectionCase) {
        if (openCase(collectionCase.loanAccountId()).isPresent() && open(collectionCase.status())) {
            throw new ConflictException("An open collection case already exists for this loan");
        }
        cases.put(collectionCase.id(), collectionCase);
    }

    @Override
    public void updateCase(CollectionCase collectionCase, int expectedVersion) {
        CollectionCase current = cases.get(collectionCase.id());
        if (current == null || current.version() != expectedVersion) {
            throw new ConflictException("The collection case was updated by another officer");
        }
        cases.put(collectionCase.id(), collectionCase);
    }

    @Override
    public List<CollectionCase> listCases(Set<UUID> branchIds, String status, int limit, int offset) {
        return filtered(branchIds, status).stream().skip(offset).limit(limit).toList();
    }

    @Override
    public long countCases(Set<UUID> branchIds, String status) {
        return filtered(branchIds, status).size();
    }

    @Override
    public List<CollectionCase> allCases() {
        return List.copyOf(cases.values());
    }

    @Override
    public void insertAssignment(CollectionAssignment assignment) {
        if (assignment.active() && assignments.values().stream().anyMatch(item -> item.caseId().equals(assignment.caseId()) && item.active())) {
            throw new ConflictException("This collection case already has an active assignment");
        }
        assignments.put(assignment.id(), assignment);
    }

    @Override
    public void deactivateAssignments(UUID caseId) {
        assignments.replaceAll((id, assignment) -> assignment.caseId().equals(caseId) && assignment.active()
                ? new CollectionAssignment(assignment.id(), assignment.caseId(), assignment.officerId(), assignment.branchId(), assignment.assignedBy(), assignment.assignedAt(), false)
                : assignment);
    }

    @Override
    public List<CollectionAssignment> assignments(UUID caseId) {
        return assignments.values().stream().filter(item -> item.caseId().equals(caseId)).toList();
    }

    @Override
    public List<CollectionAssignment> activeAssignments() {
        return assignments.values().stream().filter(CollectionAssignment::active).toList();
    }

    @Override
    public void insertActivity(CollectionActivity activity) {
        activities.put(activity.id(), activity);
    }

    @Override
    public List<CollectionActivity> activities(UUID caseId) {
        return activities.values().stream().filter(item -> item.caseId().equals(caseId)).toList();
    }

    @Override
    public boolean activityLogged(UUID caseId, String activityType, Instant fromInclusive, Instant toExclusive) {
        return activities.values().stream().anyMatch(item -> item.caseId().equals(caseId)
                && item.activityType().equals(activityType)
                && !item.contactedAt().isBefore(fromInclusive)
                && item.contactedAt().isBefore(toExclusive));
    }

    @Override
    public void insertPromise(CollectionPromise promise) {
        promises.put(promise.id(), promise);
    }

    @Override
    public void updatePromise(CollectionPromise promise) {
        promises.put(promise.id(), promise);
    }

    @Override
    public List<CollectionPromise> promises(UUID caseId) {
        return promises.values().stream().filter(item -> item.caseId().equals(caseId)).toList();
    }

    @Override
    public List<CollectionPromise> allPromises() {
        return List.copyOf(promises.values());
    }

    @Override
    public void insertEscalation(CollectionEscalation escalation) {
        if ("OPEN".equals(escalation.status()) && openEscalation(escalation.caseId()).isPresent()) {
            throw new ConflictException("This collection case already has an open escalation");
        }
        escalations.put(escalation.id(), escalation);
    }

    @Override
    public Optional<CollectionEscalation> openEscalation(UUID caseId) {
        return escalations.values().stream().filter(item -> item.caseId().equals(caseId) && "OPEN".equals(item.status())).findFirst();
    }

    @Override
    public List<CollectionEscalation> escalations(UUID caseId) {
        return escalations.values().stream().filter(item -> item.caseId().equals(caseId)).toList();
    }

    @Override
    public void updateEscalation(CollectionEscalation escalation) {
        escalations.put(escalation.id(), escalation);
    }

    @Override
    public void insertRestructuring(LoanRestructuring restructuring) {
        if ("REQUESTED".equals(restructuring.status()) && pendingRestructuring(restructuring.loanAccountId()).isPresent()) {
            throw new ConflictException("This loan already has a restructuring waiting for approval");
        }
        restructurings.put(restructuring.id(), restructuring);
    }

    @Override
    public Optional<LoanRestructuring> lockRestructuring(UUID id) {
        return Optional.ofNullable(restructurings.get(id));
    }

    @Override
    public List<LoanRestructuring> restructuringHistory(UUID loanAccountId) {
        return restructurings.values().stream()
                .filter(item -> item.loanAccountId().equals(loanAccountId))
                .sorted(Comparator.comparing(LoanRestructuring::requestedAt))
                .toList();
    }

    @Override
    public Optional<LoanRestructuring> pendingRestructuring(UUID loanAccountId) {
        return restructurings.values().stream()
                .filter(item -> item.loanAccountId().equals(loanAccountId) && "REQUESTED".equals(item.status()))
                .findFirst();
    }

    @Override
    public void updateRestructuring(LoanRestructuring restructuring, int expectedVersion) {
        LoanRestructuring current = restructurings.get(restructuring.id());
        if (current == null || current.version() != expectedVersion) {
            throw new ConflictException("This restructuring was updated by another officer");
        }
        restructurings.put(restructuring.id(), restructuring);
    }

    @Override
    public void insertApproval(RestructuringApproval approval) {
        LoanRestructuring restructuring = restructurings.get(approval.restructuringId());
        if (restructuring != null && restructuring.requestedBy().equals(approval.approverId())) {
            throw new ConflictException("The officer who requested a restructuring cannot decide it");
        }
        if (approvals.values().stream().anyMatch(item -> item.restructuringId().equals(approval.restructuringId()))) {
            throw new ConflictException("This restructuring has already been decided");
        }
        approvals.put(approval.id(), approval);
    }

    @Override
    public Optional<RestructuringApproval> approvalFor(UUID restructuringId) {
        return approvals.values().stream().filter(item -> item.restructuringId().equals(restructuringId)).findFirst();
    }

    @Override
    public void insertScheduleLink(RestructuringScheduleVersion link) {
        if (!link.preserved()) {
            throw new ConflictException("The original repayment schedule was not preserved");
        }
        scheduleLinks.put(link.id(), link);
    }

    @Override
    public List<RestructuringScheduleVersion> scheduleLinks(UUID restructuringId) {
        return scheduleLinks.values().stream().filter(item -> item.restructuringId().equals(restructuringId)).toList();
    }

    @Override
    public void insertRecovery(RecoveryTransaction recovery) {
        if (recoveryExists(recovery.repaymentId())) {
            throw new ConflictException("This confirmed repayment is already recorded as a recovery");
        }
        recoveries.put(recovery.id(), recovery);
    }

    @Override
    public List<RecoveryTransaction> recoveries(UUID caseId) {
        return recoveries.values().stream().filter(item -> item.caseId().equals(caseId)).toList();
    }

    @Override
    public List<RecoveryTransaction> allRecoveries() {
        return List.copyOf(recoveries.values());
    }

    @Override
    public boolean recoveryExists(UUID repaymentId) {
        return recoveries.values().stream().anyMatch(item -> repaymentId.equals(item.repaymentId()));
    }

    @Override
    public boolean insertNotification(CollectionNotification notification) {
        return notifications.putIfAbsent(dedupe(notification), notification) == null;
    }

    @Override
    public List<CollectionNotification> pendingNotifications(int limit) {
        return notifications.values().stream()
                .filter(item -> "PENDING".equals(item.status()))
                .sorted(Comparator.comparing(CollectionNotification::createdAt))
                .limit(limit)
                .toList();
    }

    @Override
    public void markNotification(UUID id, String status, Instant sentAt) {
        notifications.replaceAll((key, notification) -> notification.id().equals(id) && "PENDING".equals(notification.status())
                ? new CollectionNotification(
                notification.id(), notification.loanAccountId(), notification.customerId(), notification.templateCode(),
                notification.businessDate(), notification.scheduleItemId(), notification.repaymentId(), notification.restructuringId(),
                notification.payload(), status, notification.createdAt(), sentAt)
                : notification);
    }

    @Override
    public int countNotifications() {
        return notifications.size();
    }

    @Override
    public void saveReport(CollectionsReport report) {
        reports.put(report.asOfDate(), report);
    }

    @Override
    public Optional<CollectionsReport> report(LocalDate asOf) {
        return Optional.ofNullable(reports.get(asOf));
    }

    @Override
    public void insertJobRun(String jobName, LocalDate businessDate, int processed, Instant at) {
        jobRuns.add(new JobRun(jobName, businessDate, processed));
    }

    @Override
    public int jobRuns(String jobName, LocalDate businessDate) {
        return (int) jobRuns.stream().filter(run -> run.jobName.equals(jobName) && run.businessDate.equals(businessDate)).count();
    }

    private List<CollectionCase> filtered(Set<UUID> branchIds, String status) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        return cases.values().stream()
                .filter(item -> branchIds == null || branchIds.contains(item.branchId()))
                .filter(item -> status == null || status.equals(item.status()))
                .sorted(Comparator.comparing(CollectionCase::openedOn).reversed())
                .toList();
    }

    private static boolean open(String status) {
        return "OPEN".equals(status) || "ASSIGNED".equals(status) || "IN_PROGRESS".equals(status) || "ESCALATED".equals(status);
    }

    private static String dedupe(CollectionNotification notification) {
        return notification.templateCode() + "|" + notification.businessDate() + "|" + notification.loanAccountId()
                + "|" + dash(notification.scheduleItemId()) + "|" + dash(notification.repaymentId()) + "|" + dash(notification.restructuringId());
    }

    private static String dash(UUID id) {
        return id == null ? "-" : id.toString();
    }

    private record JobRun(String jobName, LocalDate businessDate, int processed) {
    }
}
