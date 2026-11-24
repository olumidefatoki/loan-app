package com.mfb.lending.module.collections.calculation;

/**
 * Delinquency and portfolio-at-risk rules used by the calculator, the daily job, and the PAR report.
 */
public final class DelinquencyRules {

    public static final String DAYS_PAST_DUE =
            "Days past due is the largest number of completed calendar days between the as-of date and the due date of a "
                    + "current-schedule instalment that still has a confirmed unpaid amount. An instalment due on the as-of date is not past due.";

    public static final String CONFIRMED_ALLOCATION =
            "Confirmed paid amounts are repayment allocations whose repayment status is POSTED. Received, awaiting confirmation, "
                    + "failed, and reversed repayments do not reduce principal due, interest due, or days past due.";

    public static final String DENOMINATOR =
            "The PAR denominator is the outstanding principal of loan accounts in ACTIVE or OVERDUE status whose outstanding "
                    + "principal is greater than zero. PENDING_ACTIVATION, CLOSED, and zero-principal accounts are excluded.";

    public static final String NUMERATOR =
            "The PAR numerator is the outstanding principal of denominator loans whose days past due are greater than or equal "
                    + "to the threshold. PAR 30 uses 30, PAR 60 uses 60, and PAR 90 uses 90. A loan in PAR 90 is included in PAR 60 and PAR 30.";

    public static final String COLLECTION_EXPOSURE =
            "Collection exposure is the confirmed unpaid amount of instalments due before the as-of date, plus outstanding fees "
                    + "and outstanding penalties. Principal that is not yet due is reported separately and is not added to exposure.";

    public static final String PAR_DESCRIPTION = DAYS_PAST_DUE + " " + CONFIRMED_ALLOCATION + " " + DENOMINATOR + " " + NUMERATOR;

    private DelinquencyRules() {
    }
}
