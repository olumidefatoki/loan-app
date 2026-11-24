package com.mfb.lending.module.reporting.calculation;

import java.util.List;

/**
 * Portfolio and application definitions shared by the SQL and the API text.
 */
public final class ReportingRules {

    public static final List<String> PENDING = List.of(
            "SUBMITTED", "KYC_PENDING", "DOCUMENTS_PENDING", "UNDER_REVIEW", "CREDIT_ASSESSMENT",
            "REFERRED", "COMPLIANCE_HOLD", "OFFER_ISSUED", "OFFER_ACCEPTED", "PENDING_DISBURSEMENT");
    public static final List<String> AWAITING_APPROVAL = List.of("PENDING_APPROVAL");
    public static final List<String> APPROVED = List.of("APPROVED");
    public static final List<String> REJECTED = List.of("REJECTED");
    public static final List<String> DISBURSED = List.of("DISBURSED");
    public static final List<String> WITHDRAWN = List.of("WITHDRAWN");
    public static final List<String> CANCELLED = List.of("CANCELLED");
    public static final List<String> EXPIRED = List.of("EXPIRED");
    public static final List<String> NON_DRAFT = List.of(
            "SUBMITTED", "KYC_PENDING", "DOCUMENTS_PENDING", "UNDER_REVIEW", "CREDIT_ASSESSMENT",
            "REFERRED", "COMPLIANCE_HOLD", "PENDING_APPROVAL", "APPROVED", "REJECTED",
            "OFFER_ISSUED", "OFFER_ACCEPTED", "PENDING_DISBURSEMENT", "DISBURSED",
            "WITHDRAWN", "CANCELLED", "EXPIRED");

    public static final String PAR =
            "PAR uses the outstanding principal of ACTIVE and OVERDUE loans that still have principal outstanding. "
                    + "Days past due come from the current schedule. An instalment due on the as-of date is not past due. "
                    + "Only POSTED repayment allocations reduce principal due and interest due. "
                    + "PAR 30, PAR 60, and PAR 90 use thresholds of 30, 60, and 90 days. A loan in PAR 90 is included in PAR 60 and PAR 30. "
                    + "When the report end date is before today, the principal weight is the posted general-ledger principal as of that date.";

    public static final String COLLECTION_RATE =
            "Collection rate is posted repayment cash divided by the contractual instalment amount on the current schedule "
                    + "whose due date falls in the period. When no instalment falls due, the rate is not defined. "
                    + "Management reports use general-ledger cash. The operations dashboard uses posted repayment amounts.";

    public static final String DEFAULT_RATE =
            "Default rate is PAR 90 principal divided by the same outstanding principal used as the PAR denominator. "
                    + "A non-performing loan is a denominator loan with at least 90 days past due.";

    public static final String APPROVAL_RATE =
            "Approval rate is the number of applications whose first APPROVED or REJECTED status in the period was APPROVED, "
                    + "divided by the applications that received either decision. Rejection rate uses the same denominator. "
                    + "The rate is not defined when no application was approved or rejected in the period.";

    public static final String TURNAROUND =
            "Loan turnaround time is the average number of days from submission to the first APPROVED or REJECTED status, "
                    + "for decisions that occurred in the period.";

    public static final String DISBURSEMENT =
            "Disbursement principal is the net posted amount on general-ledger account 1200 for DISBURSEMENT journals and reversals of those journals.";

    public static final String COLLECTIONS =
            "Repayment collections are the net posted amount on general-ledger account 2100 for REPAYMENT journals and reversals of those journals.";

    public static final String INTEREST =
            "Interest income is the net posted credit on general-ledger account 4200. The subledger check is posted interest accruals minus posted interest reversals.";

    public static final String FEES =
            "Fee income is the net posted credit on general-ledger account 4100. The subledger check is disbursement fees posted to that account plus posted loan charges.";

    public static final String RECONCILIATION =
            "Open balances compare the loan subledger with posted general-ledger accounts 1200, 1300, 1400, 1500, and 2200. "
                    + "That tie-out is the current book. Period disbursements, collections, interest, and fees use the report dates and compare the subledger with the same journals.";

    public static final String WRITE_OFF =
            "Write-off expense is the net debit on general-ledger account 5100. "
                    + "An uncollectible collection closure is reported as an operational exposure and is not treated as a write-off journal. "
                    + "A confirmed recovery is tied to its repayment. A recovery whose repayment is not POSTED is a reconciliation exception.";

    public static final String AVERAGE_LOAN =
            "Average loan size is period disbursement principal divided by the number of loans disbursed in the period.";

    public static final String SECURITY =
            "A loan is secured when the application has an active collateral pledge. Otherwise it is unsecured.";

    public static final String BUCKET =
            "Delinquency buckets match collections: current is 0 days, then 1-30, 31-60, 61-90, and 91 or more. "
                    + "A loan at exactly 90 days is in PAR 90 and in the 61-90 bucket.";

    private ReportingRules() {
    }

    public static String sqlList(List<String> values) {
        if (values.stream().anyMatch(value -> value == null || !value.matches("[A-Z0-9_]+"))) {
            throw new IllegalArgumentException("A reporting status list contains an unsafe token");
        }
        return String.join(", ", values.stream().map(value -> "'" + value + "'").toList());
    }

    public static String bucket(int daysPastDue) {
        if (daysPastDue <= 0) {
            return "CURRENT";
        }
        if (daysPastDue <= 30) {
            return "DPD_1_30";
        }
        if (daysPastDue <= 60) {
            return "DPD_31_60";
        }
        if (daysPastDue <= 90) {
            return "DPD_61_90";
        }
        return "DPD_ABOVE_90";
    }
}
