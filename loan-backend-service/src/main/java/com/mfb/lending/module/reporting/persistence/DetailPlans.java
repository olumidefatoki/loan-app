package com.mfb.lending.module.reporting.persistence;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.mfb.lending.module.reporting.application.ReportingScope;
import com.mfb.lending.module.reporting.calculation.ReportMask;
import com.mfb.lending.module.reporting.domain.ReportCode;
import com.mfb.lending.module.reporting.domain.ReportingModels.LoanLine;

final class DetailPlans {

    private DetailPlans() {
    }

    static Plan of(ReportCode code, ReportingScope scope) {
        return switch (code) {
            case DISBURSEMENTS -> disbursements(scope);
            case REPAYMENTS -> repayments(scope);
            case LOAN_PORTFOLIO, PAR, OUTSTANDING -> portfolio(scope, "TRUE", "p.disbursement_date", false);
            case DEFAULTS -> portfolio(scope, "p.days_past_due >= 90", "p.disbursement_date", true);
            case DELINQUENCY, COLLECTIONS -> portfolio(scope, "p.days_past_due >= 1", "p.disbursement_date", true);
            case MATURITY -> portfolio(scope, "p.maturity_date >= :dueFrom AND p.maturity_date < :dueTo", "p.maturity_date", false);
            case CLOSURES -> closures(scope);
            case RESTRUCTURED -> restructured(scope);
            case WRITE_OFFS -> writeOffs(scope);
            default -> throw new IllegalArgumentException(code + " is an aggregate report");
        };
    }

