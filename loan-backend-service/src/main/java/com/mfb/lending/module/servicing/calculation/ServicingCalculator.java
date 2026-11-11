package com.mfb.lending.module.servicing.calculation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.CalculatedInstalment;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.LoanQuote;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.LoanQuoteRequest;
import com.mfb.lending.module.loanproduct.domain.DayCountConvention;
import com.mfb.lending.module.loanproduct.domain.EarlyRepaymentRule;
import com.mfb.lending.module.loanproduct.domain.InterestMethod;
import com.mfb.lending.module.loanproduct.domain.InterestPeriod;
import com.mfb.lending.module.loanproduct.domain.PenaltyBasis;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;

/**
 * Servicing maths for accrual, penalties, settlement, and allocation.
 * Contractual instalments are produced by {@link LoanCalculationEngine}. This class does not
 * reprice those instalments. It recognises each instalment's interest across the actual days in
 * the period, including 29 February, and the daily pieces add back to the contractual interest.
 * The class has no repository or controller dependency. Monetary values use {@link BigDecimal}.
 */
public final class ServicingCalculator {

    public static final String VERSION = "SERVICING_CALC_V1";

    private static final MathContext MATH = new MathContext(24, RoundingMode.HALF_EVEN);
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private static final BigDecimal YEAR_365 = new BigDecimal("365");
    private static final BigDecimal YEAR_360 = new BigDecimal("360");

    private ServicingCalculator() {
    }

    public static ScheduleQuote schedule(ScheduleRequest request) {
        validateSchedule(request);
        LoanQuote quote = LoanCalculationEngine.quote(new LoanQuoteRequest(
                request.principal(),
                request.interestRatePercent(),
                request.interestMethod(),
                request.interestPeriod(),
                request.tenure(),
                request.tenureUnit(),
                request.frequency(),
                request.dayCount(),
                request.scale(),
                request.rounding(),
                request.gracePeriodDays(),
                request.disbursementDate(),
                request.taxRatePercent(),
                request.fees()));
        Set<LocalDate> holidays = holidays(request.holidays());
        List<ScheduleRow> rows = new ArrayList<>();
        LocalDate previous = null;
        for (CalculatedInstalment instalment : quote.schedule()) {
            LocalDate contractual = instalment.dueDate();
            LocalDate due = align(contractual, request.repaymentDayOfMonth(), request.frequency());
            due = adjust(due, request.holidayRule(), holidays);
            due = after(previous, due, request.holidayRule(), holidays);
            rows.add(new ScheduleRow(
                    instalment.number(),
                    contractual,
                    due,
                    instalment.openingBalance(),
                    instalment.principalDue(),
                    instalment.interestDue(),
                    instalment.instalmentAmount(),
                    instalment.closingBalance()));
            previous = due;
        }
        return new ScheduleQuote(quote, List.copyOf(rows));
    }

    public static List<DailySlice> slices(LocalDate accrualStart, List<ScheduleRow> rows, int scale, RoundingMode rounding) {
        if (accrualStart == null) {
            throw ValidationException.field("accrualStart", "Accrual start is required");
        }
        List<DailySlice> slices = new ArrayList<>();
        LocalDate start = accrualStart;
        for (ScheduleRow row : rows) {
            slices.addAll(split(row.number(), start, row.dueDate(), row.interestDue(), scale, rounding));
            start = row.dueDate();
        }
        return List.copyOf(slices);
    }

