package com.mfb.lending.module.reporting.persistence;

import com.mfb.lending.module.reporting.application.ReportingScope;
import com.mfb.lending.module.reporting.calculation.ReportingCalculator;

final class ScopeSql {

    static final String PAID = """
            SELECT a.schedule_item_id,
                   COALESCE(SUM(a.amount) FILTER (WHERE a.component = 'PRINCIPAL'), 0) AS principal_paid,
                   COALESCE(SUM(a.amount) FILTER (WHERE a.component = 'INTEREST'), 0) AS interest_paid
            FROM loan_repayment_allocations a
            JOIN loan_repayments r ON r.id = a.repayment_id AND r.status = 'POSTED'
            WHERE a.schedule_item_id IS NOT NULL
            GROUP BY a.schedule_item_id
            """;

    static final String GL_BALANCES = """
            SELECT j.loan_account_id,
                   COALESCE(SUM(CASE WHEN l.account_code = '1200' AND l.direction = 'DEBIT' THEN l.amount WHEN l.account_code = '1200' THEN -l.amount ELSE 0 END), 0) AS principal,
                   COALESCE(SUM(CASE WHEN l.account_code = '1300' AND l.direction = 'DEBIT' THEN l.amount WHEN l.account_code = '1300' THEN -l.amount ELSE 0 END), 0) AS interest,
                   COALESCE(SUM(CASE WHEN l.account_code = '1400' AND l.direction = 'DEBIT' THEN l.amount WHEN l.account_code = '1400' THEN -l.amount ELSE 0 END), 0) AS fees,
                   COALESCE(SUM(CASE WHEN l.account_code = '1500' AND l.direction = 'DEBIT' THEN l.amount WHEN l.account_code = '1500' THEN -l.amount ELSE 0 END), 0) AS penalties
            FROM journal_entries j
            JOIN journal_entry_lines l ON l.journal_entry_id = j.id
            WHERE j.status = 'POSTED'
              AND j.posted_at < :stockExclusive
              AND l.account_code IN ('1200', '1300', '1400', '1500')
            GROUP BY j.loan_account_id
            """;

    static final String DPD = """
            COALESCE((
                SELECT MAX(CAST(:stockDate AS date) - item.due_date)
                FROM loan_schedule_items item
                LEFT JOIN paid ON paid.schedule_item_id = item.id
                WHERE item.schedule_id = la.current_schedule_id
                  AND item.due_date < CAST(:stockDate AS date)
                  AND (GREATEST(item.principal_due - COALESCE(paid.principal_paid, 0), 0)
                    + GREATEST(item.interest_due - COALESCE(paid.interest_paid, 0), 0)) > 0
            ), 0)
            """;

    static final String DISBURSEMENT_EVENT = """
            AND (j.event_code = 'DISBURSEMENT' OR (j.event_code = 'REVERSAL' AND EXISTS (
                SELECT 1 FROM journal_entries original
                WHERE original.id = j.reversal_of AND original.event_code = 'DISBURSEMENT')))
            """;

    static final String REPAYMENT_EVENT = """
            AND (j.event_code = 'REPAYMENT' OR (j.event_code = 'REVERSAL' AND EXISTS (
                SELECT 1 FROM journal_entries original
                WHERE original.id = j.reversal_of AND original.event_code = 'REPAYMENT')))
            """;

    static final String OFFICER = """
            AND EXISTS (
                SELECT 1 FROM system_users officer
                WHERE officer.id = :officerId
                  AND officer.principal_type = 'STAFF'
                  AND (officer.email = app.created_by OR officer.id::text = app.created_by))
            """;

    private ScopeSql() {
    }

    static String classification(String applicationId) {
        return """
                LEFT JOIN LATERAL (
                    SELECT ca.risk_category
                    FROM credit_assessments ca
                    WHERE ca.application_id = %s AND ca.risk_category IS NOT NULL
                    ORDER BY ca.revision DESC, ca.updated_at DESC
                    LIMIT 1
                ) risk ON TRUE
                LEFT JOIN LATERAL (
                    SELECT CASE WHEN EXISTS (
                        SELECT 1 FROM loan_collaterals lc
                        WHERE lc.application_id = %s AND lc.status = 'ACTIVE'
                    ) THEN 'SECURED' ELSE 'UNSECURED' END AS security_class
                ) security ON TRUE
                """.formatted(applicationId, applicationId);
    }

    static String loanWhere(ReportingScope scope) {
        StringBuilder sql = new StringBuilder("la.currency_code = 'NGN'");
        appendScope(sql, scope, "la.branch_id", "la.product_id", true);
        return sql.toString();
    }

