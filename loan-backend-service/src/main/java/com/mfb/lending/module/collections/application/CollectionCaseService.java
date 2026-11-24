package com.mfb.lending.module.collections.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.config.CollectionsProperties;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.collections.domain.CollectionCodes.ActivityType;
import com.mfb.lending.module.collections.domain.CollectionCodes.ClosureOutcome;
import com.mfb.lending.module.collections.domain.CollectionCodes.ContactChannel;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionActivity;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionAssignment;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionCase;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionEscalation;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionPromise;
import com.mfb.lending.module.collections.domain.CollectionModels.DelinquencySnapshot;
import com.mfb.lending.module.collections.domain.CollectionModels.LoanPosition;
import com.mfb.lending.module.collections.domain.CollectionModels.PostedRepayment;
import com.mfb.lending.module.collections.domain.CollectionModels.RecoveryTransaction;
import com.mfb.lending.module.collections.persistence.CollectionsStore;
import com.mfb.lending.module.integration.corebanking.UnitOfWork;
import org.springframework.stereotype.Service;

@Service
public class CollectionCaseService {

    private static final UUID SYSTEM_ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final CollectionsStore store;
    private final ServicingExposureSource exposures;
    private final DelinquencyService delinquency;
    private final CollectionNoticeService notices;
    private final OfficerDirectory officers;
    private final BranchDirectory branches;
    private final UnitOfWork unitOfWork;
    private final AuditService audit;
    private final CollectionsProperties properties;
    private final java.time.Clock clock;

