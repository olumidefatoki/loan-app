package com.mfb.lending.module.reporting.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public final class ReportingCalculator {

    public static final int PAR_30 = 30;
    public static final int PAR_60 = 60;
    public static final int PAR_90 = 90;
    public static final int LEDGER_SCALE = 6;
    public static final int RATE_SCALE = 6;
    public static final int DAY_SCALE = 2;

    private ReportingCalculator() {
    }

    public static BigDecimal ledger(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(LEDGER_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal kobo(BigDecimal value) {
        return ledger(value).setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal ratio(BigDecimal part, BigDecimal whole) {
        BigDecimal denominator = ledger(whole);
        if (denominator.signum() == 0) {
            return BigDecimal.ZERO.setScale(RATE_SCALE, RoundingMode.HALF_UP);
        }
        return ledger(part).divide(denominator, RATE_SCALE, RoundingMode.HALF_UP);
    }

    public static Rate definedRate(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.signum() == 0) {
            return Rate.undefined();
        }
        return new Rate(ledger(numerator).divide(ledger(denominator), RATE_SCALE, RoundingMode.HALF_UP), true);
    }

    public static Rate decisionRate(long selected, long approved, long rejected) {
        long decided = approved + rejected;
        if (decided == 0) {
            return Rate.undefined();
        }
        return new Rate(BigDecimal.valueOf(selected).divide(BigDecimal.valueOf(decided), RATE_SCALE, RoundingMode.HALF_UP), true);
    }

    public static BigDecimal averageLoan(BigDecimal disbursedPrincipal, long disbursedLoans) {
        if (disbursedLoans <= 0) {
            return BigDecimal.ZERO.setScale(LEDGER_SCALE, RoundingMode.HALF_UP);
        }
        return ledger(disbursedPrincipal).divide(BigDecimal.valueOf(disbursedLoans), LEDGER_SCALE, RoundingMode.HALF_UP);
    }

    public static BigDecimal variance(BigDecimal subledger, BigDecimal generalLedger) {
        return ledger(subledger).subtract(ledger(generalLedger));
    }

    public static BigDecimal turnaroundDays(BigDecimal averageDays) {
        if (averageDays == null) {
            return null;
        }
        return averageDays.setScale(DAY_SCALE, RoundingMode.HALF_UP);
    }

    public static boolean inPar(int daysPastDue, int threshold) {
        return daysPastDue >= threshold;
    }

    public static PortfolioMath summarise(List<Exposure> loans) {
        BigDecimal denominator = BigDecimal.ZERO;
        BigDecimal par30 = BigDecimal.ZERO;
        BigDecimal par60 = BigDecimal.ZERO;
        BigDecimal par90 = BigDecimal.ZERO;
        BigDecimal overdue = BigDecimal.ZERO;
        long count = 0;
        long par30Count = 0;
        long par60Count = 0;
        long par90Count = 0;
        long overdueCount = 0;
        for (Exposure loan : loans) {
            if (loan.principal() == null || loan.principal().signum() <= 0) {
                continue;
            }
            count++;
            denominator = denominator.add(loan.principal());
            if (loan.daysPastDue() >= 1) {
                overdue = overdue.add(loan.principal());
                overdueCount++;
            }
            if (inPar(loan.daysPastDue(), PAR_30)) {
                par30 = par30.add(loan.principal());
                par30Count++;
            }
            if (inPar(loan.daysPastDue(), PAR_60)) {
                par60 = par60.add(loan.principal());
                par60Count++;
            }
            if (inPar(loan.daysPastDue(), PAR_90)) {
                par90 = par90.add(loan.principal());
                par90Count++;
            }
        }
        return new PortfolioMath(count, ledger(denominator), ledger(overdue), overdueCount,
                ledger(par30), par30Count, ledger(par60), par60Count, ledger(par90), par90Count,
                ratio(par30, denominator), ratio(par60, denominator), ratio(par90, denominator), ratio(par90, denominator));
    }

    public static String principalAtLeast(String amount, String days, int threshold) {
        return "COALESCE(SUM(" + amount + ") FILTER (WHERE " + days + " >= " + threshold + "), 0)";
    }

    public static boolean recoveryException(String repaymentStatus) {
        return repaymentStatus == null || !"POSTED".equals(repaymentStatus);
    }

    public record Rate(BigDecimal value, boolean defined) {
        public static Rate undefined() {
            return new Rate(null, false);
        }
    }

    public record Exposure(BigDecimal principal, int daysPastDue) {
    }

    public record PortfolioMath(
            long loanCount,
            BigDecimal outstandingPrincipal,
            BigDecimal overduePrincipal,
            long overdueCount,
            BigDecimal par30Principal,
            long par30Count,
            BigDecimal par60Principal,
            long par60Count,
            BigDecimal par90Principal,
            long par90Count,
            BigDecimal par30Ratio,
            BigDecimal par60Ratio,
            BigDecimal par90Ratio,
            BigDecimal defaultRate
    ) {
    }
}