    static String applicationWhere(ReportingScope scope, boolean createdInPeriod) {
        StringBuilder sql = new StringBuilder("app.status <> 'DRAFT'");
        appendScope(sql, scope, "app.branch_id", "app.product_id", false);
        if (createdInPeriod) {
            sql.append(" AND app.created_at >= :periodStart AND app.created_at < :periodEnd");
        }
        if (scope.loanStatus() != null) {
            sql.append("""
                     AND EXISTS (
                        SELECT 1 FROM loan_accounts status_loan
                        WHERE status_loan.application_id = app.id AND status_loan.status = :loanStatus)
                    """);
        }
        return sql.toString();
    }

    static String portfolioCte(ReportingScope scope) {
        String principal = scope.historical() ? "COALESCE(gl.principal, 0)" : "la.outstanding_principal";
        String interest = scope.historical() ? "COALESCE(gl.interest, 0)" : "la.outstanding_interest";
        String fees = scope.historical() ? "COALESCE(gl.fees, 0)" : "la.outstanding_fees";
        String penalties = scope.historical() ? "COALESCE(gl.penalties, 0)" : "la.outstanding_penalties";
        String population = scope.historical()
                ? "la.disbursement_date <= :stockDate AND COALESCE(gl.principal, 0) > 0"
                : "la.status IN ('ACTIVE', 'OVERDUE') AND la.outstanding_principal > 0";
        String glCte = scope.historical() ? ", gl_balances AS (" + GL_BALANCES + ")" : "";
        String glJoin = scope.historical() ? " LEFT JOIN gl_balances gl ON gl.loan_account_id = la.id " : "";
        return "WITH paid AS (" + PAID + ")" + glCte + ", portfolio AS ("
                + "SELECT la.id AS loan_id, la.customer_id, la.reference AS loan_reference, la.status AS loan_status, "
                + principal + " AS principal, " + interest + " AS interest, " + fees + " AS fees, " + penalties + " AS penalties, "
                + "la.original_principal, la.disbursement_date, la.maturity_date, la.closed_at, la.branch_id, "
                + "COALESCE(b.code, 'UNASSIGNED') AS branch_code, COALESCE(b.name, 'Unassigned') AS branch_name, "
                + "la.product_id, prod.product_code, pv.product_name, app.customer_segment AS segment, "
                + "security.security_class, COALESCE(risk.risk_category, 'UNCLASSIFIED') AS risk_category, "
                + DPD + " AS days_past_due "
                + "FROM loan_accounts la "
                + "JOIN loan_applications app ON app.id = la.application_id "
                + "JOIN loan_products prod ON prod.id = la.product_id "
                + "JOIN loan_product_versions pv ON pv.id = la.product_version_id "
                + "LEFT JOIN branches b ON b.id = la.branch_id "
                + classification("la.application_id")
                + glJoin
                + " WHERE " + loanWhere(scope) + " AND " + population
                + ")";
    }

    static String parPrincipal(String amount, String days, int threshold) {
        return ReportingCalculator.principalAtLeast(amount, days, threshold);
    }

    private static void appendScope(StringBuilder sql, ReportingScope scope, String branchColumn, String productColumn, boolean loanLevel) {
        if (!scope.unrestricted()) {
            sql.append(" AND ").append(branchColumn).append(" IN (:branchIds)");
        }
        if (scope.branchId() != null) {
            sql.append(" AND ").append(branchColumn).append(" = :branchId");
        }
        if (scope.productId() != null) {
            sql.append(" AND ").append(productColumn).append(" = :productId");
        }
        if (scope.segment() != null) {
            sql.append(" AND app.customer_segment = :segment");
        }
        if (loanLevel && scope.loanStatus() != null) {
            sql.append(" AND la.status = :loanStatus");
        }
        if (scope.riskCategory() != null) {
            sql.append(" AND COALESCE(risk.risk_category, 'UNCLASSIFIED') = :riskCategory");
        }
        if (scope.securityClass() != null) {
            sql.append(" AND security.security_class = :securityClass");
        }
        if (scope.officerId() != null) {
            sql.append(OFFICER);
        }
        if (scope.repaymentStatus() != null) {
            if (loanLevel) {
                sql.append("""
                         AND EXISTS (
                            SELECT 1 FROM loan_repayments repay_filter
                            WHERE repay_filter.loan_account_id = la.id AND repay_filter.status = :repaymentStatus)
                        """);
            } else {
                sql.append("""
                         AND EXISTS (
                            SELECT 1 FROM loan_accounts repay_loan
                            JOIN loan_repayments repay_filter ON repay_filter.loan_account_id = repay_loan.id
                            WHERE repay_loan.application_id = app.id AND repay_filter.status = :repaymentStatus)
                        """);
            }
        }
    }
}
