package com.mfb.lending.module.collections.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.config.CollectionsProperties;
import com.mfb.lending.module.collections.calculation.DelinquencyRules;
import com.mfb.lending.module.collections.domain.CollectionCodes.ClosureOutcome;
import com.mfb.lending.module.collections.domain.CollectionCodes.RestructuringType;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionAssignment;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionPromise;
import com.mfb.lending.module.collections.domain.CollectionModels.DelinquencySnapshot;
import com.mfb.lending.module.collections.domain.CollectionModels.LoanRestructuring;
import com.mfb.lending.module.collections.domain.CollectionModels.ParReport;
import com.mfb.lending.module.collections.persistence.InMemoryCollectionsStore;
import com.mfb.lending.module.integration.corebanking.UnitOfWork;
import com.mfb.lending.module.loanproduct.domain.DayCountConvention;
import com.mfb.lending.module.loanproduct.domain.EarlyRepaymentRule;
import com.mfb.lending.module.loanproduct.domain.InterestMethod;
import com.mfb.lending.module.loanproduct.domain.InterestPeriod;
import com.mfb.lending.module.loanproduct.domain.PenaltyBasis;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;
import com.mfb.lending.module.servicing.application.ApprovedRestructure;
import com.mfb.lending.module.servicing.application.LoanBooking;
import com.mfb.lending.module.servicing.application.LoanServicing;
import com.mfb.lending.module.servicing.application.RepaymentCommand;
import com.mfb.lending.module.servicing.domain.LoanAccount;
import com.mfb.lending.module.servicing.domain.LoanRepayment;
import com.mfb.lending.module.servicing.domain.RepaymentAllocation;
import com.mfb.lending.module.servicing.domain.RepaymentChannel;
import com.mfb.lending.module.servicing.domain.RepaymentStatus;
import com.mfb.lending.module.servicing.domain.ScheduleLine;
import com.mfb.lending.module.servicing.persistence.InMemoryServicingStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CollectionsWorkflowTest {

    private final MutableClock clock = new MutableClock(LocalDate.of(2026, 1, 15));
    private final InMemoryServicingStore servicingStore = new InMemoryServicingStore();
    private final InMemoryCollectionsStore collections = new InMemoryCollectionsStore();
    private final LoanServicing servicing = new LoanServicing(
            servicingStore, (applicationId, reference) -> {
                throw new UnsupportedOperationException();
            }, new FakeCore(), new DirectWork(), null, new ObjectMapper(), clock);
    private final ServicingExposureSource exposures = new ServicingExposureSource(servicingStore);
    private final CollectionsProperties properties = new CollectionsProperties();
    private final DelinquencyService delinquency = new DelinquencyService(collections, exposures, properties, clock);
    private final CollectionNoticeService notices = new CollectionNoticeService(collections, exposures, properties, clock);
    private final CollectionCaseService cases = new CollectionCaseService(
            collections, exposures, delinquency, notices, officerId -> {
            }, branchId -> true, new DirectWork(), null, properties, clock);
    private final RestructuringService restructuring = new RestructuringService(
            collections, exposures, delinquency, new ServicingScheduleRevision(servicing), notices, new DirectWork(), null, properties, clock);
    private final CollectionsJobs jobs = new CollectionsJobs(delinquency, cases, notices, new CollectionsReportService(collections, delinquency), collections, properties, clock);

    @Test
    void overdueIdentificationIgnoresUnconfirmedAllocationsAndKeepsPartialInstalmentsMissed() {
        LoanAccount account = open("1000.00", "0", 1);
        LocalDate due = servicingStore.lines(account.currentScheduleId()).get(0).dueDate();
        clock.set(due.plusDays(5));

        jobs.runDelinquency(today());
        DelinquencySnapshot overdue = onlySnapshot();
        assertThat(overdue.daysPastDue()).isEqualTo(5);
        assertThat(overdue.bucket()).isEqualTo("DPD_1_30");
        assertThat(overdue.overdueAmount()).isEqualByComparingTo("1000.00");
        assertThat(overdue.missedInstalments()).isEqualTo(1);
        assertThat(overdue.partialPayment()).isFalse();
        assertThat(overdue.outstandingPrincipal()).isEqualByComparingTo("1000.00");

        servicing.repay(command(account, "100.00", "partial-0001"));
        jobs.runDelinquency(today());
        DelinquencySnapshot partial = onlySnapshot();
        assertThat(partial.overdueAmount()).isEqualByComparingTo("900.00");
        assertThat(partial.partialPayment()).isTrue();
        assertThat(partial.missedInstalments()).isEqualTo(1);
        assertThat(partial.daysPastDue()).isEqualTo(5);

        ScheduleLine line = servicingStore.lines(account.currentScheduleId()).get(0);
        LoanRepayment unconfirmed = new LoanRepayment(
                UUID.randomUUID(), "RP000000000099", account.id(), "unposted-01", RepaymentChannel.BRANCH, RepaymentStatus.RECEIVED,
                new BigDecimal("500.00"), today(), false, BigDecimal.ZERO, null, null, null, null, null, 1, 0, null,
                Instant.now(), Instant.now(), actor(), "officer");
        servicingStore.insertRepayment(unconfirmed);
        servicingStore.insertAllocation(new RepaymentAllocation(UUID.randomUUID(), unconfirmed.id(), "PRINCIPAL", new BigDecimal("500.00"), line.id(), "NGN"));
        jobs.runDelinquency(today());
        assertThat(onlySnapshot().overdueAmount()).isEqualByComparingTo("900.00");
        assertThat(collections.jobRuns("DELINQUENCY", today())).isEqualTo(3);
        assertThat(collections.snapshotsOn(today())).hasSize(1);
    }

    @Test
    void portfolioAtRiskUsesTheSameDenominatorAsTheStoredSnapshots() {
        LoanAccount older = open("400.00", "0", 3);
        clock.set(LocalDate.of(2026, 2, 20));
        LoanAccount newer = open("600.00", "0", 3);
        LocalDate olderDue = servicingStore.lines(older.currentScheduleId()).get(0).dueDate();
        clock.set(olderDue.plusDays(40));

        jobs.runDelinquency(today());
        ParReport report = delinquency.par(today());
        List<DelinquencySnapshot> snapshots = collections.snapshotsOn(today());
        BigDecimal denominator = snapshots.stream().filter(DelinquencySnapshot::portfolioIncluded).map(DelinquencySnapshot::outstandingPrincipal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal par30 = snapshots.stream()
                .filter(snapshot -> snapshot.portfolioIncluded() && snapshot.daysPastDue() >= 30)
                .map(DelinquencySnapshot::outstandingPrincipal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(report.denominatorOutstandingPrincipal()).isEqualByComparingTo(denominator);
        assertThat(report.par30().outstandingPrincipal()).isEqualByComparingTo(par30);
        assertThat(report.par60().thresholdDays()).isEqualTo(60);
        assertThat(report.par90().thresholdDays()).isEqualTo(90);
        assertThat(report.denominatorRule()).isEqualTo(DelinquencyRules.DENOMINATOR);
        assertThat(snapshot(older).daysPastDue()).isGreaterThanOrEqualTo(30);
        assertThat(snapshot(newer).daysPastDue()).isLessThan(30);
        assertThat(report.par30().outstandingPrincipal()).isEqualByComparingTo(older.outstandingPrincipal());
        assertThat(report.denominatorOutstandingPrincipal()).isEqualByComparingTo(older.outstandingPrincipal().add(newer.outstandingPrincipal()));
    }

    @Test
    void scheduledJobsCanRunTwiceWithoutDuplicatingCasesSnapshotsOrNotices() {
        LoanAccount account = open("1000.00", "0", 1);
        clock.set(servicingStore.lines(account.currentScheduleId()).get(0).dueDate().plusDays(2));
        jobs.runDelinquency(today());
        jobs.runDelinquency(today());
        jobs.runCases(today());
        jobs.runCases(today());
        int noticesAfterFirst = jobs.runReminders(today());
        int stored = collections.countNotifications();
        int noticesAfterSecond = jobs.runReminders(today());

        assertThat(collections.snapshotsOn(today())).hasSize(1);
        assertThat(collections.allCases()).hasSize(1);
        assertThat(noticesAfterFirst).isPositive();
        assertThat(noticesAfterSecond).isZero();
        assertThat(collections.countNotifications()).isEqualTo(stored);
        assertThat(collections.jobRuns("DELINQUENCY", today())).isEqualTo(2);
        assertThat(collections.jobRuns("COLLECTION_CASES", today())).isEqualTo(2);
        assertThat(collections.jobRuns("REMINDERS", today())).isEqualTo(2);
        assertThatThrownBy(() -> CollectionMessages.payload(Map.of("phone", "08031234567"))).isInstanceOf(ConflictException.class);
    }

    @Test
    void assignmentPromiseMonitoringAndClosureDoNotChangeTheLoanBalance() {
        LoanAccount account = open("1000.00", "0", 1);
        clock.set(servicingStore.lines(account.currentScheduleId()).get(0).dueDate().plusDays(3));
        jobs.runCases(today());
        var collectionCase = collections.allCases().get(0);
        cases.assign(collectionCase.id(), UUID.randomUUID(), account.branchId(), actor(), "maker");
        cases.assign(collectionCase.id(), UUID.randomUUID(), account.branchId(), actor(), "maker");
        assertThat(collections.assignments(collectionCase.id()).stream().filter(CollectionAssignment::active)).hasSize(1);

        collections.insertPromise(new CollectionPromise(
                UUID.randomUUID(), collectionCase.id(), account.id(), new BigDecimal("50.00"), today().minusDays(1), "OPEN", null,
                actor(), Instant.now(clock), null));
        cases.monitorPromises(today());
        cases.monitorPromises(today());
        assertThat(collections.allPromises()).extracting(CollectionPromise::status).containsExactly("BROKEN");
        assertThat(collections.escalations(collectionCase.id())).hasSize(1);

        BigDecimal principal = servicing.get(account.id()).outstandingPrincipal();
        cases.close(collectionCase.id(), ClosureOutcome.UNCOLLECTIBLE, "The customer could not be contacted", null, null, actor(), "officer");
        assertThat(servicing.get(account.id()).outstandingPrincipal()).isEqualByComparingTo(principal);
        assertThat(collections.findCase(collectionCase.id()).orElseThrow().status()).isEqualTo("CLOSED");
        assertThat(collections.findCase(collectionCase.id()).orElseThrow().closureOutcome()).isEqualTo("UNCOLLECTIBLE");
    }

    @Test
    void approvedRestructuringKeepsTheOriginalScheduleAccrualsAndPastDueHistory() {
        LoanAccount account = open("1200.00", "12", 6);
        int originalLines = servicingStore.lines(account.currentScheduleId()).size();
        clock.set(servicingStore.lines(account.currentScheduleId()).get(0).dueDate().plusDays(10));
        jobs.runDelinquency(today());
        int historicDays = onlySnapshot().daysPastDue();
        assertThat(historicDays).isPositive();
        BigDecimal principal = servicing.get(account.id()).outstandingPrincipal();
        BigDecimal interest = servicing.get(account.id()).outstandingInterest();
        UUID maker = UUID.randomUUID();
        UUID approver = UUID.randomUUID();

        LoanRestructuring requested = restructuring.request(account.id(), request(RestructuringType.TENOR_EXTENSION, 12, null, null, null, null, maker));
        assertThatThrownBy(() -> restructuring.approve(requested.id(), maker, "maker", "Same officer")).isInstanceOf(ConflictException.class);
        LoanRestructuring approved = restructuring.approve(requested.id(), approver, "approver", "Authorised concession");

        assertThat(servicing.get(account.id()).scheduleVersion()).isEqualTo(2);
        assertThat(servicing.get(account.id()).outstandingPrincipal()).isEqualByComparingTo(principal);
        assertThat(servicing.get(account.id()).outstandingInterest()).isGreaterThanOrEqualTo(interest);
        assertThat(servicingStore.findSchedule(account.id(), 1).orElseThrow().status()).isEqualTo("SUPERSEDED");
        assertThat(servicingStore.lines(servicingStore.findSchedule(account.id(), 1).orElseThrow().id())).hasSize(originalLines);
        assertThat(approved.daysPastDueAtDecision()).isEqualTo(historicDays);
        assertThat(approved.outstandingPrincipalAfter()).isEqualByComparingTo(principal);
        assertThat(approved.reconciliationMatched()).isTrue();
        assertThat(collections.scheduleLinks(approved.id())).singleElement().satisfies(link -> {
            assertThat(link.preserved()).isTrue();
            assertThat(link.previousScheduleId()).isNotEqualTo(link.newScheduleId());
        });

        jobs.runDelinquency(today());
        assertThat(collections.restructuringHistory(account.id()).get(0).daysPastDueAtDecision()).isEqualTo(historicDays);
        assertThat(onlySnapshot().daysPastDue()).isZero();
        assertThat(servicingStore.lines(servicingStore.findSchedule(account.id(), 1).orElseThrow().id())).hasSize(originalLines);
    }

    @Test
    void revisedInstalmentMustMatchTheEngineAndAPartialSettlementDoesNotReducePrincipal() {
        LoanAccount mismatched = open("1000.00", "10", 4);
        LoanRestructuring bad = restructuring.request(mismatched.id(), request(RestructuringType.REVISED_AMOUNT, 4, mismatched.interestRate(), null, new BigDecimal("0.01"), null, actor()));
        assertThatThrownBy(() -> restructuring.approve(bad.id(), UUID.randomUUID(), "approver", "Does not match")).isInstanceOf(ValidationException.class);
        assertThat(servicing.get(mismatched.id()).scheduleVersion()).isEqualTo(1);

        LoanAccount revised = open("1000.00", "10", 4);
        BigDecimal instalment = servicing.quotedInstalment(new ApprovedRestructure(
                revised.id(), 4, revised.interestRate(), null, null, null, 0, null,
                "Customer requested a revised instalment amount", null, actor(), "officer"));
        LoanRestructuring matched = restructuring.request(revised.id(), request(RestructuringType.REVISED_AMOUNT, 4, revised.interestRate(), null, instalment, null, actor()));
        restructuring.approve(matched.id(), UUID.randomUUID(), "approver", "Instalment agrees");
        assertThat(servicing.get(revised.id()).scheduleVersion()).isEqualTo(2);

        LoanAccount settled = open("1000.00", "10", 4);
        BigDecimal before = servicing.get(settled.id()).outstandingPrincipal();
        BigDecimal beforeInterest = servicing.get(settled.id()).outstandingInterest();
        LoanRestructuring partial = restructuring.request(settled.id(), request(RestructuringType.PARTIAL_SETTLEMENT, null, null, null, null, new BigDecimal("25.00"), actor()));
        LoanRestructuring decided = restructuring.approve(partial.id(), UUID.randomUUID(), "approver", "Agreed part payment");
        assertThat(servicing.get(settled.id()).outstandingPrincipal()).isEqualByComparingTo(before);
        assertThat(servicing.get(settled.id()).outstandingInterest()).isGreaterThanOrEqualTo(beforeInterest);
        assertThat(decided.partialSettlementAmount()).isEqualByComparingTo("25.00");
        assertThat(servicingStore.findSchedule(settled.id(), 1).orElseThrow().status()).isEqualTo("SUPERSEDED");
    }

    @Test
    void frequencyChangeAndMoratoriumCreateANewScheduleWithoutErasingTheOldOne() {
        LoanAccount frequency = open("800.00", "8", 4);
        LoanRestructuring weekly = restructuring.request(frequency.id(), request(RestructuringType.FREQUENCY_CHANGE, 4, null, RepaymentFrequency.WEEKLY, null, null, actor()));
        restructuring.approve(weekly.id(), UUID.randomUUID(), "approver", "Weekly collection was agreed");
        assertThat(servicing.get(frequency.id()).repaymentFrequency()).isEqualTo("WEEKLY");
        assertThat(servicingStore.findSchedule(frequency.id(), 1).orElseThrow().status()).isEqualTo("SUPERSEDED");

        LoanAccount deferred = open("800.00", "8", 4);
        LoanRestructuring moratorium = restructuring.request(deferred.id(), request(RestructuringType.MORATORIUM, 4, null, null, null, null, actor(), 14));
        restructuring.approve(moratorium.id(), UUID.randomUUID(), "approver", "Authorised payment holiday");
        LocalDate firstDue = servicingStore.lines(servicing.get(deferred.id()).currentScheduleId()).get(0).dueDate();
        assertThat(firstDue).isAfterOrEqualTo(today().plusDays(14));
        assertThat(servicing.get(deferred.id()).outstandingPrincipal()).isEqualByComparingTo("800.00");
        assertThat(servicingStore.lines(servicingStore.findSchedule(deferred.id(), 1).orElseThrow().id())).isNotEmpty();
    }

    private RestructuringService.RestructuringRequest request(
            RestructuringType type,
            Integer tenure,
            BigDecimal rate,
            RepaymentFrequency frequency,
            BigDecimal revisedInstalment,
            BigDecimal partial,
            UUID maker) {
        return request(type, tenure, rate, frequency, revisedInstalment, partial, maker, null);
    }

    private RestructuringService.RestructuringRequest request(
            RestructuringType type,
            Integer tenure,
            BigDecimal rate,
            RepaymentFrequency frequency,
            BigDecimal revisedInstalment,
            BigDecimal partial,
            UUID maker,
            Integer moratoriumDays) {
        return new RestructuringService.RestructuringRequest(
                type, "Customer requested this change after a documented income disruption", tenure, rate, frequency, null, null,
                revisedInstalment, moratoriumDays, partial, null, null, maker, "maker");
    }

    private DelinquencySnapshot onlySnapshot() {
        assertThat(collections.snapshotsOn(today())).hasSize(1);
        return collections.snapshotsOn(today()).get(0);
    }

    private DelinquencySnapshot snapshot(LoanAccount account) {
        return collections.snapshotsOn(today()).stream().filter(item -> item.loanAccountId().equals(account.id())).findFirst().orElseThrow();
    }

    private LoanAccount open(String principal, String rate, int tenure) {
        return servicing.openConfirmed(new LoanBooking(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal(principal), new BigDecimal(rate), InterestMethod.REDUCING_BALANCE, InterestPeriod.ANNUAL, tenure, TenureUnit.MONTHS,
                RepaymentFrequency.MONTHLY, DayCountConvention.ACTUAL_365, 2, RoundingMode.HALF_UP, 0, BigDecimal.ZERO, List.of(),
                EarlyRepaymentRule.ALLOWED, BigDecimal.ZERO, PenaltyBasis.NONE, BigDecimal.ZERO, BigDecimal.ZERO, 0, true, "CB000000000001"), actor(), "officer");
    }

    private RepaymentCommand command(LoanAccount account, String amount, String key) {
        return new RepaymentCommand(account.id(), new BigDecimal(amount), RepaymentChannel.BANK_TRANSFER, key, null, null, false, actor(), "officer");
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of("Africa/Lagos"));
    }

    private static UUID actor() {
        return UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    }

    private static final class DirectWork implements UnitOfWork {
        @Override
        public <T> T required(java.util.function.Supplier<T> work) {
            return work.get();
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(LocalDate date) {
            set(date);
        }

        private void set(LocalDate date) {
            instant = date.atTime(12, 0).toInstant(ZoneOffset.UTC);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private static final class FakeCore implements com.mfb.lending.module.servicing.application.CoreCollectionPort {
        @Override
        public Result collect(UUID applicationId, String idempotencyKey, BigDecimal amount, UUID actorId, String actorName) {
            return new Result(UUID.randomUUID(), "CB1", "SUCCESSFUL", true, false, false);
        }

        @Override
        public Result reverse(UUID coreTransactionId, String idempotencyKey, UUID actorId, String actorName) {
            return new Result(coreTransactionId, "RV1", "REVERSED", false, false, true);
        }

        @Override
        public BigDecimal confirmedServicingCash(UUID applicationId) {
            return BigDecimal.ZERO;
        }
    }
}