    public static List<RecognisedInterest> recognise(
            List<DailySlice> slices,
            LocalDate after,
            LocalDate through,
            AccrualFrequency frequency) {
        if (after == null || through == null) {
            throw ValidationException.field("valueDate", "An accrual range is required");
        }
        if (through.isBefore(after)) {
            return List.of();
        }
        List<DailySlice> selected = slices.stream()
                .filter(slice -> slice.date().isAfter(after) && !slice.date().isAfter(through))
                .toList();
        if (frequency == AccrualFrequency.DAILY) {
            return selected.stream()
                    .map(slice -> new RecognisedInterest(slice.date(), slice.amount(), slice.instalmentNumber()))
                    .toList();
        }
        Map<YearMonth, List<DailySlice>> grouped = new LinkedHashMap<>();
        for (DailySlice slice : selected) {
            grouped.computeIfAbsent(YearMonth.from(slice.date()), ignored -> new ArrayList<>()).add(slice);
        }
        List<RecognisedInterest> monthly = new ArrayList<>();
        for (List<DailySlice> month : grouped.values()) {
            BigDecimal amount = month.stream().map(DailySlice::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
            monthly.add(new RecognisedInterest(month.get(month.size() - 1).date(), amount, null));
        }
        return List.copyOf(monthly);
    }

    public static BigDecimal yearFraction(LocalDate start, LocalDate end, DayCountConvention convention) {
        if (start == null || end == null || convention == null) {
            throw ValidationException.field("dayCount", "A day-count range is required");
        }
        if (!end.isAfter(start)) {
            return BigDecimal.ZERO;
        }
        return switch (convention) {
            case ACTUAL_365 -> BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end)).divide(YEAR_365, MATH);
            case ACTUAL_360 -> BigDecimal.valueOf(ChronoUnit.DAYS.between(start, end)).divide(YEAR_360, MATH);
            case THIRTY_360 -> BigDecimal.valueOf(thirty360Days(start, end)).divide(YEAR_360, MATH);
        };
    }

    /**
     * US 30/360. A 31st becomes the 30th, and the end day becomes the 30th when the start day is the 30th.
     * February is not given an extra leap day beyond the day numbers in the formula.
     */
    public static int thirty360Days(LocalDate start, LocalDate end) {
        int startDay = Math.min(start.getDayOfMonth(), 30);
        int endDay = end.getDayOfMonth();
        if (endDay == 31 && startDay == 30) {
            endDay = 30;
        }
        return 360 * (end.getYear() - start.getYear())
                + 30 * (end.getMonthValue() - start.getMonthValue())
                + (endDay - startDay);
    }

    public static LocalDate adjust(LocalDate date, HolidayRule rule, Collection<LocalDate> holidays) {
        if (date == null) {
            throw ValidationException.field("dueDate", "A repayment date is required");
        }
        HolidayRule selected = rule == null ? HolidayRule.NONE : rule;
        if (selected == HolidayRule.NONE || !closed(date, holidays(holidays))) {
            return date;
        }
        if (selected == HolidayRule.PREVIOUS_BUSINESS_DAY) {
            return previousOpen(date, holidays(holidays));
        }
        LocalDate next = nextOpen(date, holidays(holidays));
        if (selected == HolidayRule.MODIFIED_FOLLOWING && next.getMonthValue() != date.getMonthValue()) {
            return previousOpen(date, holidays(holidays));
        }
        return next;
    }

    public static BigDecimal penaltyTotal(PenaltyRequest request) {
        int scale = request.scale();
        RoundingMode rounding = request.rounding();
        BigDecimal zero = zero(scale);
        if (!request.open() || request.basis() == null || request.basis() == PenaltyBasis.NONE) {
            return zero;
        }
        LocalDate start = request.dueDate().plusDays(request.graceDays());
        if (!request.asOf().isAfter(start)) {
            return zero;
        }
        if (request.basis() == PenaltyBasis.FLAT_AMOUNT) {
            return money(request.flatAmount(), scale, rounding);
        }
        BigDecimal base = request.basis() == PenaltyBasis.PERCENTAGE_OF_INSTALMENT
                ? request.instalmentBase()
                : request.overdueBase();
        if (base == null || base.signum() <= 0) {
            return zero;
        }
        BigDecimal fraction = yearFraction(start, request.asOf(), request.dayCount());
        BigDecimal raw = base.multiply(request.ratePercent(), MATH).divide(HUNDRED, MATH).multiply(fraction, MATH);
        return raw.setScale(scale, rounding);
    }

    public static SettlementQuote settlement(SettlementInput input) {
        int scale = input.scale();
        RoundingMode rounding = input.rounding();
        if (input.asOf().isBefore(input.disbursementDate())) {
            throw ValidationException.field("valueDate", "Settlement cannot be before disbursement");
        }
        BigDecimal earned = input.slices().stream()
                .filter(slice -> !slice.date().isAfter(input.asOf()))
                .map(DailySlice::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(scale, rounding);
        BigDecimal unpaid = earned.subtract(money(input.interestPaid(), scale, rounding));
        BigDecimal credit = zero(scale);
        if (unpaid.signum() < 0) {
            credit = unpaid.negate();
            unpaid = zero(scale);
        }
        boolean beforeMaturity = input.asOf().isBefore(input.maturityDate());
        if (input.enforceEarlySettlementRule()
                && input.rule() == EarlyRepaymentRule.NOT_ALLOWED
                && beforeMaturity
                && input.principal().signum() > 0) {
            throw ValidationException.field("valueDate", "Early settlement is not allowed for this loan");
        }
        BigDecimal earlyFee = zero(scale);
        if (input.rule() == EarlyRepaymentRule.ALLOWED_WITH_FEE && beforeMaturity && input.principal().signum() > 0) {
            if (input.earlyFeeRate() == null) {
                throw ValidationException.field("earlyRepaymentFeeRate", "An early settlement fee rate is required");
            }
            earlyFee = input.principal().multiply(input.earlyFeeRate(), MATH).divide(HUNDRED, MATH).setScale(scale, rounding);
        }
        BigDecimal principal = money(input.principal(), scale, rounding);
        BigDecimal fees = money(input.fees(), scale, rounding);
        BigDecimal penalties = money(input.penalties(), scale, rounding);
        BigDecimal total = principal.add(unpaid).add(fees).add(penalties).add(earlyFee).subtract(credit);
        if (total.signum() < 0) {
            total = zero(scale);
        }
        BigDecimal unearned = money(input.contractualInterest(), scale, rounding).subtract(earned);
        if (unearned.signum() < 0) {
            unearned = zero(scale);
        }
        return new SettlementQuote(principal, unpaid, unearned, fees, penalties, earlyFee, credit, total, earned);
    }

    public static BigDecimal adjustedInterest(BigDecimal outstanding, BigDecimal delta, int scale, RoundingMode rounding) {
        BigDecimal next = money(outstanding, scale, rounding).add(money(delta, scale, rounding));
        if (next.signum() < 0) {
            throw ValidationException.field("amount", "An interest adjustment cannot take outstanding interest below zero");
        }
        return next;
    }

    public static AllocationResult allocate(
            List<AllocationComponent> priority,
            Map<AllocationComponent, BigDecimal> outstanding,
            BigDecimal amount,
            int scale,
            RoundingMode rounding) {
        if (priority == null || priority.isEmpty()) {
            throw ValidationException.field("allocationPolicy", "An allocation policy is required");
        }
        if (priority.stream().distinct().count() != priority.size()) {
            throw ValidationException.field("allocationPolicy", "An allocation component cannot be repeated");
        }
        BigDecimal remaining = money(amount, scale, rounding);
        if (remaining.signum() <= 0) {
            throw ValidationException.field("amount", "amount must be greater than zero");
        }
        List<AllocationSlice> slices = new ArrayList<>();
        for (AllocationComponent component : priority) {
            if (remaining.signum() == 0) {
                break;
            }
            BigDecimal due = money(outstanding.getOrDefault(component, BigDecimal.ZERO), scale, rounding);
            if (due.signum() <= 0) {
                continue;
            }
            BigDecimal applied = due.min(remaining);
            slices.add(new AllocationSlice(component, applied));
            remaining = remaining.subtract(applied);
        }
        return new AllocationResult(List.copyOf(slices), remaining);
    }

    public static BigDecimal duePrincipal(List<ScheduleRow> rows, Map<Integer, BigDecimal> principalPaid, LocalDate asOf, int scale, RoundingMode rounding) {
        BigDecimal due = zero(scale);
        for (ScheduleRow row : rows) {
            if (row.dueDate().isAfter(asOf)) {
                continue;
            }
            BigDecimal paid = money(principalPaid.getOrDefault(row.number(), BigDecimal.ZERO), scale, rounding);
            BigDecimal unpaid = row.principalDue().subtract(paid);
            if (unpaid.signum() > 0) {
                due = due.add(unpaid);
            }
        }
        return due;
    }

    public static String fingerprint(String canonical) {
        return LoanCalculationEngine.sha256(canonical);
    }

    private static List<DailySlice> split(int instalment, LocalDate start, LocalDate end, BigDecimal interest, int scale, RoundingMode rounding) {
        BigDecimal total = interest.setScale(scale, rounding);
        long days = ChronoUnit.DAYS.between(start, end);
        if (days <= 0) {
            if (total.signum() == 0) {
                return List.of();
            }
            return List.of(new DailySlice(instalment, end, total));
        }
        BigDecimal each = total.divide(BigDecimal.valueOf(days), scale, RoundingMode.DOWN);
        List<DailySlice> slices = new ArrayList<>();
        BigDecimal allocated = zero(scale);
        for (long offset = 1; offset < days; offset++) {
            if (each.signum() > 0) {
                slices.add(new DailySlice(instalment, start.plusDays(offset), each));
            }
            allocated = allocated.add(each);
        }
        BigDecimal last = total.subtract(allocated);
        if (last.signum() > 0) {
            slices.add(new DailySlice(instalment, end, last));
        }
        return slices;
    }

    private static LocalDate align(LocalDate date, Integer repaymentDay, RepaymentFrequency frequency) {
        if (repaymentDay == null || frequency != RepaymentFrequency.MONTHLY) {
            return date;
        }
        if (repaymentDay < 1 || repaymentDay > 31) {
            throw ValidationException.field("repaymentDayOfMonth", "Repayment day must be between 1 and 31");
        }
        return date.withDayOfMonth(Math.min(repaymentDay, date.lengthOfMonth()));
    }

    private static LocalDate after(LocalDate previous, LocalDate due, HolidayRule rule, Set<LocalDate> holidays) {
        if (previous == null || due.isAfter(previous)) {
            return due;
        }
        LocalDate cursor = previous;
        LocalDate adjusted;
        do {
            cursor = cursor.plusDays(1);
            adjusted = adjust(cursor, HolidayRule.NEXT_BUSINESS_DAY, holidays);
        } while (!adjusted.isAfter(previous));
        return adjusted;
    }

    private static boolean closed(LocalDate date, Set<LocalDate> holidays) {
        DayOfWeek day = date.getDayOfWeek();
        return day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY || holidays.contains(date);
    }

    private static LocalDate nextOpen(LocalDate date, Set<LocalDate> holidays) {
        LocalDate cursor = date;
        do {
            cursor = cursor.plusDays(1);
        } while (closed(cursor, holidays));
        return cursor;
    }

    private static LocalDate previousOpen(LocalDate date, Set<LocalDate> holidays) {
        LocalDate cursor = date;
        do {
            cursor = cursor.minusDays(1);
        } while (closed(cursor, holidays));
        return cursor;
    }

    private static Set<LocalDate> holidays(Collection<LocalDate> holidays) {
        return holidays == null ? Set.of() : Set.copyOf(new TreeSet<>(holidays));
    }

    private static void validateSchedule(ScheduleRequest request) {
        if (request.scale() < 0 || request.scale() > 6) {
            throw ValidationException.field("roundingPrecision", "Rounding precision must be between 0 and 6");
        }
        if (request.gracePeriodDays() < 0) {
            throw ValidationException.field("gracePeriodDays", "Grace period cannot be negative");
        }
        if (request.taxRatePercent() == null) {
            throw ValidationException.field("taxRate", "Tax rate is required");
        }
    }

    public static BigDecimal money(BigDecimal value, int scale, RoundingMode rounding) {
        if (value == null) {
            return zero(scale);
        }
        return value.setScale(scale, rounding);
    }

    public static BigDecimal zero(int scale) {
        return BigDecimal.ZERO.setScale(scale, RoundingMode.UNNECESSARY);
    }

    public record ScheduleRequest(
            BigDecimal principal,
            BigDecimal interestRatePercent,
            InterestMethod interestMethod,
            InterestPeriod interestPeriod,
            int tenure,
            TenureUnit tenureUnit,
            RepaymentFrequency frequency,
            DayCountConvention dayCount,
            int scale,
            RoundingMode rounding,
            int gracePeriodDays,
            LocalDate disbursementDate,
            BigDecimal taxRatePercent,
            List<LoanCalculationEngine.FeeInput> fees,
            Integer repaymentDayOfMonth,
            HolidayRule holidayRule,
            List<LocalDate> holidays
    ) {
    }

    public record ScheduleRow(
            int number,
            LocalDate contractualDueDate,
            LocalDate dueDate,
            BigDecimal openingBalance,
            BigDecimal principalDue,
            BigDecimal interestDue,
            BigDecimal instalmentAmount,
            BigDecimal closingBalance
    ) {
    }

    public record ScheduleQuote(LoanQuote quote, List<ScheduleRow> rows) {
    }

    public record DailySlice(int instalmentNumber, LocalDate date, BigDecimal amount) {
    }

    public record RecognisedInterest(LocalDate date, BigDecimal amount, Integer instalmentNumber) {
    }

    public record PenaltyRequest(
            PenaltyBasis basis,
            BigDecimal overdueBase,
            BigDecimal instalmentBase,
            BigDecimal flatAmount,
            BigDecimal ratePercent,
            LocalDate dueDate,
            int graceDays,
            LocalDate asOf,
            DayCountConvention dayCount,
            int scale,
            RoundingMode rounding,
            boolean open
    ) {
    }

    public record SettlementInput(
            BigDecimal principal,
            BigDecimal interestPaid,
            BigDecimal contractualInterest,
            BigDecimal fees,
            BigDecimal penalties,
            List<DailySlice> slices,
            LocalDate disbursementDate,
            LocalDate maturityDate,
            LocalDate asOf,
            EarlyRepaymentRule rule,
            BigDecimal earlyFeeRate,
            int scale,
            RoundingMode rounding,
            boolean enforceEarlySettlementRule
    ) {
    }

    public record SettlementQuote(
            BigDecimal principal,
            BigDecimal accruedInterest,
            BigDecimal unearnedInterest,
            BigDecimal fees,
            BigDecimal penalties,
            BigDecimal earlyFee,
            BigDecimal interestCredit,
            BigDecimal totalDue,
            BigDecimal earnedInterest
    ) {
    }

    public record AllocationSlice(AllocationComponent component, BigDecimal amount) {
    }

    public record AllocationResult(List<AllocationSlice> slices, BigDecimal unapplied) {
    }
}
