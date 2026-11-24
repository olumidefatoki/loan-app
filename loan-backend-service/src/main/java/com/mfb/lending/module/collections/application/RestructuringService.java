package com.mfb.lending.module.collections.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.config.CollectionsProperties;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.collections.calculation.DelinquencyCalculator;
import com.mfb.lending.module.collections.domain.CollectionCodes.RestructuringType;
import com.mfb.lending.module.collections.domain.CollectionModels.DelinquencySnapshot;
import com.mfb.lending.module.collections.domain.CollectionModels.LoanPosition;
import com.mfb.lending.module.collections.domain.CollectionModels.LoanRestructuring;
import com.mfb.lending.module.collections.domain.CollectionModels.RestructuringApproval;
import com.mfb.lending.module.collections.domain.CollectionModels.RestructuringScheduleVersion;
import com.mfb.lending.module.collections.persistence.CollectionsStore;
import com.mfb.lending.module.integration.corebanking.UnitOfWork;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.servicing.application.LoanServicing;
import com.mfb.lending.module.servicing.calculation.HolidayRule;
import org.springframework.stereotype.Service;

@Service
public class RestructuringService {

    private final CollectionsStore store;
    private final ServicingExposureSource exposures;
    private final DelinquencyService delinquency;
    private final ScheduleRevisionPort schedules;
    private final CollectionNoticeService notices;
    private final UnitOfWork unitOfWork;
    private final AuditService audit;
    private final CollectionsProperties properties;
    private final java.time.Clock clock;