    static List<String> cells(Plan plan, ResultSet rs) throws SQLException {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < plan.columns().size(); i++) {
            values.add(cell(rs, i + 1, plan.columns().get(i)));
        }
        return values;
    }

    static LoanLine line(Plan plan, ResultSet rs) throws SQLException {
        List<String> values = cells(plan, rs);
        Map<String, String> mapped = new HashMap<>();
        for (int i = 0; i < plan.columns().size(); i++) {
            mapped.put(plan.columns().get(i).header(), values.get(i));
        }
        return new LoanLine(
                first(mapped, "Journal", "Reference", "Case"),
                blank(mapped.get("Loan")),
                date(mapped.get("Date")),
                money(mapped.get("Amount")),
                money(mapped.get("Principal")),
                money(mapped.get("Interest")),
                money(mapped.get("Fees")),
                money(mapped.get("Penalties")),
                blank(mapped.get("Status")),
                blank(first(mapped, "Event", "Type")),
                blank(mapped.get("Branch")),
                blank(mapped.get("Product")),
                blank(mapped.get("Segment")),
                blank(mapped.get("Security")),
                blank(mapped.get("Risk")),
                blank(mapped.get("Customer")),
                whole(mapped.get("Days past due")),
                blank(mapped.get("Bucket")));
    }

    private static Plan portfolio(ReportingScope scope, String where, String dateExpression, boolean bucket) {
        List<Column> columns = loanColumns(dateExpression, "p.principal", "p.interest", "p.fees", "p.penalties", "p.loan_status", bucket);
        String sql = ScopeSql.portfolioCte(scope)
                + " SELECT " + select(columns)
                + " FROM portfolio p JOIN customers c ON c.id = p.customer_id WHERE " + where;
        return new Plan(sql, columns, "col_1 DESC NULLS LAST, col_0");
    }

    private static Plan disbursements(ReportingScope scope) {
        List<Column> columns = List.of(
                text("Journal", "j.reference"),
                text("Loan", "la.reference"),
                date("Date", "(j.posted_at AT TIME ZONE :zone)::date"),
                money("Principal", "SUM(CASE WHEN l.direction = 'DEBIT' THEN l.amount ELSE -l.amount END)", true),
                text("Event", "j.event_code"),
                text("Branch", "COALESCE(b.code, 'UNASSIGNED')"),
                text("Product", "prod.product_code"),
                text("Segment", "app.customer_segment"),
                text("Security", "security.security_class"),
                text("Risk", "COALESCE(risk.risk_category, 'UNCLASSIFIED')"),
                customer("c.display_name"));
        String sql = "SELECT " + select(columns) + journalFrom()
                + " WHERE j.status = 'POSTED' AND " + ScopeSql.loanWhere(scope)
                + " AND j.posted_at >= :periodStart AND j.posted_at < :periodEnd AND l.account_code = '1200' "
                + ScopeSql.DISBURSEMENT_EVENT
                + group(columns);
        return new Plan(sql, columns, "col_2 DESC NULLS LAST, col_0");
    }

    private static Plan repayments(ReportingScope scope) {
        List<Column> columns = List.of(
                text("Reference", "r.reference"),
                text("Loan", "la.reference"),
                date("Date", "r.value_date"),
                money("Amount", "CASE WHEN j.event_code = 'REVERSAL' THEN -r.amount ELSE r.amount END", false),
                text("Event", "j.event_code"),
                text("Status", "r.status"),
                text("Branch", "COALESCE(b.code, 'UNASSIGNED')"),
                text("Product", "prod.product_code"),
                text("Segment", "app.customer_segment"),
                text("Security", "security.security_class"),
                text("Risk", "COALESCE(risk.risk_category, 'UNCLASSIFIED')"),
                customer("c.display_name"));
        String sql = "SELECT " + select(columns) + """
                 FROM loan_repayments r
                 JOIN journal_entries j ON (
                    (j.id = r.cash_journal_id AND j.event_code = 'REPAYMENT')
                    OR (j.event_code = 'REVERSAL' AND j.reversal_of = r.cash_journal_id))
                 JOIN loan_accounts la ON la.id = r.loan_account_id
                """ + loanDecoration("la")
                + " WHERE j.status = 'POSTED' AND " + ScopeSql.loanWhere(scope)
                + " AND j.posted_at >= :periodStart AND j.posted_at < :periodEnd ";
        return new Plan(sql, columns, "col_2 DESC NULLS LAST, col_0");
    }

    private static Plan closures(ReportingScope scope) {
        List<Column> columns = List.of(
                text("Loan", "la.reference"),
                date("Date", "(la.closed_at AT TIME ZONE :zone)::date"),
                money("Principal", "la.original_principal", false),
                text("Status", "la.status"),
                text("Branch", "COALESCE(b.code, 'UNASSIGNED')"),
                text("Product", "prod.product_code"),
                text("Segment", "app.customer_segment"),
                customer("c.display_name"));
        String sql = "SELECT " + select(columns) + " " + decoratedLoans()
                + " WHERE la.status = 'CLOSED' AND la.closed_at >= :periodStart AND la.closed_at < :periodEnd AND "
                + ScopeSql.loanWhere(scope);
        return new Plan(sql, columns, "col_1 DESC NULLS LAST, col_0");
    }

    private static Plan restructured(ReportingScope scope) {
        List<Column> columns = List.of(
                text("Loan", "la.reference"),
                date("Date", "(approval.decided_at AT TIME ZONE :zone)::date"),
                text("Type", "rs.restructuring_type"),
                money("Principal", "rs.outstanding_principal_after", false),
                money("Interest", "rs.outstanding_interest_after", false),
                text("Branch", "COALESCE(b.code, 'UNASSIGNED')"),
                text("Product", "prod.product_code"),
                customer("c.display_name"));
        String sql = "SELECT " + select(columns) + """
                 FROM loan_restructuring rs
                 JOIN restructuring_approvals approval ON approval.restructuring_id = rs.id AND approval.decision = 'APPROVED'
                 JOIN loan_accounts la ON la.id = rs.loan_account_id
                """ + loanDecoration("la")
                + " WHERE rs.status = 'APPROVED' AND approval.decided_at >= :periodStart AND approval.decided_at < :periodEnd AND "
                + ScopeSql.loanWhere(scope);
        return new Plan(sql, columns, "col_1 DESC NULLS LAST, col_0");
    }

    private static Plan writeOffs(ReportingScope scope) {
        List<Column> columns = List.of(
                text("Case", "cc.reference"),
                text("Loan", "la.reference"),
                date("Date", "(cc.closed_at AT TIME ZONE :zone)::date"),
                money("Amount", "cc.collection_exposure", false),
                text("Branch", "COALESCE(b.code, 'UNASSIGNED')"),
                text("Product", "prod.product_code"),
                customer("c.display_name"));
        String sql = "SELECT " + select(columns) + """
                 FROM collection_cases cc
                 JOIN loan_accounts la ON la.id = cc.loan_account_id
                """ + loanDecoration("la")
                + " WHERE cc.closure_outcome = 'UNCOLLECTIBLE' AND cc.closed_at >= :periodStart AND cc.closed_at < :periodEnd AND "
                + ScopeSql.loanWhere(scope);
        return new Plan(sql, columns, "col_2 DESC NULLS LAST, col_1");
    }

    private static List<Column> loanColumns(String dateExpression, String principal, String interest, String fees, String penalties, String status, boolean bucket) {
        List<Column> columns = new ArrayList<>();
        columns.add(text("Loan", "p.loan_reference"));
        columns.add(date("Date", dateExpression));
        columns.add(money("Principal", principal, false));
        columns.add(money("Interest", interest, false));
        columns.add(money("Fees", fees, false));
        columns.add(money("Penalties", penalties, false));
        columns.add(text("Status", status));
        columns.add(text("Branch", "p.branch_code"));
        columns.add(text("Product", "p.product_code"));
        columns.add(text("Segment", "p.segment"));
        columns.add(text("Security", "p.security_class"));
        columns.add(text("Risk", "p.risk_category"));
        columns.add(whole("Days past due", "p.days_past_due"));
        if (bucket) {
            columns.add(text("Bucket", """
                    CASE
                        WHEN p.days_past_due <= 0 THEN 'CURRENT'
                        WHEN p.days_past_due <= 30 THEN 'DPD_1_30'
                        WHEN p.days_past_due <= 60 THEN 'DPD_31_60'
                        WHEN p.days_past_due <= 90 THEN 'DPD_61_90'
                        ELSE 'DPD_ABOVE_90'
                    END
                    """));
        }
        columns.add(customer("c.display_name"));
        return columns;
    }

    private static String journalFrom() {
        return """
                 FROM journal_entries j
                 JOIN journal_entry_lines l ON l.journal_entry_id = j.id
                 JOIN loan_accounts la ON la.id = j.loan_account_id
                """ + loanDecoration("la");
    }

    private static String decoratedLoans() {
        return " FROM loan_accounts la " + loanDecoration("la");
    }

    private static String loanDecoration(String loanAlias) {
        return """
                 JOIN loan_applications app ON app.id = %s.application_id
                 JOIN customers c ON c.id = %s.customer_id
                 JOIN loan_products prod ON prod.id = %s.product_id
                 LEFT JOIN branches b ON b.id = %s.branch_id
                """.formatted(loanAlias, loanAlias, loanAlias, loanAlias)
                + ScopeSql.classification(loanAlias + ".application_id");
    }

    private static String select(List<Column> columns) {
        StringBuilder sql = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                sql.append(", ");
            }
            sql.append(columns.get(i).expression()).append(" AS col_").append(i);
        }
        return sql.toString();
    }

    private static String group(List<Column> columns) {
        List<String> parts = new ArrayList<>();
        for (Column column : columns) {
            if (!column.aggregate()) {
                parts.add(column.expression());
            }
        }
        return parts.isEmpty() ? "" : " GROUP BY " + String.join(", ", parts);
    }

    private static String cell(ResultSet rs, int index, Column column) throws SQLException {
        String value = switch (column.kind()) {
            case MONEY -> moneyText(rs.getBigDecimal(index));
            case WHOLE -> rs.getObject(index) == null ? "" : Long.toString(rs.getLong(index));
            case DATE -> dateText(rs, index);
            case TEXT -> text(rs.getString(index));
        };
        if (column.mask()) {
            value = ReportMask.displayName(value);
        }
        return value;
    }

    private static String moneyText(BigDecimal value) {
        if (value == null) {
            return "";
        }
        return value.setScale(6, RoundingMode.HALF_UP).toPlainString();
    }

    private static String dateText(ResultSet rs, int index) throws SQLException {
        Object value = rs.getObject(index);
        if (value == null) {
            return "";
        }
        if (value instanceof LocalDate date) {
            return date.toString();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate().toString();
        }
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime().toLocalDate().toString();
        }
        if (value instanceof OffsetDateTime time) {
            return time.toLocalDate().toString();
        }
        return value.toString();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String first(Map<String, String> values, String... keys) {
        for (String key : keys) {
            String value = values.get(key);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static LocalDate date(String value) {
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    private static BigDecimal money(String value) {
        return value == null || value.isBlank() ? null : new BigDecimal(value);
    }

    private static Integer whole(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }

    private static Column text(String header, String expression) {
        return new Column(header, expression, false, false, CellKind.TEXT);
    }

    private static Column customer(String expression) {
        return new Column("Customer", expression, false, true, CellKind.TEXT);
    }

    private static Column date(String header, String expression) {
        return new Column(header, expression, false, false, CellKind.DATE);
    }

    private static Column money(String header, String expression, boolean aggregate) {
        return new Column(header, expression, aggregate, false, CellKind.MONEY);
    }

    private static Column whole(String header, String expression) {
        return new Column(header, expression, false, false, CellKind.WHOLE);
    }

    record Column(String header, String expression, boolean aggregate, boolean mask, CellKind kind) {
    }

    enum CellKind {
        TEXT, MONEY, WHOLE, DATE
    }

    record Plan(String sql, List<Column> columns, String orderBy) {
        List<String> headers() {
            return columns.stream().map(Column::header).toList();
        }
    }
}
