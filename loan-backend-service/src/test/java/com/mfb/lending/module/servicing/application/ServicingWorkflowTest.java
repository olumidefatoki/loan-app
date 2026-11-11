package com.mfb.lending.module.servicing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.integration.corebanking.UnitOfWork;
import com.mfb.lending.module.loanproduct.domain.DayCountConvention;
import com.mfb.lending.module.loanproduct.domain.EarlyRepaymentRule;
import com.mfb.lending.module.loanproduct.domain.InterestMethod;
import com.mfb.lending.module.loanproduct.domain.InterestPeriod;
import com.mfb.lending.module.loanproduct.domain.PenaltyBasis;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;
import com.mfb.lending.module.servicing.calculation.AllocationComponent;
import com.mfb.lending.module.servicing.calculation.HolidayRule;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.SettlementQuote;
import com.mfb.lending.module.servicing.calculation.StandardChart;
import com.mfb.lending.module.servicing.domain.JournalHeader;
import com.mfb.lending.module.servicing.domain.JournalLine;
import com.mfb.lending.module.servicing.domain.JournalStatus;
import com.mfb.lending.module.servicing.domain.LoanAccount;
import com.mfb.lending.module.servicing.domain.LoanAccountStatus;
import com.mfb.lending.module.servicing.domain.LoanRepayment;
import com.mfb.lending.module.servicing.domain.RepaymentChannel;
import com.mfb.lending.module.servicing.domain.RepaymentStatus;
import com.mfb.lending.module.servicing.domain.ScheduleLine;
import com.mfb.lending.module.servicing.persistence.InMemoryServicingStore;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServicingWorkflowTest {

    private final MutableClock clock = new MutableClock(LocalDate.of(2026, 1, 15));
    private final InMemoryServicingStore store = new InMemoryServicingStore();
    private final FakeCore core = new FakeCore();
    private final LoanServicing servicing = new LoanServicing(
            store, (applicationId, reference) -> {
                throw new UnsupportedOperationException();
            }, core, new DirectWork(), null, new ObjectMapper(), clock);

    @Test
    void accountStaysInactiveUntilDisbursementIsConfirmed() {
        assertThatThrownBy(() -> servicing.openConfirmed(booking("1000.00", "0", InterestMethod.REDUCING_BALANCE, 1, EarlyRepaymentRule.ALLOWED, false), actor(), "officer"))
                .isInstanceOf(ConflictException.class);
        assertThat(store.countAccounts(null, null)).isZero();
    }

    @Test
    void partialFullAndOverpaymentFollowTheAllocationAndCloseOnlyWhenNothingIsOutstanding() {
        LoanAccount partial = open("1000.00", "0", InterestMethod.REDUCING_BALANCE, 1, EarlyRepaymentRule.ALLOWED);
        LoanRepayment first = pay(partial, "100.00", "partial-0001");
        LoanAccount afterPartial = servicing.get(partial.id());
        assertThat(first.status()).isEqualTo(RepaymentStatus.POSTED);
        assertThat(afterPartial.outstandingPrincipal()).isEqualByComparingTo("900.00");
        assertThat(afterPartial.status()).isEqualTo(LoanAccountStatus.ACTIVE);

        LoanAccount full = open("1000.00", "0", InterestMethod.REDUCING_BALANCE, 1, EarlyRepaymentRule.ALLOWED);
        pay(full, "1000.00", "full-0000001");
        LoanAccount closed = servicing.get(full.id());
        assertThat(closed.status()).isEqualTo(LoanAccountStatus.CLOSED);
        assertThat(closed.totalOutstanding()).isEqualByComparingTo("0.00");
        assertThat(closed.closedAt()).isNotNull();

        LoanAccount extra = open("1000.00", "0", InterestMethod.REDUCING_BALANCE, 1, EarlyRepaymentRule.ALLOWED);
        pay(extra, "1050.00", "over-0000001");
        LoanAccount overpaid = servicing.get(extra.id());
        assertThat(overpaid.status()).isEqualTo(LoanAccountStatus.CLOSED);
        assertThat(overpaid.outstandingPrincipal()).isEqualByComparingTo("0.00");
        assertThat(overpaid.overpayment()).isEqualByComparingTo("50.00");
        assertThat(store.journalLines(cashJournal(extra.id()).id()).stream()
                .filter(line -> "2200".equals(line.accountCode()))
                .map(JournalLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo("50.00");
        assertBalanced();
    }

    @Test
    void duplicateRepaymentIsPostedOnceAndAFailedCollectionDoesNotReducePrincipal() {
        LoanAccount account = open("1000.00", "0", InterestMethod.REDUCING_BALANCE, 1, EarlyRepaymentRule.ALLOWED);
        LoanRepayment first = pay(account, "100.00", "dup-key-0001");
        LoanRepayment second = pay(account, "100.00", "dup-key-0001");
        assertThat(second.id()).isEqualTo(first.id());
        assertThat(core.collects).isEqualTo(1);
        assertThat(servicing.get(account.id()).outstandingPrincipal()).isEqualByComparingTo("900.00");

        core.posted = false;
        core.failed = true;
        LoanAccount failedAccount = open("1000.00", "0", InterestMethod.REDUCING_BALANCE, 1, EarlyRepaymentRule.ALLOWED);
        LoanRepayment failed = pay(failedAccount, "100.00", "fail-key-001");
        assertThat(failed.status()).isEqualTo(RepaymentStatus.FAILED);
        assertThat(servicing.get(failedAccount.id()).outstandingPrincipal()).isEqualByComparingTo("1000.00");
    }

    @Test
    void unconfirmedCollectionDoesNotPostUntilALaterConfirmation() {
        LoanAccount account = open("1000.00", "0", InterestMethod.REDUCING_BALANCE, 1, EarlyRepaymentRule.ALLOWED);
        core.posted = false;
        core.failed = false;
        LoanRepayment waiting = pay(account, "100.00", "wait-key-001");
        assertThat(waiting.status()).isEqualTo(RepaymentStatus.AWAITING_CONFIRMATION);
        assertThat(servicing.get(account.id()).outstandingPrincipal()).isEqualByComparingTo("1000.00");
        core.posted = true;
        LoanRepayment confirmed = pay(account, "100.00", "wait-key-001");
        assertThat(confirmed.id()).isEqualTo(waiting.id());
        assertThat(confirmed.status()).isEqualTo(RepaymentStatus.POSTED);
        assertThat(servicing.get(account.id()).outstandingPrincipal()).isEqualByComparingTo("900.00");
    }

    @Test
    void concurrentRepaymentsBothReducePrincipal() throws Exception {
        LoanAccount account = open("1000.00", "0", InterestMethod.REDUCING_BALANCE, 1, EarlyRepaymentRule.ALLOWED);
        CountDownLatch ready = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<LoanRepayment> left = pool.submit(() -> waitAndPay(ready, account, "100.00", "conc-left-01"));
            Future<LoanRepayment> right = pool.submit(() -> waitAndPay(ready, account, "100.00", "conc-right1"));
            assertThat(left.get().status()).isEqualTo(RepaymentStatus.POSTED);
            assertThat(right.get().status()).isEqualTo(RepaymentStatus.POSTED);
        } finally {
            pool.shutdownNow();
        }
        assertThat(servicing.get(account.id()).outstandingPrincipal()).isEqualByComparingTo("800.00");
        assertBalanced();
    }

    @Test
    void reversalRestoresTheBalanceAndLeavesTheOriginalJournalUntouched() {
        LoanAccount account = open("1000.00", "0", InterestMethod.REDUCING_BALANCE, 1, EarlyRepaymentRule.ALLOWED);
        LoanRepayment repayment = pay(account, "100.00", "rev-pay-0001");
        JournalHeader original = cashJournal(account.id());
        List<String> before = store.journalLines(original.id()).stream().map(ServicingWorkflowTest::lineText).toList();
        LoanRepayment reversed = servicing.reverse(repayment.id(), "rev-key-0001", actor(), "officer");
        assertThat(reversed.status()).isEqualTo(RepaymentStatus.REVERSED);
        assertThat(servicing.get(account.id()).outstandingPrincipal()).isEqualByComparingTo("1000.00");
        assertThat(servicing.get(account.id()).status()).isEqualTo(LoanAccountStatus.ACTIVE);
        assertThat(store.journalLines(original.id()).stream().map(ServicingWorkflowTest::lineText).toList()).isEqualTo(before);
        JournalHeader mirror = store.listJournals(20, 0).stream()
                .filter(journal -> original.id().equals(journal.reversalOf()))
                .findFirst()
                .orElseThrow();
        assertThat(mirror.status()).isEqualTo(JournalStatus.POSTED);
        assertBalanced();
    }

    @Test
    void earlySettlementChargesTheFeeAndAccrualToMaturityEqualsContractualInterest() {
        LoanAccount account = open("1200.00", "10", InterestMethod.FLAT, 12, EarlyRepaymentRule.ALLOWED_WITH_FEE);
        clock.set(LocalDate.of(2026, 1, 25));
        SettlementQuote quote = servicing.settlementQuote(account.id(), LocalDate.of(2026, 1, 25));
        BigDecimal piece = new BigDecimal("10.00").divide(BigDecimal.valueOf(31), 2, RoundingMode.DOWN);
        assertThat(quote.accruedInterest()).isEqualByComparingTo(piece.multiply(BigDecimal.TEN));
        assertThat(quote.earlyFee()).isEqualByComparingTo("24.00");
        assertThat(quote.unearnedInterest().signum()).isPositive();
        LoanRepayment settled = servicing.settle(command(account, quote.totalDue(), "settle-00001", true));
        LoanAccount closed = servicing.get(account.id());
        assertThat(settled.status()).isEqualTo(RepaymentStatus.POSTED);
        assertThat(settled.feeCharged()).isEqualByComparingTo("24.00");
        assertThat(closed.status()).isEqualTo(LoanAccountStatus.CLOSED);
        assertThat(closed.totalOutstanding()).isEqualByComparingTo("0.00");

        LoanAccount matured = open("1200.00", "10", InterestMethod.FLAT, 12, EarlyRepaymentRule.ALLOWED);
        BigDecimal contractual = store.lines(matured.currentScheduleId()).stream().map(ScheduleLine::interestDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        clock.set(matured.maturityDate());
        pay(matured, "0.01", "accrue-00001");
        assertThat(servicing.get(matured.id()).accruedInterest()).isEqualByComparingTo(contractual);
        assertBalanced();
    }

    @Test
    void earlySettlementIsRefusedWhenTheProductDoesNotAllowIt() {
        LoanAccount account = open("1200.00", "10", InterestMethod.FLAT, 12, EarlyRepaymentRule.NOT_ALLOWED);
        clock.set(LocalDate.of(2026, 1, 25));
        assertThatThrownBy(() -> servicing.settlementQuote(account.id(), LocalDate.of(2026, 1, 25)))
                .isInstanceOf(ValidationException.class);
        assertThat(servicing.get(account.id()).outstandingPrincipal()).isEqualByComparingTo("1200.00");
    }

    @Test
    void restructureKeepsTheOldScheduleAndAPrincipalFirstPolicyChangesAllocation() {
        LoanAccount account = open("1200.00", "10", InterestMethod.FLAT, 12, EarlyRepaymentRule.ALLOWED);
        clock.set(LocalDate.of(2026, 2, 15));
        servicing.restructure(new RestructureCommand(
                account.id(), 6, new BigDecimal("8"), false, null, HolidayRule.NONE, "Customer concession", null, actor(), "officer"));
        LoanAccount next = servicing.get(account.id());
        assertThat(next.scheduleVersion()).isEqualTo(2);
        assertThat(store.findSchedule(account.id(), 1).orElseThrow().status()).isEqualTo("SUPERSEDED");
        var current = store.findSchedule(account.id(), null).orElseThrow();
        assertThat(current.versionNumber()).isEqualTo(2);
        assertThat(current.fingerprint()).hasSize(64);
        assertThat(current.parameters()).contains("\"tenure\":6").contains("8");
        assertThat(store.lines(current.id())).hasSize(6);

        store.savePolicy(new StandardChart.AllocationPolicy(2, LocalDate.of(2020, 1, 1), List.of(
                AllocationComponent.PRINCIPAL, AllocationComponent.INTEREST, AllocationComponent.FEE, AllocationComponent.PENALTY)));
        LoanAccount interestBearing = open("1200.00", "10", InterestMethod.FLAT, 12, EarlyRepaymentRule.ALLOWED);
        clock.set(interestBearing.maturityDate());
        pay(interestBearing, "5.00", "policy-00001");
        LoanAccount allocated = servicing.get(interestBearing.id());
        assertThat(allocated.outstandingPrincipal()).isEqualByComparingTo("1195.00");
        assertThat(allocated.outstandingInterest()).isEqualByComparingTo(allocated.accruedInterest());
    }

    @Test
    void interestAdjustmentDoesNothingUntilADifferentOfficerApprovesIt() {
        LoanAccount account = open("1000.00", "0", InterestMethod.REDUCING_BALANCE, 1, EarlyRepaymentRule.ALLOWED);
        UUID maker = actor();
        var adjustment = servicing.adjustInterest(account.id(), new BigDecimal("10.00"), "Rate concession", maker, "maker");
        assertThat(servicing.get(account.id()).outstandingInterest()).isEqualByComparingTo("0.00");
        assertThat(servicing.journal(adjustment.journalId()).status()).isEqualTo(JournalStatus.DRAFT);
        assertThatThrownBy(() -> servicing.approveJournal(adjustment.journalId(), maker)).isInstanceOf(ConflictException.class);
        JournalHeader posted = servicing.approveJournal(adjustment.journalId(), UUID.randomUUID());
        assertThat(posted.status()).isEqualTo(JournalStatus.POSTED);
        assertThat(servicing.get(account.id()).outstandingInterest()).isEqualByComparingTo("10.00");
        assertThat(servicing.get(account.id()).accruedInterest()).isEqualByComparingTo("10.00");
        assertThatThrownBy(() -> servicing.approveJournal(adjustment.journalId(), UUID.randomUUID())).isInstanceOf(ConflictException.class);
        assertBalanced();
    }

    private LoanRepayment waitAndPay(CountDownLatch ready, LoanAccount account, String amount, String key) throws InterruptedException {
        ready.countDown();
        ready.await();
        return pay(account, amount, key);
    }

    private LoanRepayment pay(LoanAccount account, String amount, String key) {
        return servicing.repay(command(account, new BigDecimal(amount), key, false));
    }

    private RepaymentCommand command(LoanAccount account, BigDecimal amount, String key, boolean settlement) {
        return new RepaymentCommand(account.id(), amount, RepaymentChannel.BANK_TRANSFER, key, null, null, settlement, actor(), "officer");
    }

    private LoanAccount open(String principal, String rate, InterestMethod method, int tenure, EarlyRepaymentRule early) {
        return servicing.openConfirmed(booking(principal, rate, method, tenure, early, true), actor(), "officer");
    }

    private LoanBooking booking(String principal, String rate, InterestMethod method, int tenure, EarlyRepaymentRule early, boolean confirmed) {
        return new LoanBooking(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                new BigDecimal(principal), new BigDecimal(rate), method, InterestPeriod.ANNUAL, tenure, TenureUnit.MONTHS,
                RepaymentFrequency.MONTHLY, DayCountConvention.ACTUAL_365, 2, RoundingMode.HALF_UP, 0, BigDecimal.ZERO, List.of(),
                early, new BigDecimal("2.00"), PenaltyBasis.NONE, BigDecimal.ZERO, BigDecimal.ZERO, 0, confirmed, "CB000000000001");
    }

    private JournalHeader cashJournal(UUID accountId) {
        return store.listJournals(50, 0).stream()
                .filter(journal -> accountId.equals(journal.loanAccountId()) && "REPAYMENT".equals(journal.eventCode()))
                .findFirst()
                .orElseThrow();
    }

    private void assertBalanced() {
        for (JournalHeader header : store.listJournals(200, 0)) {
            if (header.status() != JournalStatus.POSTED) {
                continue;
            }
            BigDecimal debits = BigDecimal.ZERO;
            BigDecimal credits = BigDecimal.ZERO;
            for (JournalLine line : store.journalLines(header.id())) {
                if ("DEBIT".equals(line.direction())) {
                    debits = debits.add(line.amount());
                } else {
                    credits = credits.add(line.amount());
                }
            }
            assertThat(debits).as(header.reference()).isEqualByComparingTo(credits);
            assertThat(debits.signum()).isPositive();
        }
    }

    private static String lineText(JournalLine line) {
        return line.lineNumber() + ":" + line.accountCode() + ":" + line.direction() + ":" + line.amount().toPlainString();
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

    private static final class FakeCore implements CoreCollectionPort {
        private final AtomicInteger sequence = new AtomicInteger();
        private int collects;
        private boolean posted = true;
        private boolean failed;

        @Override
        public Result collect(UUID applicationId, String idempotencyKey, BigDecimal amount, UUID actorId, String actorName) {
            collects++;
            int number = sequence.incrementAndGet();
            return new Result(UUID.randomUUID(), "CB" + number, posted ? "SUCCESSFUL" : "SUBMITTED", posted, failed, false);
        }

        @Override
        public Result reverse(UUID coreTransactionId, String idempotencyKey, UUID actorId, String actorName) {
            return new Result(coreTransactionId, "RV" + sequence.incrementAndGet(), "REVERSED", false, false, true);
        }

        @Override
        public BigDecimal confirmedServicingCash(UUID applicationId) {
            return BigDecimal.ZERO;
        }
    }
}