    public RestructuringService(
            CollectionsStore store,
            ServicingExposureSource exposures,
            DelinquencyService delinquency,
            ScheduleRevisionPort schedules,
            CollectionNoticeService notices,
            UnitOfWork unitOfWork,
            AuditService audit,
            CollectionsProperties properties,
            java.time.Clock clock) {
        this.store = store;
        this.exposures = exposures;
        this.delinquency = delinquency;
        this.schedules = schedules;
        this.notices = notices;
        this.unitOfWork = unitOfWork;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    public LoanRestructuring request(UUID loanAccountId, RestructuringRequest request) {
        LoanPosition position = exposures.require(loanAccountId);
        if ("CLOSED".equals(position.status()) || "PENDING_ACTIVATION".equals(position.status())) {
            throw new ConflictException("This loan cannot be restructured");
        }
        String justification = requiredJustification(request.justification());
        LocalDate valueDate = request.valueDate() == null ? today() : request.valueDate();
        if (valueDate.isAfter(today())) {
            throw ValidationException.field("valueDate", "Value date cannot be in the future");
        }
        Terms terms = terms(request, position, valueDate);
        if (store.pendingRestructuring(loanAccountId).isPresent()) {
            throw new ConflictException("This loan already has a restructuring waiting for approval");
        }
        delinquency.calculate(valueDate.isAfter(today()) ? today() : valueDate);
        DelinquencySnapshot snapshot = store.latestSnapshot(loanAccountId).orElse(null);
        LoanRestructuring restructuring = new LoanRestructuring(
                UUID.randomUUID(), loanAccountId, request.type().name(), "REQUESTED", justification, terms.tenure(), terms.interestRate(),
                terms.frequency(), terms.repaymentDay(), terms.holidayRule(), terms.revisedInstalment(), terms.moratoriumDays(),
                terms.partialSettlement(), terms.revisedMaturity(), valueDate, request.actorId(), request.actorName(), Instant.now(clock),
                snapshot == null ? 0 : snapshot.daysPastDue(), snapshot == null ? "CURRENT" : snapshot.bucket(),
                snapshot == null ? BigDecimal.ZERO : snapshot.overdueAmount(), null, null, null, null, null, null, null, null, null, null, null, 0);
        store.insertRestructuring(restructuring);
        audit("LOAN_RESTRUCTURING_REQUESTED", restructuring.id(), request.actorName(), java.util.Map.of("type", request.type().name()));
        return restructuring;
    }

    public LoanRestructuring get(UUID id) {
        return store.lockRestructuring(id).orElseThrow(() -> new ResourceNotFoundException("Loan restructuring"));
    }

    public List<LoanRestructuring> history(UUID loanAccountId) {
        exposures.require(loanAccountId);
        return store.restructuringHistory(loanAccountId);
    }

    public RestructuringApproval approval(UUID restructuringId) {
        return store.approvalFor(restructuringId).orElse(null);
    }

    public List<RestructuringScheduleVersion> scheduleLinks(UUID restructuringId) {
        return store.scheduleLinks(restructuringId);
    }

    public LoanRestructuring approve(UUID id, UUID approverId, String approverName, String reason) {
        return unitOfWork.required(() -> decide(id, approverId, approverName, reason, true));
    }

    public LoanRestructuring reject(UUID id, UUID approverId, String approverName, String reason) {
        if (reason == null || reason.isBlank()) {
            throw ValidationException.field("reason", "A rejection needs a reason");
        }
        return unitOfWork.required(() -> decide(id, approverId, approverName, reason, false));
    }

    private LoanRestructuring decide(UUID id, UUID approverId, String approverName, String reason, boolean approve) {
        LoanRestructuring current = store.lockRestructuring(id).orElseThrow(() -> new ResourceNotFoundException("Loan restructuring"));
        if (!"REQUESTED".equals(current.status())) {
            throw new ConflictException("This restructuring has already been decided");
        }
        if (current.requestedBy().equals(approverId)) {
            throw new ConflictException("The officer who requested a restructuring cannot decide it");
        }
        LoanPosition position = exposures.require(current.loanAccountId());
        LocalDate asOf = current.valueDate() == null ? today() : current.valueDate();
        delinquency.calculate(asOf);
        DelinquencySnapshot snapshot = store.latestSnapshot(current.loanAccountId()).orElse(null);
        int dpd = snapshot == null ? current.daysPastDueAtRequest() : snapshot.daysPastDue();
        String bucket = snapshot == null ? current.bucketAtRequest() : snapshot.bucket();
        BigDecimal overdue = snapshot == null ? current.overdueAtRequest() : snapshot.overdueAmount();
        UUID previousScheduleId = null;
        UUID newScheduleId = null;
        BigDecimal principalAfter = position.outstandingPrincipal();
        BigDecimal interestAfter = position.outstandingInterest();
        Boolean matched = null;
        BigDecimal difference = null;
        if (approve) {
            ScheduleRevisionPort.Revision revision = schedules.apply(new ScheduleRevisionPort.RevisionRequest(
                    current.loanAccountId(), current.tenure(), current.interestRate(), current.repaymentFrequency(),
                    current.repaymentDayOfMonth(), holiday(current.holidayRule()), current.moratoriumDays() == null ? 0 : current.moratoriumDays(),
                    current.revisedInstalment(), current.justification(), asOf, approverId, approverName));
            if (revision.outstandingPrincipal().compareTo(position.outstandingPrincipal()) != 0
                    || revision.outstandingFees().compareTo(position.outstandingFees()) != 0
                    || revision.outstandingPenalties().compareTo(position.outstandingPenalties()) != 0
                    || revision.outstandingInterest().compareTo(position.outstandingInterest()) < 0) {
                throw new ConflictException("Restructuring cannot erase accrued obligations or principal");
            }
            LoanServicing.Reconciliation reconciliation = schedules.reconcile(current.loanAccountId());
            previousScheduleId = revision.previousScheduleId();
            newScheduleId = revision.newScheduleId();
            principalAfter = revision.outstandingPrincipal();
            interestAfter = revision.outstandingInterest();
            matched = reconciliation.matched();
            difference = reconciliation.difference();
            store.insertScheduleLink(new RestructuringScheduleVersion(
                    UUID.randomUUID(), current.id(), revision.previousScheduleId(), revision.newScheduleId(),
                    revision.previousVersion(), revision.newVersion(), true));
        }
        LoanRestructuring decided = new LoanRestructuring(
                current.id(), current.loanAccountId(), current.type(), approve ? "APPROVED" : "REJECTED", current.justification(),
                current.tenure(), current.interestRate(), current.repaymentFrequency(), current.repaymentDayOfMonth(), current.holidayRule(),
                current.revisedInstalment(), current.moratoriumDays(), current.partialSettlementAmount(), current.revisedMaturity(),
                current.valueDate(), current.requestedBy(), current.requestedByName(), current.requestedAt(), current.daysPastDueAtRequest(),
                current.bucketAtRequest(), current.overdueAtRequest(), dpd, bucket, overdue, previousScheduleId, newScheduleId,
                position.outstandingPrincipal(), position.outstandingInterest(), principalAfter, interestAfter, matched, difference, current.version() + 1);
        store.updateRestructuring(decided, current.version());
        store.insertApproval(new RestructuringApproval(
                UUID.randomUUID(), current.id(), approve ? "APPROVED" : "REJECTED", approverId, approverName, clean(reason), Instant.now(clock)));
        LoanPosition after = exposures.require(current.loanAccountId());
        notices.restructuringDecision(after, current.id(), approve ? "APPROVED" : "REJECTED", today());
        audit(approve ? "LOAN_RESTRUCTURING_APPROVED" : "LOAN_RESTRUCTURING_REJECTED", current.id(), approverName, java.util.Map.of("type", current.type()));
        return decided;
    }

    private Terms terms(RestructuringRequest request, LoanPosition position, LocalDate valueDate) {
        String frequency = request.repaymentFrequency() == null ? position.repaymentFrequency() : request.repaymentFrequency().name();
        int unpaid = (int) position.instalments().stream().filter(line -> DelinquencyCalculator.unpaid(line).signum() > 0).count();
        int baseline = unpaid == 0 ? Math.max(position.instalments().size(), 1) : unpaid;
        Integer tenure = request.tenure();
        Integer moratorium = null;
        BigDecimal rate = request.interestRate();
        BigDecimal revised = request.revisedInstalment();
        BigDecimal partial = request.partialSettlementAmount();
        LocalDate maturity = request.revisedMaturity();
        switch (request.type()) {
            case TENOR_EXTENSION -> {
                if (tenure == null || tenure <= baseline) {
                    throw ValidationException.field("tenure", "Tenor extension must be longer than the unpaid instalments");
                }
                revised = null;
                partial = null;
                maturity = null;
            }
            case FREQUENCY_CHANGE -> {
                if (request.repaymentFrequency() == null) {
                    throw ValidationException.field("repaymentFrequency", "A frequency change needs a repayment frequency");
                }
                if (tenure == null || tenure < 1) {
                    throw ValidationException.field("tenure", "Tenure must be at least 1");
                }
                revised = null;
                partial = null;
            }
            case REVISED_AMOUNT -> {
                if (tenure == null || tenure < 1) {
                    throw ValidationException.field("tenure", "Tenure must be at least 1");
                }
                if (revised == null || revised.signum() <= 0) {
                    throw ValidationException.field("revisedInstalment", "A revised instalment is required");
                }
                partial = null;
            }
            case MORATORIUM -> {
                if (request.moratoriumDays() == null || request.moratoriumDays() < 1 || request.moratoriumDays() > 366) {
                    throw ValidationException.field("moratoriumDays", "Moratorium must be between 1 and 366 days");
                }
                if (tenure == null || tenure < 1) {
                    tenure = Math.max(baseline, 1);
                }
                moratorium = request.moratoriumDays();
                revised = null;
                partial = null;
            }
            case INTEREST_RESTRUCTURE -> {
                if (rate == null || rate.signum() < 0) {
                    throw ValidationException.field("interestRate", "Interest rate cannot be negative");
                }
                if (tenure == null || tenure < 1) {
                    tenure = Math.max(baseline, 1);
                }
                revised = null;
                partial = null;
            }
            case PARTIAL_SETTLEMENT -> {
                if (partial == null || partial.signum() <= 0) {
                    throw ValidationException.field("partialSettlementAmount", "Partial settlement amount is required");
                }
                BigDecimal outstanding = position.outstandingPrincipal().add(position.outstandingInterest()).add(position.outstandingFees()).add(position.outstandingPenalties());
                if (partial.compareTo(outstanding) >= 0) {
                    throw ValidationException.field("partialSettlementAmount", "Partial settlement must be less than the outstanding balance");
                }
                if (tenure == null || tenure < 1) {
                    tenure = Math.max(baseline, 1);
                }
                revised = null;
                rate = null;
            }
            case MATURITY_REVISION -> {
                if (maturity == null) {
                    throw ValidationException.field("revisedMaturity", "Revised maturity is required");
                }
                tenure = periodsUntil(valueDate, maturity, frequency);
                revised = null;
                partial = null;
            }
            default -> throw ValidationException.field("type", "Restructuring type is required");
        }
        if (request.repaymentDayOfMonth() != null && (request.repaymentDayOfMonth() < 1 || request.repaymentDayOfMonth() > 28)) {
            throw ValidationException.field("repaymentDayOfMonth", "Repayment day must be between 1 and 28");
        }
        if (request.holidayRule() != null) {
            holiday(request.holidayRule().name());
        }
        return new Terms(
                tenure, rate, request.type() == RestructuringType.FREQUENCY_CHANGE ? frequency : request.repaymentFrequency() == null ? null : frequency,
                request.repaymentDayOfMonth(), request.holidayRule() == null ? null : request.holidayRule().name(), revised, moratorium, partial, maturity);
    }

    private static int periodsUntil(LocalDate from, LocalDate maturity, String frequency) {
        if (!maturity.isAfter(from)) {
            throw ValidationException.field("revisedMaturity", "Revised maturity must be after the value date");
        }
        int periods = switch (frequency) {
            case "DAILY" -> (int) ChronoUnit.DAYS.between(from, maturity);
            case "WEEKLY" -> (int) Math.max(1, ChronoUnit.DAYS.between(from, maturity) / 7);
            case "MONTHLY" -> (int) Math.max(1, ChronoUnit.MONTHS.between(from, maturity));
            default -> throw ValidationException.field("repaymentFrequency", "Repayment frequency must be DAILY, WEEKLY, or MONTHLY");
        };
        return Math.max(periods, 1);
    }

    private static HolidayRule holiday(String value) {
        if (value == null) {
            return null;
        }
        try {
            return HolidayRule.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("holidayRule", "Holiday rule is not recognised");
        }
    }

    private static String requiredJustification(String justification) {
        if (justification == null || justification.isBlank() || justification.trim().length() < 15) {
            throw ValidationException.field("justification", "Restructuring needs a documented justification of at least 15 characters");
        }
        if (justification.trim().length() > 500) {
            throw ValidationException.field("justification", "Use 500 characters or fewer");
        }
        return justification.trim();
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of(properties.getZone()));
    }

    private void audit(String action, UUID id, String actor, java.util.Map<String, Object> after) {
        if (audit != null) {
            audit.record(action, "loan_restructuring", id.toString(), actor, null, after);
        }
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record RestructuringRequest(
            RestructuringType type,
            String justification,
            Integer tenure,
            BigDecimal interestRate,
            RepaymentFrequency repaymentFrequency,
            Integer repaymentDayOfMonth,
            HolidayRule holidayRule,
            BigDecimal revisedInstalment,
            Integer moratoriumDays,
            BigDecimal partialSettlementAmount,
            LocalDate revisedMaturity,
            LocalDate valueDate,
            UUID actorId,
            String actorName
    ) {
    }

    private record Terms(
            Integer tenure,
            BigDecimal interestRate,
            String frequency,
            Integer repaymentDay,
            String holidayRule,
            BigDecimal revisedInstalment,
            Integer moratoriumDays,
            BigDecimal partialSettlement,
            LocalDate revisedMaturity
    ) {
    }
}
