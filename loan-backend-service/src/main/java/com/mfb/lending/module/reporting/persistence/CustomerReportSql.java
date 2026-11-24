package com.mfb.lending.module.reporting.persistence;

/**
 * Customer reporting SQL. Every statement is restricted to :customerId and does not read identity numbers or contact fields.
 */
public final class CustomerReportSql {

    public static final String POSITION = """
            SELECT COUNT(*) FILTER (WHERE la.status IN ('ACTIVE', 'OVERDUE')) AS active_loans,
                   COALESCE(SUM(la.outstanding_principal) FILTER (WHERE la.status IN ('ACTIVE', 'OVERDUE')), 0) AS outstanding_principal,
                   COALESCE(SUM(la.outstanding_interest) FILTER (WHERE la.status IN ('ACTIVE', 'OVERDUE')), 0) AS outstanding_interest,
                   COALESCE(SUM(la.outstanding_fees) FILTER (WHERE la.status IN ('ACTIVE', 'OVERDUE')), 0) AS outstanding_fees,
                   COALESCE(SUM(la.outstanding_penalties) FILTER (WHERE la.status IN ('ACTIVE', 'OVERDUE')), 0) AS outstanding_penalties
            FROM loan_accounts la
            WHERE la.customer_id = :customerId
            """;

    public static final String NEXT_REPAYMENT = """
            WITH paid AS (
            """ + ScopeSql.PAID + """
            )
            SELECT item.due_date,
                   GREATEST(item.principal_due - COALESCE(paid.principal_paid, 0), 0)
                   + GREATEST(item.interest_due - COALESCE(paid.interest_paid, 0), 0) AS amount_due
            FROM loan_accounts la
            JOIN loan_schedule_items item ON item.schedule_id = la.current_schedule_id
            LEFT JOIN paid ON paid.schedule_item_id = item.id
            WHERE la.customer_id = :customerId
              AND la.status IN ('ACTIVE', 'OVERDUE')
              AND (GREATEST(item.principal_due - COALESCE(paid.principal_paid, 0), 0)
                + GREATEST(item.interest_due - COALESCE(paid.interest_paid, 0), 0)) > 0
            ORDER BY item.due_date, la.reference
            LIMIT 1
            """;

    public static final String STATEMENT = """
            SELECT COALESCE(SUM(a.amount) FILTER (WHERE a.component = 'PRINCIPAL'), 0) AS principal_paid,
                   COALESCE(SUM(a.amount) FILTER (WHERE a.component = 'INTEREST'), 0) AS interest_paid,
                   COALESCE(SUM(a.amount) FILTER (WHERE a.component = 'FEE'), 0) AS fees_paid,
                   COALESCE(SUM(a.amount) FILTER (WHERE a.component = 'PENALTY'), 0) AS penalties_paid,
                   MAX(r.value_date) AS last_payment_date
            FROM loan_repayment_allocations a
            JOIN loan_repayments r ON r.id = a.repayment_id AND r.status = 'POSTED'
            JOIN loan_accounts la ON la.id = r.loan_account_id
            WHERE la.customer_id = :customerId
            """;

    public static final String LOANS = """
            WITH paid AS (
            """ + ScopeSql.PAID + """
            )
            SELECT la.reference, prod.product_code, la.status, la.outstanding_principal, la.outstanding_interest,
                   la.outstanding_fees, la.outstanding_penalties, la.maturity_date, nxt.due_date, nxt.amount_due
            FROM loan_accounts la
            JOIN loan_products prod ON prod.id = la.product_id
            LEFT JOIN LATERAL (
                SELECT item.due_date,
                       GREATEST(item.principal_due - COALESCE(paid.principal_paid, 0), 0)
                       + GREATEST(item.interest_due - COALESCE(paid.interest_paid, 0), 0) AS amount_due
                FROM loan_schedule_items item
                LEFT JOIN paid ON paid.schedule_item_id = item.id
                WHERE item.schedule_id = la.current_schedule_id
                  AND (GREATEST(item.principal_due - COALESCE(paid.principal_paid, 0), 0)
                    + GREATEST(item.interest_due - COALESCE(paid.interest_paid, 0), 0)) > 0
                ORDER BY item.due_date
                LIMIT 1
            ) nxt ON TRUE
            WHERE la.customer_id = :customerId
            ORDER BY la.disbursement_date DESC, la.reference
            LIMIT :limit OFFSET :offset
            """;

    public static final String LOAN_COUNT = """
            SELECT COUNT(*) FROM loan_accounts la WHERE la.customer_id = :customerId
            """;

    public static final String REPAYMENTS = """
            SELECT r.reference, la.reference AS loan_reference, r.value_date, r.amount, r.status, r.channel
            FROM loan_repayments r
            JOIN loan_accounts la ON la.id = r.loan_account_id
            WHERE la.customer_id = :customerId
              AND r.value_date >= :dueFrom AND r.value_date < :dueTo
            ORDER BY r.value_date DESC, r.reference
            LIMIT :limit OFFSET :offset
            """;

    public static final String REPAYMENT_COUNT = """
            SELECT COUNT(*)
            FROM loan_repayments r
            JOIN loan_accounts la ON la.id = r.loan_account_id
            WHERE la.customer_id = :customerId
              AND r.value_date >= :dueFrom AND r.value_date < :dueTo
            """;

    public static final String APPLICATIONS = """
            SELECT app.reference, prod.product_code, app.status, app.amount, app.currency_code, app.submitted_at
            FROM loan_applications app
            JOIN loan_products prod ON prod.id = app.product_id
            WHERE app.customer_id = :customerId
            ORDER BY app.created_at DESC, app.reference
            LIMIT :limit
            """;

    public static final String APPLICATION_COUNT = """
            SELECT COUNT(*) FROM loan_applications app WHERE app.customer_id = :customerId
            """;

    private CustomerReportSql() {
    }
}
