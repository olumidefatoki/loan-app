package com.mfb.lending.module.loancalculation;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.loanproduct.domain.DayCountConvention;
import com.mfb.lending.module.loanproduct.domain.FeeCalculation;
import com.mfb.lending.module.loanproduct.domain.InterestMethod;
import com.mfb.lending.module.loanproduct.domain.InterestPeriod;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;

/**
 * The only loan interest and instalment calculator in this service.
 * Offer generation and any later booking must call this engine with the same inputs.
 */
public final class LoanCalculationEngine {

    public static final String VERSION = "LOAN_CALC_V1";

    private static final MathContext MATH = new MathContext(24, RoundingMode.HALF_EVEN);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private LoanCalculationEngine() {
    }

    public static LoanQuote quote(LoanQuoteRequest request) {
        int scale = request.scale();
        RoundingMode rounding = request.rounding();
        if (scale < 0 || scale > 6) {
            throw ValidationException.field("roundingPrecision", "Rounding precision must be between 0 and 6");
        }
        if (request.principal() == null || request.principal().signum() <= 0) {
            throw ValidationException.field("principal", "Principal must be greater than zero");
        }
        if (request.interestRatePercent() == null
                || request.interestRatePercent().signum() < 0
                || request.interestRatePercent().compareTo(HUNDRED) > 0) {
            throw ValidationException.field("interestRate", "Interest rate must be between 0 and 100");
        }
        if (request.taxRatePercent() == null
                || request.taxRatePercent().signum() < 0
                || request.taxRatePercent().compareTo(HUNDRED) > 0) {
            throw ValidationException.field("taxRate", "Tax rate must be between 0 and 100");
        }
        if (request.disbursementDate() == null) {
            throw ValidationException.field("disbursementDate", "Disbursement date is required");
        }
        if (request.gracePeriodDays() < 0) {
            throw ValidationException.field("gracePeriodDays", "Grace period cannot be negative");
        }
        BigDecimal principal = money(request.principal(), scale, rounding);
        int instalments = instalmentCount(request.tenure(), request.tenureUnit(), request.frequency());
        int periodsPerYear = periodsPerYear(request.frequency(), request.dayCount());
        BigDecimal periodicRate = periodicRate(request.interestRatePercent(), request.interestPeriod(), periodsPerYear);
        List<CalculatedInstalment> schedule = schedule(
                principal, periodicRate, instalments, request.interestMethod(), request.frequency(),
                request.disbursementDate(), request.gracePeriodDays(), scale, rounding);
        BigDecimal totalInterest = schedule.stream().map(CalculatedInstalment::interestDue).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalRepayment = schedule.stream().map(CalculatedInstalment::instalmentAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<CalculatedFee> fees = fees(request.fees(), principal, scale, rounding);
        BigDecimal processing = sum(fees, "PROCESSING");
        BigDecimal other = fees.stream()
                .filter(fee -> !"PROCESSING".equals(fee.type()))
                .map(CalculatedFee::charged)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal taxable = processing.add(other);
        BigDecimal taxes = money(taxable.multiply(request.taxRatePercent()).divide(HUNDRED, MATH), scale, rounding);
        BigDecimal net = principal.subtract(taxable).subtract(taxes);
        if (net.signum() < 0) {
            throw ValidationException.field("principal", "Fees and taxes exceed the approved principal");
        }
        LoanQuote quote = new LoanQuote(
                VERSION,
                null,
                principal,
                request.interestRatePercent().setScale(4, RoundingMode.HALF_UP),
                request.interestMethod(),
                request.interestPeriod(),
                request.tenure(),
                request.tenureUnit(),
                request.frequency(),
                request.dayCount(),
                scale,
                rounding,
                request.gracePeriodDays(),
                request.disbursementDate(),
                request.taxRatePercent().setScale(4, RoundingMode.HALF_UP),
                instalments,
                schedule.get(0).instalmentAmount(),
                processing,
                other,
                taxes,
                totalInterest,
                totalRepayment,
                net,
                List.copyOf(fees),
                List.copyOf(schedule));
        return quote.withFingerprint(fingerprint(quote));
    }

    private static List<CalculatedInstalment> schedule(
            BigDecimal principal,
            BigDecimal periodicRate,
            int instalments,
            InterestMethod method,
            RepaymentFrequency frequency,
            LocalDate disbursementDate,
            int gracePeriodDays,
            int scale,
            RoundingMode rounding) {
        return method == InterestMethod.FLAT
                ? flat(principal, periodicRate, instalments, frequency, disbursementDate, gracePeriodDays, scale, rounding)
                : reducing(principal, periodicRate, instalments, frequency, disbursementDate, gracePeriodDays, scale, rounding);
    }

    private static List<CalculatedInstalment> flat(
            BigDecimal principal,
            BigDecimal periodicRate,
            int instalments,
            RepaymentFrequency frequency,
            LocalDate disbursementDate,
            int gracePeriodDays,
            int scale,
            RoundingMode rounding) {
        BigDecimal totalInterest = money(principal.multiply(periodicRate).multiply(BigDecimal.valueOf(instalments), MATH), scale, rounding);
        List<BigDecimal> principalParts = split(principal, instalments, scale, rounding);
        List<BigDecimal> interestParts = split(totalInterest, instalments, scale, rounding);
        List<CalculatedInstalment> rows = new ArrayList<>();
        BigDecimal opening = principal;
        for (int number = 1; number <= instalments; number++) {
            BigDecimal principalDue = principalParts.get(number - 1);
            BigDecimal interestDue = interestParts.get(number - 1);
            BigDecimal payment = principalDue.add(interestDue);
            BigDecimal closing = opening.subtract(principalDue);
            rows.add(new CalculatedInstalment(
                    number,
                    dueDate(disbursementDate, gracePeriodDays, frequency, number),
                    opening,
                    principalDue,
                    interestDue,
                    payment,
                    closing));
            opening = closing;
        }
        return rows;
    }

    private static List<CalculatedInstalment> reducing(
            BigDecimal principal,
            BigDecimal periodicRate,
            int instalments,
            RepaymentFrequency frequency,
            LocalDate disbursementDate,
            int gracePeriodDays,
            int scale,
            RoundingMode rounding) {
        BigDecimal regular = regularInstalment(principal, periodicRate, instalments, scale, rounding);
        List<CalculatedInstalment> rows = new ArrayList<>();
        BigDecimal opening = principal;
        BigDecimal zero = BigDecimal.ZERO.setScale(scale, rounding);
        for (int number = 1; number <= instalments; number++) {
            boolean last = number == instalments;
            BigDecimal interestDue = money(opening.multiply(periodicRate, MATH), scale, rounding);
            BigDecimal principalDue;
            BigDecimal payment;
            if (last) {
                principalDue = opening;
                payment = principalDue.add(interestDue);
            } else {
                payment = regular;
                principalDue = payment.subtract(interestDue);
                if (principalDue.compareTo(opening) > 0) {
                    principalDue = opening;
                    payment = principalDue.add(interestDue);
                }
                if (principalDue.signum() < 0) {
                    principalDue = zero;
                    payment = interestDue;
                }
            }
            BigDecimal closing = opening.subtract(principalDue);
            rows.add(new CalculatedInstalment(
                    number,
                    dueDate(disbursementDate, gracePeriodDays, frequency, number),
                    opening,
                    principalDue,
                    interestDue,
                    payment,
                    closing));
            opening = closing;
        }
        return rows;
    }

    private static BigDecimal regularInstalment(
            BigDecimal principal,
            BigDecimal periodicRate,
            int instalments,
            int scale,
            RoundingMode rounding) {
        if (periodicRate.signum() == 0) {
            return money(principal.divide(BigDecimal.valueOf(instalments), MATH), scale, rounding);
        }
        BigDecimal onePlus = BigDecimal.ONE.add(periodicRate);
        BigDecimal factor = onePlus.pow(instalments, MATH);
        BigDecimal raw = principal.multiply(periodicRate, MATH).multiply(factor, MATH)
                .divide(factor.subtract(BigDecimal.ONE), MATH);
        return raw.setScale(scale, rounding);
    }

    private static List<CalculatedFee> fees(List<FeeInput> inputs, BigDecimal principal, int scale, RoundingMode rounding) {
        if (inputs == null || inputs.isEmpty()) {
            return List.of();
        }
        List<FeeInput> ordered = inputs.stream()
                .sorted(Comparator.comparing(FeeInput::type).thenComparing(FeeInput::name))
                .toList();
        List<CalculatedFee> fees = new ArrayList<>();
        for (FeeInput input : ordered) {
            BigDecimal charged = switch (input.calculation()) {
                case FLAT_AMOUNT -> {
                    if (input.amount() == null || input.amount().signum() < 0) {
                        throw ValidationException.field("fees", "A flat fee needs an amount of zero or more");
                    }
                    yield money(input.amount(), scale, rounding);
                }
                case PERCENTAGE -> {
                    if (input.rate() == null || input.rate().signum() < 0 || input.rate().compareTo(HUNDRED) > 0) {
                        throw ValidationException.field("fees", "A percentage fee rate must be between 0 and 100");
                    }
                    yield money(principal.multiply(input.rate()).divide(HUNDRED, MATH), scale, rounding);
                }
            };
            fees.add(new CalculatedFee(
                    input.type(),
                    input.name(),
                    input.calculation(),
                    input.amount() == null ? null : money(input.amount(), scale, rounding),
                    input.rate() == null ? null : input.rate().setScale(4, RoundingMode.HALF_UP),
                    charged));
        }
        return fees;
    }

    private static BigDecimal sum(List<CalculatedFee> fees, String type) {
        return fees.stream()
                .filter(fee -> type.equals(fee.type()))
                .map(CalculatedFee::charged)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<BigDecimal> split(BigDecimal total, int parts, int scale, RoundingMode rounding) {
        BigDecimal each = total.divide(BigDecimal.valueOf(parts), scale, rounding);
        List<BigDecimal> values = new ArrayList<>();
        BigDecimal allocated = BigDecimal.ZERO.setScale(scale, rounding);
        for (int index = 0; index < parts - 1; index++) {
            values.add(each);
            allocated = allocated.add(each);
        }
        values.add(total.setScale(scale, rounding).subtract(allocated));
        return values;
    }

    private static BigDecimal periodicRate(BigDecimal annualOrMonthlyPercent, InterestPeriod period, int periodsPerYear) {
        BigDecimal nominal = annualOrMonthlyPercent.divide(HUNDRED, MATH);
        BigDecimal periods = BigDecimal.valueOf(periodsPerYear);
        if (period == InterestPeriod.ANNUAL) {
            return nominal.divide(periods, MATH);
        }
        return nominal.multiply(BigDecimal.valueOf(12), MATH).divide(periods, MATH);
    }

    private static int periodsPerYear(RepaymentFrequency frequency, DayCountConvention dayCount) {
        return switch (frequency) {
            case MONTHLY -> 12;
            case WEEKLY -> 52;
            case DAILY -> dayCount == DayCountConvention.ACTUAL_365 ? 365 : 360;
        };
    }

    private static int instalmentCount(int tenure, TenureUnit unit, RepaymentFrequency frequency) {
        boolean aligned = (unit == TenureUnit.MONTHS && frequency == RepaymentFrequency.MONTHLY)
                || (unit == TenureUnit.WEEKS && frequency == RepaymentFrequency.WEEKLY)
                || (unit == TenureUnit.DAYS && frequency == RepaymentFrequency.DAILY);
        if (!aligned) {
            throw ValidationException.field("tenure", "Tenure unit must match the repayment frequency");
        }
        if (tenure < 1) {
            throw ValidationException.field("tenure", "Tenure must be at least 1");
        }
        return tenure;
    }

    private static LocalDate dueDate(LocalDate disbursementDate, int gracePeriodDays, RepaymentFrequency frequency, int number) {
        LocalDate anchor = disbursementDate.plusDays(gracePeriodDays);
        return switch (frequency) {
            case MONTHLY -> anchor.plusMonths(number);
            case WEEKLY -> anchor.plusWeeks(number);
            case DAILY -> anchor.plusDays(number);
        };
    }

    private static BigDecimal money(BigDecimal value, int scale, RoundingMode rounding) {
        return value.setScale(scale, rounding);
    }

    private static String fingerprint(LoanQuote quote) {
        StringBuilder canonical = new StringBuilder();
        canonical.append(VERSION)
                .append("|principal=").append(quote.principal().toPlainString())
                .append("|rate=").append(quote.interestRatePercent().toPlainString())
                .append("|method=").append(quote.interestMethod().name())
                .append("|period=").append(quote.interestPeriod().name())
                .append("|tenure=").append(quote.tenure())
                .append("|unit=").append(quote.tenureUnit().name())
                .append("|frequency=").append(quote.frequency().name())
                .append("|dayCount=").append(quote.dayCount().name())
                .append("|scale=").append(quote.scale())
                .append("|rounding=").append(quote.rounding().name())
                .append("|grace=").append(quote.gracePeriodDays())
                .append("|disbursement=").append(quote.disbursementDate())
                .append("|tax=").append(quote.taxRatePercent().toPlainString())
                .append("|instalment=").append(quote.instalmentAmount().toPlainString())
                .append("|interest=").append(quote.totalInterest().toPlainString())
                .append("|repayment=").append(quote.totalRepayment().toPlainString())
                .append("|taxes=").append(quote.taxes().toPlainString());
        for (CalculatedFee fee : quote.fees()) {
            canonical.append("|fee=")
                    .append(fee.type()).append(':')
                    .append(fee.name()).append(':')
                    .append(fee.calculation().name()).append(':')
                    .append(fee.amount() == null ? "" : fee.amount().toPlainString()).append(':')
                    .append(fee.rate() == null ? "" : fee.rate().toPlainString()).append(':')
                    .append(fee.charged().toPlainString());
        }
        return sha256(canonical.toString());
    }

    public static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public record FeeInput(String type, String name, FeeCalculation calculation, BigDecimal amount, BigDecimal rate) {
    }

    public record LoanQuoteRequest(
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
            List<FeeInput> fees
    ) {
    }

    public record CalculatedFee(
            String type,
            String name,
            FeeCalculation calculation,
            BigDecimal amount,
            BigDecimal rate,
            BigDecimal charged
    ) {
    }

    public record CalculatedInstalment(
            int number,
            LocalDate dueDate,
            BigDecimal openingBalance,
            BigDecimal principalDue,
            BigDecimal interestDue,
            BigDecimal instalmentAmount,
            BigDecimal closingBalance
    ) {
    }

    public record LoanQuote(
            String engineVersion,
            String fingerprint,
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
            int instalmentCount,
            BigDecimal instalmentAmount,
            BigDecimal processingFees,
            BigDecimal otherFees,
            BigDecimal taxes,
            BigDecimal totalInterest,
            BigDecimal totalRepayment,
            BigDecimal netDisbursement,
            List<CalculatedFee> fees,
            List<CalculatedInstalment> schedule
    ) {
        private LoanQuote withFingerprint(String value) {
            return new LoanQuote(
                    engineVersion, value, principal, interestRatePercent, interestMethod, interestPeriod, tenure,
                    tenureUnit, frequency, dayCount, scale, rounding, gracePeriodDays, disbursementDate, taxRatePercent,
                    instalmentCount, instalmentAmount, processingFees, otherFees, taxes, totalInterest, totalRepayment,
                    netDisbursement, fees, schedule);
        }
    }
}