    public CollectionCaseService(
            CollectionsStore store,
            ServicingExposureSource exposures,
            DelinquencyService delinquency,
            CollectionNoticeService notices,
            OfficerDirectory officers,
            BranchDirectory branches,
            UnitOfWork unitOfWork,
            AuditService audit,
            CollectionsProperties properties,
            java.time.Clock clock) {
        this.store = store;
        this.exposures = exposures;
        this.delinquency = delinquency;
        this.notices = notices;
        this.officers = officers;
        this.branches = branches;
        this.unitOfWork = unitOfWork;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    public Page list(Set<UUID> branchIds, String status, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return new Page(store.listCases(branchIds, blank(status), safeSize, safePage * safeSize), safePage, safeSize, store.countCases(branchIds, blank(status)));
    }

    public CaseDetail get(UUID id) {
        CollectionCase collectionCase = require(id);
        return new CaseDetail(
                collectionCase, store.assignments(id), store.activities(id), store.promises(id), store.escalations(id), store.recoveries(id));
    }

    public int generateCases(LocalDate asOf) {
        if (store.snapshotsOn(asOf).isEmpty()) {
            delinquency.calculate(asOf);
        }
        int processed = 0;
        for (DelinquencySnapshot snapshot : store.snapshotsOn(asOf)) {
            if (!snapshot.delinquent() && !snapshot.overdue() && !snapshot.escalationRequired()) {
                continue;
            }
            openOrRefresh(snapshot);
            processed++;
        }
        return processed;
    }

    public CollectionCase assign(UUID caseId, UUID officerId, UUID branchId, UUID actorId, String actorName) {
        return unitOfWork.required(() -> {
            CollectionCase current = requireOpen(caseId);
            officers.requireCollector(officerId);
            if (!branches.exists(branchId)) {
                throw ValidationException.field("branchId", "Branch was not found");
            }
            store.deactivateAssignments(current.id());
            store.insertAssignment(new CollectionAssignment(UUID.randomUUID(), current.id(), officerId, branchId, actorId, Instant.now(clock), true));
            String status = "ESCALATED".equals(current.status()) ? "ESCALATED" : "ASSIGNED";
            CollectionCase updated = current.assigned(branchId, status, Instant.now(clock));
            store.updateCase(updated, current.version());
            audit("COLLECTION_CASE_ASSIGNED", updated.id(), actorName, java.util.Map.of("officerId", officerId.toString()));
            return updated;
        });
    }

    public CollectionActivity activity(UUID caseId, ActivityType type, ContactChannel channel, String summary, UUID actorId, String actorName) {
        if (type == ActivityType.PROMISE || type == ActivityType.ESCALATION || type == ActivityType.CLOSURE) {
            throw ValidationException.field("activityType", "Use the promise, escalation, or closure action");
        }
        return unitOfWork.required(() -> {
            CollectionCase current = requireOpen(caseId);
            CollectionActivity activity = record(current, type.name(), channel.name(), summary, actorId);
            if ("ASSIGNED".equals(current.status())) {
                CollectionCase progressed = current.withStatus("IN_PROGRESS", Instant.now(clock));
                store.updateCase(progressed, current.version());
            }
            audit("COLLECTION_ACTIVITY_RECORDED", current.id(), actorName, java.util.Map.of("type", type.name()));
            return activity;
        });
    }

    public CollectionPromise promise(UUID caseId, BigDecimal amount, LocalDate promiseDate, String note, UUID actorId, String actorName) {
        if (amount == null || amount.signum() <= 0) {
            throw ValidationException.field("amount", "Promise amount must be greater than zero");
        }
        if (promiseDate == null || promiseDate.isBefore(today())) {
            throw ValidationException.field("promiseDate", "Promise date cannot be before today");
        }
        return unitOfWork.required(() -> {
            CollectionCase current = requireOpen(caseId);
            CollectionPromise promise = new CollectionPromise(
                    UUID.randomUUID(), current.id(), current.loanAccountId(), amount, promiseDate, "OPEN", clean(note), actorId, Instant.now(clock), null);
            store.insertPromise(promise);
            record(current, ActivityType.PROMISE.name(), ContactChannel.PHONE.name(), "Promise to pay " + amount.toPlainString() + " on " + promiseDate, actorId);
            audit("COLLECTION_PROMISE_RECORDED", current.id(), actorName, java.util.Map.of("date", promiseDate.toString()));
            return promise;
        });
    }

    public CollectionEscalation escalate(UUID caseId, String reason, UUID actorId, String actorName) {
        String cleanReason = requiredText(reason, "reason", "An escalation needs a reason");
        return unitOfWork.required(() -> {
            CollectionCase current = requireOpen(caseId);
            CollectionEscalation escalation = openEscalation(current, cleanReason, level(current.daysPastDue()), actorId);
            audit("COLLECTION_CASE_ESCALATED", current.id(), actorName, java.util.Map.of("level", escalation.level()));
            return escalation;
        });
    }

    public RecoveryTransaction recover(UUID caseId, BigDecimal amount, UUID repaymentId, String note, UUID actorId, String actorName) {
        return unitOfWork.required(() -> {
            CollectionCase current = requireOpen(caseId);
            RecoveryTransaction recovery = confirm(current, amount, repaymentId, outcomeFor(current, amount), note, actorId);
            audit("COLLECTION_RECOVERY_RECORDED", current.id(), actorName, java.util.Map.of("repaymentId", repaymentId.toString()));
            return recovery;
        });
    }

    public CollectionCase close(UUID caseId, ClosureOutcome outcome, String note, BigDecimal amount, UUID repaymentId, UUID actorId, String actorName) {
        String cleanNote = requiredText(note, "note", "Case closure needs a note");
        return unitOfWork.required(() -> {
            CollectionCase current = requireOpen(caseId);
            if (outcome == ClosureOutcome.RECOVERED || outcome == ClosureOutcome.PARTIAL_RECOVERY) {
                confirm(current, amount, repaymentId, outcome.name(), cleanNote, actorId);
            }
            CollectionCase closed = current.closed(outcome.name(), cleanNote, Instant.now(clock));
            store.updateCase(closed, current.version());
            store.openEscalation(current.id()).ifPresent(escalation -> store.updateEscalation(escalation.closed()));
            record(closed, ActivityType.CLOSURE.name(), ContactChannel.BRANCH.name(), cleanNote, actorId);
            audit("COLLECTION_CASE_CLOSED", closed.id(), actorName, java.util.Map.of("outcome", outcome.name()));
            return closed;
        });
    }

    public int monitorPromises(LocalDate asOf) {
        int processed = 0;
        ZoneId zone = ZoneId.of(properties.getZone());
        for (CollectionPromise promise : store.allPromises()) {
            if (!"OPEN".equals(promise.status())) {
                continue;
            }
            processed++;
            LoanPosition position = exposures.require(promise.loanAccountId());
            LocalDate createdOn = LocalDate.ofInstant(promise.createdAt(), zone);
            BigDecimal paid = position.postedRepayments().stream()
                    .filter(payment -> !payment.valueDate().isBefore(createdOn) && !payment.valueDate().isAfter(asOf))
                    .map(PostedRepayment::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (paid.compareTo(promise.amount()) >= 0) {
                store.updatePromise(promise.resolved("KEPT", Instant.now(clock)));
                continue;
            }
            if (asOf.isAfter(promise.promiseDate())) {
                store.updatePromise(promise.resolved("BROKEN", Instant.now(clock)));
                store.openCase(promise.loanAccountId()).ifPresent(collectionCase -> {
                    if (!"CLOSED".equals(collectionCase.status()) && store.openEscalation(collectionCase.id()).isEmpty()) {
                        openEscalation(collectionCase, "Promise to pay was missed", 1, promise.createdBy());
                    }
                });
            } else if (asOf.isEqual(promise.promiseDate())) {
                notices.followUp(position, promise, asOf);
                store.openCase(promise.loanAccountId()).ifPresent(collectionCase -> {
                    Instant from = asOf.atStartOfDay(zone).toInstant();
                    if (!store.activityLogged(collectionCase.id(), ActivityType.PAYMENT_FOLLOW_UP.name(), from, from.plus(java.time.Duration.ofDays(1)))) {
                        record(collectionCase, ActivityType.PAYMENT_FOLLOW_UP.name(), ContactChannel.PHONE.name(),
                                "Promise of " + promise.amount().toPlainString() + " is due today", promise.createdBy());
                    }
                });
            }
        }
        return processed;
    }

    public int escalateOverdue(LocalDate asOf) {
        if (store.snapshotsOn(asOf).isEmpty()) {
            delinquency.calculate(asOf);
        }
        int created = 0;
        for (DelinquencySnapshot snapshot : store.snapshotsOn(asOf)) {
            if (!snapshot.escalationRequired()) {
                continue;
            }
            CollectionCase collectionCase = store.openCase(snapshot.loanAccountId()).orElseGet(() -> openOrRefresh(snapshot));
            if (store.openEscalation(collectionCase.id()).isPresent()) {
                continue;
            }
            openEscalation(collectionCase, "Days past due reached " + snapshot.daysPastDue(), snapshot.daysPastDue() > 90 ? 2 : 1, SYSTEM_ACTOR);
            created++;
        }
        return created;
    }

    private CollectionCase openOrRefresh(DelinquencySnapshot snapshot) {
        Instant now = Instant.now(clock);
        return store.openCase(snapshot.loanAccountId()).map(existing -> {
            CollectionCase refreshed = existing.refresh(snapshot, now);
            store.updateCase(refreshed, existing.version());
            return refreshed;
        }).orElseGet(() -> {
            LoanPosition position = exposures.require(snapshot.loanAccountId());
            CollectionCase created = new CollectionCase(
                    UUID.randomUUID(), store.nextCaseReference(), position.loanAccountId(), position.customerId(), position.branchId(),
                    "OPEN", snapshot.bucket(), snapshot.daysPastDue(), snapshot.overdueAmount(), snapshot.collectionExposure(),
                    snapshot.missedInstalments(), snapshot.asOfDate(), null, null, null, 0, now, now);
            try {
                store.insertCase(created);
                return created;
            } catch (ConflictException ex) {
                return store.openCase(snapshot.loanAccountId()).orElseThrow(() -> ex);
            }
        });
    }

    private CollectionEscalation openEscalation(CollectionCase collectionCase, String reason, int level, UUID actorId) {
        if (store.openEscalation(collectionCase.id()).isPresent()) {
            throw new ConflictException("This collection case already has an open escalation");
        }
        CollectionEscalation escalation = new CollectionEscalation(
                UUID.randomUUID(), collectionCase.id(), reason, collectionCase.bucket(), level, "OPEN", actorId, Instant.now(clock));
        store.insertEscalation(escalation);
        if (!"ESCALATED".equals(collectionCase.status())) {
            store.updateCase(collectionCase.withStatus("ESCALATED", Instant.now(clock)), collectionCase.version());
        }
        record(collectionCase, ActivityType.ESCALATION.name(), ContactChannel.BRANCH.name(), reason, actorId);
        return escalation;
    }

    private RecoveryTransaction confirm(CollectionCase collectionCase, BigDecimal amount, UUID repaymentId, String outcome, String note, UUID actorId) {
        if (repaymentId == null) {
            throw ValidationException.field("repaymentId", "Recovery must reference a posted repayment");
        }
        if (amount == null || amount.signum() <= 0) {
            throw ValidationException.field("amount", "Recovery amount must be greater than zero");
        }
        LoanPosition position = exposures.require(collectionCase.loanAccountId());
        PostedRepayment payment = position.postedRepayments().stream()
                .filter(item -> item.id().equals(repaymentId))
                .findFirst()
                .orElseThrow(() -> ValidationException.field("repaymentId", "Recovery must reference a posted repayment on this loan"));
        if (amount.compareTo(payment.amount()) > 0) {
            throw ValidationException.field("amount", "Recovery amount cannot exceed the confirmed repayment");
        }
        if (store.recoveryExists(repaymentId)) {
            throw new ConflictException("This confirmed repayment is already recorded as a recovery");
        }
        RecoveryTransaction recovery = new RecoveryTransaction(
                UUID.randomUUID(), collectionCase.id(), collectionCase.loanAccountId(), repaymentId, amount, outcome, "CONFIRMED", clean(note), actorId, Instant.now(clock));
        store.insertRecovery(recovery);
        return recovery;
    }

    private CollectionActivity record(CollectionCase collectionCase, String type, String channel, String summary, UUID actorId) {
        CollectionActivity activity = new CollectionActivity(
                UUID.randomUUID(), collectionCase.id(), type, channel, requiredText(summary, "summary", "Activity summary is required"),
                Instant.now(clock), actorId, Instant.now(clock));
        store.insertActivity(activity);
        return activity;
    }

    private CollectionCase requireOpen(UUID id) {
        CollectionCase collectionCase = require(id);
        if ("CLOSED".equals(collectionCase.status())) {
            throw new ConflictException("This collection case is closed");
        }
        return collectionCase;
    }

    private CollectionCase require(UUID id) {
        return store.findCase(id).orElseThrow(() -> new ResourceNotFoundException("Collection case"));
    }

    private static String outcomeFor(CollectionCase collectionCase, BigDecimal amount) {
        return amount.compareTo(collectionCase.collectionExposure()) >= 0 && collectionCase.collectionExposure().signum() > 0
                ? ClosureOutcome.RECOVERED.name()
                : ClosureOutcome.PARTIAL_RECOVERY.name();
    }

    private static int level(int daysPastDue) {
        return daysPastDue > 90 ? 2 : 1;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of(properties.getZone()));
    }

    private void audit(String action, UUID id, String actor, java.util.Map<String, Object> after) {
        if (audit != null) {
            audit.record(action, "collection_case", id.toString(), actor, null, after);
        }
    }

    private static String requiredText(String value, String field, String message) {
        if (value == null || value.isBlank()) {
            throw ValidationException.field(field, message);
        }
        String trimmed = value.trim();
        if (trimmed.length() > 500) {
            throw ValidationException.field(field, "Use 500 characters or fewer");
        }
        return trimmed;
    }

    private static String clean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    public record Page(List<CollectionCase> items, int page, int size, long total) {
    }

    public record CaseDetail(
            CollectionCase collectionCase,
            List<CollectionAssignment> assignments,
            List<CollectionActivity> activities,
            List<CollectionPromise> promises,
            List<CollectionEscalation> escalations,
            List<RecoveryTransaction> recoveries
    ) {
    }
}
