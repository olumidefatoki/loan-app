package com.mfb.lending.module.servicing.calculation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.loanproduct.domain.DayCountConvention;
import com.mfb.lending.module.loanproduct.domain.EarlyRepaymentRule;
import com.mfb.lending.module.loanproduct.domain.InterestMethod;
import com.mfb.lending.module.loanproduct.domain.InterestPeriod;
import com.mfb.lending.module.loanproduct.domain.PenaltyBasis;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.AllocationResult;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.DailySlice;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.ScheduleQuote;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.ScheduleRequest;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.ScheduleRow;
import com.mfb.lending.module.servicing.calculation.ServicingCalculator.SettlementQuote;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServicingCalculatorTest {

    private static final MathContext MATH = new MathContext(24, RoundingMode.HALF_EVEN);
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    @Test
    void flatInterestSplitsPrincipalAndInterestEvenly() {
        ScheduleQuote quote = ServicingCalculator.schedule(request(
                "1200.00", "10", InterestMethod.FLAT, LocalDate.of(2026, 1, 15), 12, 0, HolidayRule.NONE, null));
        BigDecimal interest = new BigDecimal("1200.00")
                .multiply(new BigDecimal("10"))
                .divide(new BigDecimal("100"), MATH)
                .divide(new BigDecimal("12"), MATH)
                .multiply(new BigDecimal("12"), MATH)
                .setScale(2, ROUNDING);
        assertThat(interest).isEqualByComparingTo("120.00");
        assertThat(quote.quote().totalInterest()).isEqualByComparingTo(interest);
        assertThat(quote.rows()).hasSize(12);
        assertThat(quote.rows()).allSatisfy(row -> {
            assertThat(row.principalDue()).isEqualByComparingTo("100.00");
            assertThat(row.interestDue()).isEqualByComparingTo("10.00");
        });
        assertThat(sum(quote, true)).isEqualByComparingTo("1200.00");
        assertThat(sum(quote, false)).isEqualByComparingTo(interest);
    }

    @Test
    void reducingBalanceUsesTheStandardAnnuityAndFirstInterest() {
        ScheduleQuote quote = ServicingCalculator.schedule(request(
                "1200.00", "12", InterestMethod.REDUCING_BALANCE, LocalDate.of(2026, 1, 15), 12, 0, HolidayRule.NONE, null));
        BigDecimal periodic = new BigDecimal("0.01");
        BigDecimal factor = BigDecimal.ONE.add(periodic).pow(12, MATH);
        BigDecimal instalment = new BigDecimal("1200.00").multiply(periodic, MATH).multiply(factor, MATH)
                .divide(factor.subtract(BigDecimal.ONE), MATH)
                .setScale(2, ROUNDING);
        BigDecimal firstInterest = new BigDecimal("1200.00").multiply(periodic).setScale(2, ROUNDING);
        assertThat(firstInterest).isEqualByComparingTo("12.00");
        assertThat(quote.rows().get(0).interestDue()).isEqualByComparingTo(firstInterest);
        assertThat(quote.rows().get(0).instalmentAmount()).isEqualByComparingTo(instalment);
        assertThat(quote.rows().get(0).principalDue()).isEqualByComparingTo(instalment.subtract(firstInterest));
        assertThat(sum(quote, true)).isEqualByComparingTo("1200.00");
    }

    @Test
    void dailySlicesIncludeTheLeapDayAndAddBackToContractualInterest() {
        ScheduleQuote leap = ServicingCalculator.schedule(request(
                "1200.00", "10", InterestMethod.FLAT, LocalDate.of(2024, 1, 31), 2, 0, HolidayRule.NONE, null));
        ScheduleQuote ordinary = ServicingCalculator.schedule(request(
                "1200.00", "10", InterestMethod.FLAT, LocalDate.of(2023, 1, 31), 2, 0, HolidayRule.NONE, null));
        assertThat(ChronoUnit.DAYS.between(LocalDate.of(2024, 1, 31), LocalDate.of(2024, 2, 29))).isEqualTo(29);
        assertThat(ChronoUnit.DAYS.between(LocalDate.of(2023, 1, 31), LocalDate.of(2023, 2, 28))).isEqualTo(28);
        assertThat(leap.rows().get(0).dueDate()).isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(ordinary.rows().get(0).dueDate()).isEqualTo(LocalDate.of(2023, 2, 28));
        List<DailySlice> leapSlices = ServicingCalculator.slices(LocalDate.of(2024, 1, 31), leap.rows(), 2, ROUNDING);
        List<DailySlice> ordinarySlices = ServicingCalculator.slices(LocalDate.of(2023, 1, 31), ordinary.rows(), 2, ROUNDING);
        assertThat(leapSlices.stream().filter(slice -> slice.instalmentNumber() == 1).count()).isEqualTo(29);
        assertThat(ordinarySlices.stream().filter(slice -> slice.instalmentNumber() == 1).count()).isEqualTo(28);
        assertThat(leapSlices.stream().anyMatch(slice -> slice.date().equals(LocalDate.of(2024, 2, 29)))).isTrue();
        assertThat(total(leapSlices)).isEqualByComparingTo(sum(leap, false));
        assertThat(total(ordinarySlices)).isEqualByComparingTo(sum(ordinary, false));
        BigDecimal piece = new BigDecimal("10.00").divide(BigDecimal.valueOf(29), 2, RoundingMode.DOWN);
        BigDecimal firstTen = leapSlices.stream().filter(slice -> slice.instalmentNumber() == 1).limit(10)
                .map(DailySlice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(firstTen).isEqualByComparingTo(piece.multiply(BigDecimal.TEN));
    }

    @Test
    void thirty360DoesNotAddALeapDayThatActualDayCountIncludes() {
        LocalDate leapStart = LocalDate.of(2024, 2, 28);
        LocalDate leapEnd = LocalDate.of(2024, 3, 1);
        LocalDate ordinaryStart = LocalDate.of(2023, 2, 28);
        LocalDate ordinaryEnd = LocalDate.of(2023, 3, 1);
        assertThat(ServicingCalculator.thirty360Days(leapStart, leapEnd)).isEqualTo(3);
        assertThat(ServicingCalculator.thirty360Days(ordinaryStart, ordinaryEnd)).isEqualTo(3);
        assertThat(ChronoUnit.DAYS.between(leapStart, leapEnd)).isEqualTo(2);
        assertThat(ChronoUnit.DAYS.between(ordinaryStart, ordinaryEnd)).isEqualTo(1);
        assertThat(ServicingCalculator.yearFraction(leapStart, leapEnd, DayCountConvention.THIRTY_360))
                .isEqualByComparingTo(ServicingCalculator.yearFraction(ordinaryStart, ordinaryEnd, DayCountConvention.THIRTY_360));
        assertThat(ServicingCalculator.yearFraction(leapStart, leapEnd, DayCountConvention.ACTUAL_365))
                .isGreaterThan(ServicingCalculator.yearFraction(ordinaryStart, ordinaryEnd, DayCountConvention.ACTUAL_365));
    }

    @Test
    void saturdayRepaymentMovesToTheNextBusinessDayWithoutChangingInterest() {
        LocalDate disbursement = LocalDate.of(2025, 12, 31);
        ScheduleQuote moved = ServicingCalculator.schedule(request(
                "1000.00", "12", InterestMethod.FLAT, disbursement, 1, 0, HolidayRule.NEXT_BUSINESS_DAY, null));
        ScheduleQuote unchanged = ServicingCalculator.schedule(request(
                "1000.00", "12", InterestMethod.FLAT, disbursement, 1, 0, HolidayRule.NONE, null));
        assertThat(unchanged.rows().get(0).contractualDueDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(unchanged.rows().get(0).contractualDueDate().getDayOfWeek()).isEqualTo(DayOfWeek.SATURDAY);
        assertThat(moved.rows().get(0).dueDate()).isEqualTo(LocalDate.of(2026, 2, 2));
        assertThat(moved.rows().get(0).dueDate().getDayOfWeek()).isEqualTo(DayOfWeek.MONDAY);
        assertThat(moved.rows().get(0).interestDue()).isEqualByComparingTo(unchanged.rows().get(0).interestDue());
        assertThat(moved.rows().get(0).principalDue()).isEqualByComparingTo(unchanged.rows().get(0).principalDue());
    }

    @Test
    void allocationPaysInterestBeforePrincipalAndLeavesOverpaymentUnapplied() {
        Map<AllocationComponent, BigDecimal> due = Map.of(
                AllocationComponent.PENALTY, new BigDecimal("0.00"),
                AllocationComponent.FEE, new BigDecimal("0.00"),
                AllocationComponent.INTEREST, new BigDecimal("30.00"),
                AllocationComponent.PRINCIPAL, new BigDecimal("100.00"));
        List<AllocationComponent> priority = List.of(
                AllocationComponent.PENALTY, AllocationComponent.FEE, AllocationComponent.INTEREST, AllocationComponent.PRINCIPAL);
        AllocationResult partial = ServicingCalculator.allocate(priority, due, new BigDecimal("40.00"), 2, ROUNDING);
        assertThat(amount(partial, AllocationComponent.INTEREST)).isEqualByComparingTo("30.00");
        assertThat(amount(partial, AllocationComponent.PRINCIPAL)).isEqualByComparingTo("10.00");
        assertThat(partial.unapplied()).isEqualByComparingTo("0.00");
        AllocationResult full = ServicingCalculator.allocate(priority, due, new BigDecimal("130.00"), 2, ROUNDING);
        assertThat(full.unapplied()).isEqualByComparingTo("0.00");
        AllocationResult over = ServicingCalculator.allocate(priority, due, new BigDecimal("150.00"), 2, ROUNDING);
        assertThat(amount(over, AllocationComponent.PRINCIPAL)).isEqualByComparingTo("100.00");
        assertThat(over.unapplied()).isEqualByComparingTo("20.00");
    }

    @Test
    void penaltyStaysZeroThroughTheGraceDateAndEarlySettlementUsesEarnedInterestOnly() {
        LocalDate due = LocalDate.of(2026, 2, 15);
        assertThat(ServicingCalculator.penaltyTotal(penalty(due, due.plusDays(5)))).isEqualByComparingTo("0.00");
        assertThat(ServicingCalculator.penaltyTotal(penalty(due, due.plusDays(6)))).isEqualByComparingTo("25.00");

        ScheduleQuote quote = ServicingCalculator.schedule(request(
                "1200.00", "10", InterestMethod.FLAT, LocalDate.of(2026, 1, 15), 12, 0, HolidayRule.NONE, null));
        List<DailySlice> slices = ServicingCalculator.slices(LocalDate.of(2026, 1, 15), quote.rows(), 2, ROUNDING);
        LocalDate asOf = LocalDate.of(2026, 1, 25);
        BigDecimal earned = slices.stream().filter(slice -> !slice.date().isAfter(asOf)).map(DailySlice::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal piece = new BigDecimal("10.00").divide(BigDecimal.valueOf(31), 2, RoundingMode.DOWN);
        assertThat(earned).isEqualByComparingTo(piece.multiply(BigDecimal.TEN));
        BigDecimal fee = new BigDecimal("1200.00").multiply(new BigDecimal("2")).divide(new BigDecimal("100"), 2, ROUNDING);
        SettlementQuote settlement = ServicingCalculator.settlement(new ServicingCalculator.SettlementInput(
                new BigDecimal("1200.00"), BigDecimal.ZERO, sum(quote, false), BigDecimal.ZERO, BigDecimal.ZERO, slices,
                LocalDate.of(2026, 1, 15), quote.rows().get(quote.rows().size() - 1).dueDate(), asOf,
                EarlyRepaymentRule.ALLOWED_WITH_FEE, new BigDecimal("2"), 2, ROUNDING, true));
        assertThat(settlement.earlyFee()).isEqualByComparingTo(fee);
        assertThat(settlement.accruedInterest()).isEqualByComparingTo(earned);
        assertThat(settlement.unearnedInterest()).isEqualByComparingTo(sum(quote, false).subtract(earned));
        assertThat(settlement.totalDue()).isEqualByComparingTo(new BigDecimal("1200.00").add(earned).add(fee));
        assertThatThrownBy(() -> ServicingCalculator.settlement(new ServicingCalculator.SettlementInput(
                new BigDecimal("1200.00"), BigDecimal.ZERO, sum(quote, false), BigDecimal.ZERO, BigDecimal.ZERO, slices,
                LocalDate.of(2026, 1, 15), quote.rows().get(quote.rows().size() - 1).dueDate(), asOf,
                EarlyRepaymentRule.NOT_ALLOWED, BigDecimal.ZERO, 2, ROUNDING, true)))
                .isInstanceOf(ValidationException.class);
    }

    private static ServicingCalculator.PenaltyRequest penalty(LocalDate due, LocalDate asOf) {
        return new ServicingCalculator.PenaltyRequest(
                PenaltyBasis.FLAT_AMOUNT, new BigDecimal("100.00"), new BigDecimal("110.00"), new BigDecimal("25.00"),
                BigDecimal.ZERO, due, 5, asOf, DayCountConvention.ACTUAL_365, 2, ROUNDING, true);
    }

    private static BigDecimal amount(AllocationResult result, AllocationComponent component) {
        return result.slices().stream().filter(slice -> slice.component() == component)
                .map(ServicingCalculator.AllocationSlice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal total(List<DailySlice> slices) {
        return slices.stream().map(DailySlice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal sum(ScheduleQuote quote, boolean principal) {
        return quote.rows().stream().map(principal ? ScheduleRow::principalDue : ScheduleRow::interestDue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static ScheduleRequest request(
            String principal,
            String rate,
            InterestMethod method,
            LocalDate disbursement,
            int tenure,
            int grace,
            HolidayRule holiday,
            Integer repaymentDay) {
        return new ScheduleRequest(
                new BigDecimal(principal), new BigDecimal(rate), method, InterestPeriod.ANNUAL, tenure, TenureUnit.MONTHS,
                RepaymentFrequency.MONTHLY, DayCountConvention.ACTUAL_365, 2, ROUNDING, grace, disbursement,
                BigDecimal.ZERO, List.of(), repaymentDay, holiday, List.of());
    }
}
