package com.mfb.lending.module.reporting.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.mfb.lending.module.reporting.application.ReportingScope;
import com.mfb.lending.module.reporting.calculation.ReportingCalculator;
import com.mfb.lending.module.reporting.calculation.ReportingRules;
import com.mfb.lending.module.reporting.domain.ExportFormat;
import com.mfb.lending.module.reporting.domain.ExportStatus;
import com.mfb.lending.module.reporting.domain.GroupDimension;
import com.mfb.lending.module.reporting.domain.LedgerMovement;
import com.mfb.lending.module.reporting.domain.ReportCode;
import com.mfb.lending.module.reporting.domain.ReportingModels.ApplicationCounts;
import com.mfb.lending.module.reporting.domain.ReportingModels.BalanceMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerApplication;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerLoan;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerLoanPage;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerPosition;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerRepayment;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerRepaymentPage;
import com.mfb.lending.module.reporting.domain.ReportingModels.DecisionMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.DetailPage;
import com.mfb.lending.module.reporting.domain.ReportingModels.ExportJob;
import com.mfb.lending.module.reporting.domain.ReportingModels.FlowMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.GroupMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.SnapshotRow;
import com.mfb.lending.module.reporting.domain.ReportingModels.StockMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.WriteOffMetrics;
import com.mfb.lending.module.reporting.persistence.DetailPlans.Plan;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Repository
public class JdbcReportingStore implements ReportingStore {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private final NamedParameterJdbcTemplate jdbc;
    private final PlatformTransactionManager transactions;

    public JdbcReportingStore(NamedParameterJdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @Override
    public CustomerPosition customerPosition(UUID customerId) {
        MapSqlParameterSource params = customer(customerId);
        CustomerPosition balances = jdbc.queryForObject(CustomerReportSql.POSITION, params, (rs, row) -> new CustomerPosition(
                rs.getLong("active_loans"), money(rs, "outstanding_principal"), money(rs, "outstanding_interest"),
                money(rs, "outstanding_fees"), money(rs, "outstanding_penalties"), null, null, ZERO, ZERO, ZERO, ZERO, null));
        List<CustomerPosition> next = jdbc.query(CustomerReportSql.NEXT_REPAYMENT, params, (rs, row) -> new CustomerPosition(
                0, ZERO, ZERO, ZERO, ZERO, rs.getObject("due_date", LocalDate.class), money(rs, "amount_due"), ZERO, ZERO, ZERO, ZERO, null));
        CustomerPosition paid = jdbc.queryForObject(CustomerReportSql.STATEMENT, params, (rs, row) -> new CustomerPosition(
                0, ZERO, ZERO, ZERO, ZERO, null, null, money(rs, "principal_paid"), money(rs, "interest_paid"),
                money(rs, "fees_paid"), money(rs, "penalties_paid"), rs.getObject("last_payment_date", LocalDate.class)));
        CustomerPosition upcoming = next.isEmpty() ? null : next.get(0);
        return new CustomerPosition(
                balances.activeLoans(), balances.outstandingPrincipal(), balances.outstandingInterest(), balances.outstandingFees(),
                balances.outstandingPenalties(), upcoming == null ? null : upcoming.nextRepaymentDate(),
                upcoming == null ? null : upcoming.nextRepaymentAmount(), paid.principalPaid(), paid.interestPaid(),
                paid.feesPaid(), paid.penaltiesPaid(), paid.lastPaymentDate());
    }

    @Override
    public CustomerLoanPage customerLoans(UUID customerId, int limit, int offset, int page, int size) {
        MapSqlParameterSource params = customer(customerId).addValue("limit", limit).addValue("offset", offset);
        List<CustomerLoan> loans = jdbc.query(CustomerReportSql.LOANS, params, (rs, row) -> new CustomerLoan(
                rs.getString("reference"), rs.getString("product_code"), rs.getString("status"),
                money(rs, "outstanding_principal"), money(rs, "outstanding_interest"), money(rs, "outstanding_fees"),
                money(rs, "outstanding_penalties"), rs.getObject("maturity_date", LocalDate.class),
                rs.getObject("due_date", LocalDate.class), nullableMoney(rs, "amount_due")));
        long total = count(CustomerReportSql.LOAN_COUNT, customer(customerId));
        return new CustomerLoanPage(loans, page, size, total);
    }

    @Override
    public CustomerRepaymentPage customerRepayments(UUID customerId, LocalDate dueFrom, LocalDate dueTo, int limit, int offset, int page, int size) {
        MapSqlParameterSource params = customer(customerId).addValue("dueFrom", dueFrom).addValue("dueTo", dueTo).addValue("limit", limit).addValue("offset", offset);
        List<CustomerRepayment> repayments = jdbc.query(CustomerReportSql.REPAYMENTS, params, (rs, row) -> new CustomerRepayment(
                rs.getString("reference"), rs.getString("loan_reference"), rs.getObject("value_date", LocalDate.class),
                money(rs, "amount"), rs.getString("status"), rs.getString("channel")));
        long total = count(CustomerReportSql.REPAYMENT_COUNT, customer(customerId).addValue("dueFrom", dueFrom).addValue("dueTo", dueTo));
        return new CustomerRepaymentPage(repayments, page, size, total);
    }

    @Override
    public List<CustomerApplication> customerApplications(UUID customerId, int limit) {
        return jdbc.query(CustomerReportSql.APPLICATIONS, customer(customerId).addValue("limit", limit), (rs, row) -> new CustomerApplication(
                rs.getString("reference"), rs.getString("product_code"), rs.getString("status"),
                nullableMoney(rs, "amount"), rs.getString("currency_code"), instant(rs, "submitted_at")));
    }

    @Override
    public long customerApplicationCount(UUID customerId) {
        return count(CustomerReportSql.APPLICATION_COUNT, customer(customerId));
    }

    @Override
    public ApplicationCounts applications(ReportingScope scope) {
        if (blocked(scope)) {
            return ApplicationCounts.empty();
        }
        String sql = """
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE app.status IN (%s)) AS pending,
                       COUNT(*) FILTER (WHERE app.status IN (%s)) AS awaiting,
                       COUNT(*) FILTER (WHERE app.status IN (%s)) AS approved,
                       COUNT(*) FILTER (WHERE app.status IN (%s)) AS rejected,
                       COUNT(*) FILTER (WHERE app.status IN (%s)) AS disbursed,
                       COUNT(*) FILTER (WHERE app.status IN (%s)) AS withdrawn,
                       COUNT(*) FILTER (WHERE app.status IN (%s)) AS cancelled,
                       COUNT(*) FILTER (WHERE app.status IN (%s)) AS expired
                FROM loan_applications app
                %s
                WHERE %s
                """.formatted(
                ReportingRules.sqlList(ReportingRules.PENDING),
                ReportingRules.sqlList(ReportingRules.AWAITING_APPROVAL),
                ReportingRules.sqlList(ReportingRules.APPROVED),
                ReportingRules.sqlList(ReportingRules.REJECTED),
                ReportingRules.sqlList(ReportingRules.DISBURSED),
                ReportingRules.sqlList(ReportingRules.WITHDRAWN),
                ReportingRules.sqlList(ReportingRules.CANCELLED),
                ReportingRules.sqlList(ReportingRules.EXPIRED),
                ScopeSql.classification("app.id"),
                ScopeSql.applicationWhere(scope, true));
        return jdbc.queryForObject(sql, params(scope), (rs, row) -> new ApplicationCounts(
                rs.getLong("total"), rs.getLong("pending"), rs.getLong("awaiting"), rs.getLong("approved"),
                rs.getLong("rejected"), rs.getLong("disbursed"), rs.getLong("withdrawn"), rs.getLong("cancelled"), rs.getLong("expired")));
    }

    @Override
    public DecisionMetrics decisions(ReportingScope scope) {
        if (blocked(scope)) {
            return DecisionMetrics.empty();
        }
        String sql = """
                SELECT COUNT(*) FILTER (WHERE decision.to_status = 'APPROVED') AS approved,
                       COUNT(*) FILTER (WHERE decision.to_status = 'REJECTED') AS rejected,
                       AVG(EXTRACT(EPOCH FROM (decision.occurred_at - app.submitted_at)) / 86400.0)
                           FILTER (WHERE app.submitted_at IS NOT NULL) AS turnaround_days,
                       COUNT(*) FILTER (WHERE app.submitted_at IS NOT NULL) AS turnaround_sample
                FROM loan_applications app
                JOIN LATERAL (
                    SELECT h.to_status, h.occurred_at
                    FROM loan_application_status_history h
                    WHERE h.application_id = app.id AND h.to_status IN ('APPROVED', 'REJECTED')
                    ORDER BY h.occurred_at
                    LIMIT 1
                ) decision ON TRUE
                %s
                WHERE decision.occurred_at >= :periodStart
                  AND decision.occurred_at < :periodEnd
                  AND %s
                """.formatted(ScopeSql.classification("app.id"), ScopeSql.applicationWhere(scope, false));
        return jdbc.queryForObject(sql, params(scope), (rs, row) -> new DecisionMetrics(
                rs.getLong("approved"), rs.getLong("rejected"), nullableDecimal(rs, "turnaround_days"), rs.getLong("turnaround_sample")));
    }

    @Override
    public StockMetrics stock(ReportingScope scope) {
        String basis = scope.historical() ? "GENERAL_LEDGER_AS_OF" : "SUBLEDGER";
        if (blocked(scope)) {
            return StockMetrics.empty(basis);
        }
        String sql = ScopeSql.portfolioCte(scope) + """
                 SELECT COUNT(*) AS loan_count,
                        COALESCE(SUM(p.principal), 0) AS outstanding_principal,
                        COALESCE(SUM(p.interest), 0) AS outstanding_interest,
                        COALESCE(SUM(p.fees), 0) AS outstanding_fees,
                        COALESCE(SUM(p.penalties), 0) AS outstanding_penalties,
                        COALESCE(SUM(p.principal) FILTER (WHERE p.days_past_due >= 1), 0) AS overdue_principal,
                        COUNT(*) FILTER (WHERE p.days_past_due >= 1) AS overdue_count,
                        COUNT(*) FILTER (WHERE p.loan_status = 'OVERDUE') AS overdue_status_count,
                        %s AS par30_principal,
                        COUNT(*) FILTER (WHERE p.days_past_due >= %d) AS par30_count,
                        %s AS par60_principal,
                        COUNT(*) FILTER (WHERE p.days_past_due >= %d) AS par60_count,
                        %s AS par90_principal,
                        COUNT(*) FILTER (WHERE p.days_past_due >= %d) AS par90_count,
                        COUNT(*) FILTER (WHERE p.days_past_due <= 0) AS bucket_current,
                        COUNT(*) FILTER (WHERE p.days_past_due BETWEEN 1 AND 30) AS bucket_1_30,
                        COUNT(*) FILTER (WHERE p.days_past_due BETWEEN 31 AND 60) AS bucket_31_60,
                        COUNT(*) FILTER (WHERE p.days_past_due BETWEEN 61 AND 90) AS bucket_61_90,
                        COUNT(*) FILTER (WHERE p.days_past_due >= 91) AS bucket_above_90
                 FROM portfolio p
                """.formatted(
                ScopeSql.parPrincipal("p.principal", "p.days_past_due", ReportingCalculator.PAR_30),
                ReportingCalculator.PAR_30,
                ScopeSql.parPrincipal("p.principal", "p.days_past_due", ReportingCalculator.PAR_60),
                ReportingCalculator.PAR_60,
                ScopeSql.parPrincipal("p.principal", "p.days_past_due", ReportingCalculator.PAR_90),
                ReportingCalculator.PAR_90);
        return jdbc.queryForObject(sql, params(scope), (rs, row) -> new StockMetrics(
                rs.getLong("loan_count"), money(rs, "outstanding_principal"), money(rs, "outstanding_interest"),
                money(rs, "outstanding_fees"), money(rs, "outstanding_penalties"), money(rs, "overdue_principal"),
                rs.getLong("overdue_count"), rs.getLong("overdue_status_count"), money(rs, "par30_principal"), rs.getLong("par30_count"),
                money(rs, "par60_principal"), rs.getLong("par60_count"), money(rs, "par90_principal"), rs.getLong("par90_count"),
                rs.getLong("bucket_current"), rs.getLong("bucket_1_30"), rs.getLong("bucket_31_60"), rs.getLong("bucket_61_90"),
                rs.getLong("bucket_above_90"), basis));
    }

    @Override
    public FlowMetrics flow(ReportingScope scope) {
        if (blocked(scope)) {
            return FlowMetrics.empty();
        }
        MapSqlParameterSource params = params(scope);
        return new FlowMetrics(
                journalSum(params, scope, "CASE WHEN l.direction = 'DEBIT' THEN l.amount ELSE -l.amount END",
                        " AND l.account_code = '1200' AND j.posted_at >= :periodStart AND j.posted_at < :periodEnd " + ScopeSql.DISBURSEMENT_EVENT),
                scalar(bookDisbursementSql(scope), params),
                count(bookDisbursementCountSql(scope), params),
                journalSum(params, scope, "CASE WHEN l.direction = 'DEBIT' THEN l.amount ELSE -l.amount END",
                        " AND l.account_code = '2100' AND j.posted_at >= :periodStart AND j.posted_at < :periodEnd " + ScopeSql.REPAYMENT_EVENT),
                scalar(bookCollectionSql(scope), params),
                scalar(dueSql(scope), params),
                journalSum(params, scope, "CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END",
                        " AND l.account_code = '4200' AND j.posted_at >= :periodStart AND j.posted_at < :periodEnd "),
                scalar(accrualSql(scope), params),
                journalSum(params, scope, "l.amount",
                        " AND j.event_code = 'INTEREST_REVERSAL' AND l.account_code = '4200' AND l.direction = 'DEBIT' AND j.posted_at >= :periodStart AND j.posted_at < :periodEnd "),
                journalSum(params, scope, "CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END",
                        " AND l.account_code = '4100' AND j.posted_at >= :periodStart AND j.posted_at < :periodEnd "),
                journalSum(params, scope, "CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END",
                        " AND l.account_code = '4100' AND j.posted_at >= :periodStart AND j.posted_at < :periodEnd " + ScopeSql.DISBURSEMENT_EVENT),
                scalar(chargeSql(scope), params));
    }

    @Override
    public BalanceMetrics balances(ReportingScope scope) {
        if (blocked(scope)) {
            return BalanceMetrics.empty();
        }
        String subledger = """
                SELECT COALESCE(SUM(la.outstanding_principal), 0) AS principal,
                       COALESCE(SUM(la.outstanding_interest), 0) AS interest,
                       COALESCE(SUM(la.outstanding_fees), 0) AS fees,
                       COALESCE(SUM(la.outstanding_penalties), 0) AS penalties,
                       COALESCE(SUM(la.overpayment), 0) AS overpayment
                FROM loan_accounts la
                JOIN loan_applications app ON app.id = la.application_id
                %s
                WHERE %s
                """.formatted(ScopeSql.classification("la.application_id"), ScopeSql.loanWhere(scope));
        String ledger = """
                SELECT COALESCE(SUM(CASE WHEN l.account_code = '1200' AND l.direction = 'DEBIT' THEN l.amount WHEN l.account_code = '1200' THEN -l.amount ELSE 0 END), 0) AS principal,
                       COALESCE(SUM(CASE WHEN l.account_code = '1300' AND l.direction = 'DEBIT' THEN l.amount WHEN l.account_code = '1300' THEN -l.amount ELSE 0 END), 0) AS interest,
                       COALESCE(SUM(CASE WHEN l.account_code = '1400' AND l.direction = 'DEBIT' THEN l.amount WHEN l.account_code = '1400' THEN -l.amount ELSE 0 END), 0) AS fees,
                       COALESCE(SUM(CASE WHEN l.account_code = '1500' AND l.direction = 'DEBIT' THEN l.amount WHEN l.account_code = '1500' THEN -l.amount ELSE 0 END), 0) AS penalties,
                       COALESCE(SUM(CASE WHEN l.account_code = '2200' AND l.direction = 'CREDIT' THEN l.amount WHEN l.account_code = '2200' THEN -l.amount ELSE 0 END), 0) AS overpayment
                %s
                WHERE j.status = 'POSTED' AND %s
                """.formatted(journalBody(), ScopeSql.loanWhere(scope));
        MapSqlParameterSource params = params(scope);
        return jdbc.queryForObject(subledger, params, (sub, row) -> jdbc.queryForObject(ledger, params, (gl, ignored) -> new BalanceMetrics(
                money(sub, "principal"), money(gl, "principal"), money(sub, "interest"), money(gl, "interest"),
                money(sub, "fees"), money(gl, "fees"), money(sub, "penalties"), money(gl, "penalties"),
                money(sub, "overpayment"), money(gl, "overpayment"))));
    }

    @Override
    public WriteOffMetrics writeOff(ReportingScope scope) {
        if (blocked(scope)) {
            return WriteOffMetrics.empty();
        }
        BigDecimal expense = journalSum(params(scope), scope, "CASE WHEN l.direction = 'DEBIT' THEN l.amount ELSE -l.amount END",
                " AND l.account_code = '5100' AND j.posted_at >= :periodStart AND j.posted_at < :periodEnd ");
        String cases = """
                SELECT COUNT(*) AS cases, COALESCE(SUM(cc.collection_exposure), 0) AS exposure
                FROM collection_cases cc
                JOIN loan_accounts la ON la.id = cc.loan_account_id
                JOIN loan_applications app ON app.id = la.application_id
                %s
                WHERE cc.closure_outcome = 'UNCOLLECTIBLE'
                  AND cc.closed_at >= :periodStart AND cc.closed_at < :periodEnd
                  AND %s
                """.formatted(ScopeSql.classification("la.application_id"), ScopeSql.loanWhere(scope));
        String recoveries = """
                SELECT COALESCE(SUM(rt.amount), 0) AS recovery_amount,
                       COALESCE(SUM(r.amount) FILTER (WHERE r.status = 'POSTED'), 0) AS posted_amount,
                       COUNT(*) AS recovery_count,
                       COUNT(*) FILTER (WHERE r.status <> 'POSTED') AS unposted
                FROM recovery_transactions rt
                JOIN loan_repayments r ON r.id = rt.repayment_id
                JOIN loan_accounts la ON la.id = rt.loan_account_id
                JOIN loan_applications app ON app.id = la.application_id
                %s
                WHERE rt.recorded_at >= :periodStart AND rt.recorded_at < :periodEnd
                  AND %s
                """.formatted(ScopeSql.classification("la.application_id"), ScopeSql.loanWhere(scope));
        MapSqlParameterSource params = params(scope);
        return jdbc.queryForObject(cases, params, (closure, row) -> jdbc.queryForObject(recoveries, params, (recovery, ignored) -> new WriteOffMetrics(
                expense, closure.getLong("cases"), money(closure, "exposure"), money(recovery, "recovery_amount"),
                money(recovery, "posted_amount"), recovery.getLong("recovery_count"), recovery.getLong("unposted"))));
    }

    @Override
    public long openCases(ReportingScope scope) {
        if (blocked(scope)) {
            return 0;
        }
        String sql = """
                SELECT COUNT(*)
                FROM collection_cases cc
                JOIN loan_accounts la ON la.id = cc.loan_account_id
                JOIN loan_applications app ON app.id = la.application_id
                %s
                WHERE cc.status <> 'CLOSED' AND %s
                """.formatted(ScopeSql.classification("la.application_id"), ScopeSql.loanWhere(scope));
        return count(sql, params(scope));
    }

    @Override
    public List<GroupMetrics> portfolioGroups(ReportingScope scope, GroupDimension dimension) {
        if (blocked(scope)) {
            return List.of();
        }
        Grouping grouping = portfolioGrouping(dimension);
        String sql = ScopeSql.portfolioCte(scope) + """
                 SELECT %s AS group_id, %s AS code, MAX(%s) AS name,
                        COUNT(*) AS loan_count,
                        COALESCE(SUM(p.principal), 0) AS outstanding_principal,
                        COALESCE(SUM(p.principal) FILTER (WHERE p.days_past_due >= 1), 0) AS overdue_principal,
                        %s AS par30_principal,
                        %s AS par60_principal,
                        %s AS par90_principal
                 FROM portfolio p
                 GROUP BY %s
                 ORDER BY code
                """.formatted(grouping.id(), grouping.code(), grouping.name(),
                ScopeSql.parPrincipal("p.principal", "p.days_past_due", ReportingCalculator.PAR_30),
                ScopeSql.parPrincipal("p.principal", "p.days_past_due", ReportingCalculator.PAR_60),
                ScopeSql.parPrincipal("p.principal", "p.days_past_due", ReportingCalculator.PAR_90),
                grouping.groupBy());
        return jdbc.query(sql, params(scope), (rs, row) -> GroupMetrics.stock(
                uuid(rs, "group_id"), rs.getString("code"), rs.getString("name"), rs.getLong("loan_count"),
                money(rs, "outstanding_principal"), money(rs, "overdue_principal"), money(rs, "par30_principal"),
                money(rs, "par60_principal"), money(rs, "par90_principal")));
    }

    @Override
    public List<GroupMetrics> movementGroups(ReportingScope scope, GroupDimension dimension, LedgerMovement movement) {
        if (blocked(scope)) {
            return List.of();
        }
        Grouping grouping = loanGrouping(dimension);
        String sql = switch (movement) {
            case GL_DISBURSEMENT, GL_COLLECTION, GL_INTEREST, GL_FEE -> glGroupSql(scope, grouping, movement);
            case BOOK_DISBURSEMENT -> bookGroupSql(scope, grouping, true);
            case BOOK_COLLECTION -> bookCollectionGroupSql(scope, grouping);
        };
        return jdbc.query(sql, params(scope), (rs, row) -> movementRow(rs, movement));
    }

    @Override
    public List<GroupMetrics> applicationGroups(ReportingScope scope, GroupDimension dimension) {
        if (blocked(scope)) {
            return List.of();
        }
        Grouping grouping = applicationGrouping(dimension);
        String sql = """
                SELECT %s AS group_id, %s AS code, %s AS name,
                       COUNT(*) AS applications,
                       COUNT(*) FILTER (WHERE app.status IN (%s)) AS pending
                FROM loan_applications app
                %s
                %s
                WHERE %s
                GROUP BY %s
                ORDER BY code
                """.formatted(grouping.id(), grouping.code(), grouping.name(), ReportingRules.sqlList(ReportingRules.PENDING),
                ScopeSql.classification("app.id"), grouping.joins(), ScopeSql.applicationWhere(scope, true), grouping.groupBy());
        return jdbc.query(sql, params(scope), (rs, row) -> new GroupMetrics(
                uuid(rs, "group_id"), rs.getString("code"), rs.getString("name"), 0, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO,
                rs.getLong("applications"), rs.getLong("pending")));
    }

    @Override
    public DetailPage details(ReportCode code, ReportingScope scope, int limit, int offset, int page, int size) {
        if (blocked(scope)) {
            return new DetailPage(List.of(), page, size, 0);
        }
        Plan plan = DetailPlans.of(code, scope);
        long total = count("SELECT COUNT(*) FROM (" + plan.sql() + ") counted", params(scope));
        List<com.mfb.lending.module.reporting.domain.ReportingModels.LoanLine> lines = jdbc.query(
                plan.sql() + " ORDER BY " + plan.orderBy() + " LIMIT :limit OFFSET :offset",
                params(scope).addValue("limit", limit).addValue("offset", offset),
                (rs, row) -> DetailPlans.line(plan, rs));
        return new DetailPage(lines, page, size, total);
    }

    @Override
    public long countDetails(ReportCode code, ReportingScope scope) {
        if (blocked(scope)) {
            return 0;
        }
        Plan plan = DetailPlans.of(code, scope);
        return count("SELECT COUNT(*) FROM (" + plan.sql() + ") counted", params(scope));
    }

    @Override
    public List<String> detailHeaders(ReportCode code, ReportingScope scope) {
        return DetailPlans.of(code, scope).headers();
    }

    @Override
    @Transactional(readOnly = true)
    public long streamDetails(ReportCode code, ReportingScope scope, int maxRows, Consumer<List<String>> rows) {
        if (blocked(scope)) {
            return 0;
        }
        Plan plan = DetailPlans.of(code, scope);
        JdbcTemplate streaming = new JdbcTemplate(jdbc.getJdbcTemplate().getDataSource());
        streaming.setFetchSize(200);
        NamedParameterJdbcTemplate named = new NamedParameterJdbcTemplate(streaming);
        long[] written = {0};
        named.query(plan.sql() + " ORDER BY " + plan.orderBy() + " LIMIT :maxRows",
                params(scope).addValue("maxRows", maxRows),
                rs -> {
                    rows.accept(DetailPlans.cells(plan, rs));
                    written[0]++;
                });
        return written[0];
    }

    @Override
    public void insertExport(ExportJob job) {
        jdbc.update("""
                INSERT INTO report_exports (
                    id, report_code, format, status, filters, requested_by, requested_by_name, file_name, content_type,
                    storage_path, row_count, token_hash, error_message, expires_at, created_at, started_at, completed_at)
                VALUES (
                    :id, :reportCode, :format, :status, CAST(:filters AS jsonb), :requestedBy, :requestedByName, :fileName,
                    :contentType, :storagePath, :rowCount, :tokenHash, :errorMessage, :expiresAt, :createdAt, :startedAt, :completedAt)
                """, exportParams(job));
    }

    @Override
    @Transactional
    public Optional<ExportJob> lockNextPending(Instant now) {
        List<ExportJob> jobs = jdbc.query("""
                UPDATE report_exports
                SET status = 'RUNNING', started_at = :now
                WHERE id = (
                    SELECT id FROM report_exports
                    WHERE status = 'PENDING' AND expires_at > :now
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1)
                RETURNING id, report_code, format, status, filters::text AS filters, requested_by, requested_by_name,
                          file_name, content_type, storage_path, row_count, token_hash, error_message, expires_at,
                          created_at, started_at, completed_at, downloaded_at
                """, new MapSqlParameterSource("now", Timestamp.from(now)), (rs, row) -> mapExport(rs));
        return jobs.stream().findFirst();
    }

    @Override
    public void markReady(UUID id, int rowCount, String path, String fileName, String contentType, Instant completedAt) {
        jdbc.update("""
                UPDATE report_exports
                SET status = 'READY', row_count = :rowCount, storage_path = :path, file_name = :fileName,
                    content_type = :contentType, completed_at = :completedAt, error_message = NULL
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("rowCount", rowCount)
                .addValue("path", path)
                .addValue("fileName", fileName)
                .addValue("contentType", contentType)
                .addValue("completedAt", Timestamp.from(completedAt)));
    }

    @Override
    public void markFailed(UUID id, String message, Instant completedAt) {
        jdbc.update("""
                UPDATE report_exports
                SET status = 'FAILED', error_message = :message, completed_at = :completedAt
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("message", message)
                .addValue("completedAt", Timestamp.from(completedAt)));
    }

    @Override
    public void markExpired(UUID id) {
        jdbc.update("UPDATE report_exports SET status = 'EXPIRED' WHERE id = :id AND status = 'READY'", new MapSqlParameterSource("id", id));
    }

    @Override
    public List<String> expireReady(Instant now) {
        return jdbc.query("""
                UPDATE report_exports SET status = 'EXPIRED'
                WHERE status = 'READY' AND expires_at <= :now
                RETURNING storage_path
                """, new MapSqlParameterSource("now", Timestamp.from(now)), (rs, row) -> rs.getString("storage_path"));
    }

    @Override
    public Optional<ExportJob> findExport(UUID id) {
        List<ExportJob> jobs = jdbc.query(exportSelect() + " WHERE id = :id", new MapSqlParameterSource("id", id), (rs, row) -> mapExport(rs));
        return jobs.stream().findFirst();
    }

    @Override
    public int activeExports(UUID userId) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM report_exports
                WHERE requested_by = :userId AND status IN ('PENDING', 'RUNNING')
                """, new MapSqlParameterSource("userId", userId), Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public void touchDownload(UUID id, Instant at) {
        jdbc.update("UPDATE report_exports SET downloaded_at = :at WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("at", Timestamp.from(at)));
    }

    @Override
    public void captureSnapshot(LocalDate date, UUID branchKey, SnapshotRow row, Instant capturedAt) {
        jdbc.update("""
                INSERT INTO reporting_snapshots (
                    id, snapshot_date, branch_key, currency_code, loan_count, outstanding_principal, outstanding_interest,
                    overdue_principal, par30_ratio, par60_ratio, par90_ratio, default_rate, disbursement_principal,
                    collection_cash, gl_principal, principal_variance, captured_at)
                VALUES (
                    :id, :snapshotDate, :branchKey, 'NGN', :loanCount, :outstandingPrincipal, :outstandingInterest,
                    :overduePrincipal, :par30, :par60, :par90, :defaultRate, :disbursement, :collection, :glPrincipal,
                    :variance, :capturedAt)
                ON CONFLICT (snapshot_date, branch_key) DO UPDATE SET
                    loan_count = EXCLUDED.loan_count,
                    outstanding_principal = EXCLUDED.outstanding_principal,
                    outstanding_interest = EXCLUDED.outstanding_interest,
                    overdue_principal = EXCLUDED.overdue_principal,
                    par30_ratio = EXCLUDED.par30_ratio,
                    par60_ratio = EXCLUDED.par60_ratio,
                    par90_ratio = EXCLUDED.par90_ratio,
                    default_rate = EXCLUDED.default_rate,
                    disbursement_principal = EXCLUDED.disbursement_principal,
                    collection_cash = EXCLUDED.collection_cash,
                    gl_principal = EXCLUDED.gl_principal,
                    principal_variance = EXCLUDED.principal_variance,
                    captured_at = EXCLUDED.captured_at
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("snapshotDate", date)
                .addValue("branchKey", branchKey)
                .addValue("loanCount", row.loanCount())
                .addValue("outstandingPrincipal", row.outstandingPrincipal())
                .addValue("outstandingInterest", row.outstandingInterest())
                .addValue("overduePrincipal", row.overduePrincipal())
                .addValue("par30", row.par30Ratio())
                .addValue("par60", row.par60Ratio())
                .addValue("par90", row.par90Ratio())
                .addValue("defaultRate", row.defaultRate())
                .addValue("disbursement", row.disbursementPrincipal())
                .addValue("collection", row.collectionCash())
                .addValue("glPrincipal", row.generalLedgerPrincipal())
                .addValue("variance", row.principalVariance())
                .addValue("capturedAt", Timestamp.from(capturedAt)));
    }

    @Override
    public Optional<LocalDate> latestSnapshot(UUID branchKey) {
        List<LocalDate> dates = jdbc.query("""
                SELECT MAX(snapshot_date) AS snapshot_date FROM reporting_snapshots WHERE branch_key = :branchKey
                """, new MapSqlParameterSource("branchKey", branchKey), (rs, row) -> rs.getObject("snapshot_date", LocalDate.class));
        return dates.stream().filter(date -> date != null).findFirst();
    }

    @Override
    public void refreshPortfolioView() {
        TransactionTemplate template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        template.executeWithoutResult(status -> jdbc.getJdbcTemplate().execute("REFRESH MATERIALIZED VIEW CONCURRENTLY mv_portfolio_position"));
    }

    @Override
    public void recordJob(UUID id, String name, LocalDate date, int processed, Instant started) {
        jdbc.update("""
                INSERT INTO reporting_job_runs (id, job_name, business_date, processed, started_at)
                VALUES (:id, :name, :businessDate, :processed, :startedAt)
                """, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("name", name)
                .addValue("businessDate", date)
                .addValue("processed", processed)
                .addValue("startedAt", Timestamp.from(started)));
    }

    private String glGroupSql(ReportingScope scope, Grouping grouping, LedgerMovement movement) {
        String amount = switch (movement) {
            case GL_DISBURSEMENT -> "CASE WHEN l.direction = 'DEBIT' THEN l.amount ELSE -l.amount END";
            case GL_COLLECTION -> "CASE WHEN l.direction = 'DEBIT' THEN l.amount ELSE -l.amount END";
            case GL_INTEREST, GL_FEE -> "CASE WHEN l.direction = 'CREDIT' THEN l.amount ELSE -l.amount END";
            default -> throw new IllegalArgumentException(movement.name());
        };
        String account = switch (movement) {
            case GL_DISBURSEMENT -> "1200";
            case GL_COLLECTION -> "2100";
            case GL_INTEREST -> "4200";
            case GL_FEE -> "4100";
            default -> throw new IllegalArgumentException(movement.name());
        };
        String event = switch (movement) {
            case GL_DISBURSEMENT -> ScopeSql.DISBURSEMENT_EVENT;
            case GL_COLLECTION -> ScopeSql.REPAYMENT_EVENT;
            default -> "";
        };
        return """
                SELECT %s AS group_id, %s AS code, %s AS name, COUNT(DISTINCT la.id) AS loan_count, COALESCE(SUM(%s), 0) AS amount
                %s
                %s
                WHERE j.status = 'POSTED' AND %s
                  AND l.account_code = '%s'
                  AND j.posted_at >= :periodStart AND j.posted_at < :periodEnd
                  %s
                GROUP BY %s
                ORDER BY code
                """.formatted(grouping.id(), grouping.code(), grouping.name(), amount, journalBody(), grouping.joins(),
                ScopeSql.loanWhere(scope), account, event, grouping.groupBy());
    }

    private String bookGroupSql(ReportingScope scope, Grouping grouping, boolean disbursement) {
        return """
                SELECT %s AS group_id, %s AS code, %s AS name, COUNT(*) AS loan_count,
                       COALESCE(SUM(la.original_principal), 0) AS amount
                FROM loan_accounts la
                JOIN loan_applications app ON app.id = la.application_id
                %s
                %s
                WHERE %s
                  AND la.status <> 'PENDING_ACTIVATION'
                  AND la.disbursement_date >= :dueFrom AND la.disbursement_date < :dueTo
                GROUP BY %s
                ORDER BY code
                """.formatted(grouping.id(), grouping.code(), grouping.name(), ScopeSql.classification("la.application_id"),
                grouping.joins(), ScopeSql.loanWhere(scope), grouping.groupBy());
    }

    private String bookCollectionGroupSql(ReportingScope scope, Grouping grouping) {
        return """
                SELECT %s AS group_id, %s AS code, %s AS name, COUNT(DISTINCT la.id) AS loan_count,
                       COALESCE(SUM(CASE WHEN j.event_code = 'REVERSAL' THEN -r.amount ELSE r.amount END), 0) AS amount
                FROM loan_repayments r
                JOIN journal_entries j ON (
                    (j.id = r.cash_journal_id AND j.event_code = 'REPAYMENT')
                    OR (j.event_code = 'REVERSAL' AND j.reversal_of = r.cash_journal_id))
                JOIN loan_accounts la ON la.id = r.loan_account_id
                JOIN loan_applications app ON app.id = la.application_id
                %s
                %s
                WHERE j.status = 'POSTED' AND j.posted_at >= :periodStart AND j.posted_at < :periodEnd AND %s
                GROUP BY %s
                ORDER BY code
                """.formatted(grouping.id(), grouping.code(), grouping.name(), ScopeSql.classification("la.application_id"),
                grouping.joins(), ScopeSql.loanWhere(scope), grouping.groupBy());
    }

    private BigDecimal journalSum(MapSqlParameterSource params, ReportingScope scope, String amount, String extra) {
        String sql = "SELECT COALESCE(SUM(" + amount + "), 0) " + journalBody()
                + " WHERE j.status = 'POSTED' AND " + ScopeSql.loanWhere(scope) + extra;
        return scalar(sql, params);
    }

    private String bookDisbursementSql(ReportingScope scope) {
        return """
                SELECT COALESCE(SUM(la.original_principal), 0)
                FROM loan_accounts la
                JOIN loan_applications app ON app.id = la.application_id
                %s
                WHERE %s
                  AND la.status <> 'PENDING_ACTIVATION'
                  AND la.disbursement_date >= :dueFrom AND la.disbursement_date < :dueTo
                """.formatted(ScopeSql.classification("la.application_id"), ScopeSql.loanWhere(scope));
    }

    private String bookDisbursementCountSql(ReportingScope scope) {
        return """
                SELECT COUNT(*)
                FROM loan_accounts la
                JOIN loan_applications app ON app.id = la.application_id
                %s
                WHERE %s
                  AND la.status <> 'PENDING_ACTIVATION'
                  AND la.disbursement_date >= :dueFrom AND la.disbursement_date < :dueTo
                """.formatted(ScopeSql.classification("la.application_id"), ScopeSql.loanWhere(scope));
    }

    private String bookCollectionSql(ReportingScope scope) {
        return """
                SELECT COALESCE(SUM(CASE WHEN j.event_code = 'REVERSAL' THEN -r.amount ELSE r.amount END), 0)
                FROM loan_repayments r
                JOIN journal_entries j ON (
                    (j.id = r.cash_journal_id AND j.event_code = 'REPAYMENT')
                    OR (j.event_code = 'REVERSAL' AND j.reversal_of = r.cash_journal_id))
                JOIN loan_accounts la ON la.id = r.loan_account_id
                JOIN loan_applications app ON app.id = la.application_id
                %s
                WHERE j.status = 'POSTED' AND j.posted_at >= :periodStart AND j.posted_at < :periodEnd AND %s
                """.formatted(ScopeSql.classification("la.application_id"), ScopeSql.loanWhere(scope));
    }

    private String dueSql(ReportingScope scope) {
        return """
                SELECT COALESCE(SUM(item.instalment_amount), 0)
                FROM loan_schedule_items item
                JOIN loan_accounts la ON la.current_schedule_id = item.schedule_id
                JOIN loan_applications app ON app.id = la.application_id
                %s
                WHERE %s AND item.due_date >= :dueFrom AND item.due_date < :dueTo
                """.formatted(ScopeSql.classification("la.application_id"), ScopeSql.loanWhere(scope));
    }

    private String accrualSql(ReportingScope scope) {
        return """
                SELECT COALESCE(SUM(a.amount), 0)
                FROM interest_accruals a
                JOIN journal_entries j ON j.id = a.journal_id
                JOIN loan_accounts la ON la.id = a.loan_account_id
                JOIN loan_applications app ON app.id = la.application_id
                %s
                WHERE a.component = 'INTEREST' AND j.status = 'POSTED'
                  AND j.posted_at >= :periodStart AND j.posted_at < :periodEnd AND %s
                """.formatted(ScopeSql.classification("la.application_id"), ScopeSql.loanWhere(scope));
    }

    private String chargeSql(ReportingScope scope) {
        return """
                SELECT COALESCE(SUM(CASE
                    WHEN j.id = c.journal_id THEN c.amount
                    WHEN j.event_code = 'REVERSAL' AND j.reversal_of = c.journal_id THEN -c.amount
                    ELSE 0 END), 0)
                FROM loan_charges c
                JOIN journal_entries j ON j.id = c.journal_id OR (j.event_code = 'REVERSAL' AND j.reversal_of = c.journal_id)
                JOIN loan_accounts la ON la.id = c.loan_account_id
                JOIN loan_applications app ON app.id = la.application_id
                %s
                WHERE j.status = 'POSTED' AND j.posted_at >= :periodStart AND j.posted_at < :periodEnd AND %s
                """.formatted(ScopeSql.classification("la.application_id"), ScopeSql.loanWhere(scope));
    }

    private static String journalBody() {
        return """
                FROM journal_entries j
                JOIN journal_entry_lines l ON l.journal_entry_id = j.id
                JOIN loan_accounts la ON la.id = j.loan_account_id
                JOIN loan_applications app ON app.id = la.application_id
                """ + ScopeSql.classification("la.application_id");
    }

    private GroupMetrics movementRow(ResultSet rs, LedgerMovement movement) throws SQLException {
        UUID id = uuid(rs, "group_id");
        String code = rs.getString("code");
        String name = rs.getString("name");
        long count = rs.getLong("loan_count");
        BigDecimal amount = money(rs, "amount");
        return switch (movement) {
            case GL_DISBURSEMENT, BOOK_DISBURSEMENT -> new GroupMetrics(id, code, name, count, ZERO, ZERO, ZERO, ZERO, ZERO, amount, ZERO, ZERO, 0, 0);
            case GL_COLLECTION, BOOK_COLLECTION -> new GroupMetrics(id, code, name, count, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, amount, ZERO, 0, 0);
            case GL_INTEREST, GL_FEE -> new GroupMetrics(id, code, name, count, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, ZERO, amount, 0, 0);
        };
    }

    private static Grouping portfolioGrouping(GroupDimension dimension) {
        return switch (dimension) {
            case BRANCH -> new Grouping(
                    "COALESCE(p.branch_id, '00000000-0000-0000-0000-000000000000'::uuid)",
                    "p.branch_code", "p.branch_name", "",
                    "COALESCE(p.branch_id, '00000000-0000-0000-0000-000000000000'::uuid), p.branch_code");
            case PRODUCT -> new Grouping("p.product_id", "p.product_code", "p.product_name", "", "p.product_id, p.product_code");
            case SEGMENT -> new Grouping("NULL::uuid", "p.segment", "p.segment", "", "p.segment");
            case SECURITY -> new Grouping("NULL::uuid", "p.security_class", "p.security_class", "", "p.security_class");
            case RISK -> new Grouping("NULL::uuid", "p.risk_category", "p.risk_category", "", "p.risk_category");
        };
    }

    private static Grouping loanGrouping(GroupDimension dimension) {
        return switch (dimension) {
            case BRANCH -> new Grouping(
                    "COALESCE(la.branch_id, '00000000-0000-0000-0000-000000000000'::uuid)",
                    "COALESCE(b.code, 'UNASSIGNED')", "COALESCE(b.name, 'Unassigned')",
                    "LEFT JOIN branches b ON b.id = la.branch_id",
                    "COALESCE(la.branch_id, '00000000-0000-0000-0000-000000000000'::uuid), COALESCE(b.code, 'UNASSIGNED'), COALESCE(b.name, 'Unassigned')");
            case PRODUCT -> new Grouping("la.product_id", "prod.product_code", "pv.product_name",
                    "JOIN loan_products prod ON prod.id = la.product_id JOIN loan_product_versions pv ON pv.id = la.product_version_id",
                    "la.product_id, prod.product_code, pv.product_name");
            case SEGMENT -> new Grouping("NULL::uuid", "app.customer_segment", "app.customer_segment", "", "app.customer_segment");
            case SECURITY -> new Grouping("NULL::uuid", "security.security_class", "security.security_class", "", "security.security_class");
            case RISK -> new Grouping("NULL::uuid", "COALESCE(risk.risk_category, 'UNCLASSIFIED')", "COALESCE(risk.risk_category, 'UNCLASSIFIED')", "",
                    "COALESCE(risk.risk_category, 'UNCLASSIFIED')");
        };
    }

    private static Grouping applicationGrouping(GroupDimension dimension) {
        return switch (dimension) {
            case BRANCH -> new Grouping(
                    "COALESCE(app.branch_id, '00000000-0000-0000-0000-000000000000'::uuid)",
                    "COALESCE(b.code, 'UNASSIGNED')", "COALESCE(b.name, 'Unassigned')",
                    "LEFT JOIN branches b ON b.id = app.branch_id",
                    "COALESCE(app.branch_id, '00000000-0000-0000-0000-000000000000'::uuid), COALESCE(b.code, 'UNASSIGNED'), COALESCE(b.name, 'Unassigned')");
            case PRODUCT -> new Grouping("app.product_id", "prod.product_code", "pv.product_name",
                    "JOIN loan_products prod ON prod.id = app.product_id JOIN loan_product_versions pv ON pv.id = app.product_version_id",
                    "app.product_id, prod.product_code, pv.product_name");
            case SEGMENT -> new Grouping("NULL::uuid", "app.customer_segment", "app.customer_segment", "", "app.customer_segment");
            case SECURITY -> new Grouping("NULL::uuid", "security.security_class", "security.security_class", "", "security.security_class");
            case RISK -> new Grouping("NULL::uuid", "COALESCE(risk.risk_category, 'UNCLASSIFIED')", "COALESCE(risk.risk_category, 'UNCLASSIFIED')", "",
                    "COALESCE(risk.risk_category, 'UNCLASSIFIED')");
        };
    }

    private MapSqlParameterSource params(ReportingScope scope) {
        return new MapSqlParameterSource()
                .addValue("branchIds", scope.branchIds().isEmpty() ? List.of(ReportingScope.UNASSIGNED_BRANCH) : scope.branchIds())
                .addValue("branchId", scope.branchId())
                .addValue("productId", scope.productId())
                .addValue("segment", scope.segment())
                .addValue("loanStatus", scope.loanStatus())
                .addValue("riskCategory", scope.riskCategory())
                .addValue("officerId", scope.officerId())
                .addValue("securityClass", scope.securityClass())
                .addValue("repaymentStatus", scope.repaymentStatus())
                .addValue("periodStart", Timestamp.from(scope.periodStart()))
                .addValue("periodEnd", Timestamp.from(scope.periodEndExclusive()))
                .addValue("dueFrom", scope.dueFrom())
                .addValue("dueTo", scope.dueToExclusive())
                .addValue("stockDate", scope.stockDate())
                .addValue("stockExclusive", Timestamp.from(scope.stockExclusive()))
                .addValue("zone", scope.zone().getId());
    }

    private static MapSqlParameterSource customer(UUID customerId) {
        if (customerId == null) {
            throw new IllegalArgumentException("A customer report requires the authenticated customer");
        }
        return new MapSqlParameterSource("customerId", customerId);
    }

    private static boolean blocked(ReportingScope scope) {
        return !scope.unrestricted() && scope.branchIds().isEmpty();
    }

    private BigDecimal scalar(String sql, MapSqlParameterSource params) {
        BigDecimal value = jdbc.queryForObject(sql, params, BigDecimal.class);
        return value == null ? ZERO : value;
    }

    private long count(String sql, MapSqlParameterSource params) {
        Long value = jdbc.queryForObject(sql, params, Long.class);
        return value == null ? 0 : value;
    }

    private static BigDecimal money(ResultSet rs, String column) throws SQLException {
        BigDecimal value = rs.getBigDecimal(column);
        return value == null ? ZERO : value;
    }

    private static BigDecimal nullableMoney(ResultSet rs, String column) throws SQLException {
        return rs.getBigDecimal(column);
    }

    private static BigDecimal nullableDecimal(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : new BigDecimal(value);
    }

    private static UUID uuid(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, UUID.class);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static String exportSelect() {
        return """
                SELECT id, report_code, format, status, filters::text AS filters, requested_by, requested_by_name,
                       file_name, content_type, storage_path, row_count, token_hash, error_message, expires_at,
                       created_at, started_at, completed_at, downloaded_at
                FROM report_exports
                """;
    }

    private static ExportJob mapExport(ResultSet rs) throws SQLException {
        return new ExportJob(
                rs.getObject("id", UUID.class),
                ReportCode.valueOf(rs.getString("report_code")),
                ExportFormat.valueOf(rs.getString("format")),
                ExportStatus.valueOf(rs.getString("status")),
                rs.getString("filters"),
                rs.getObject("requested_by", UUID.class),
                rs.getString("requested_by_name"),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getString("storage_path"),
                (Integer) rs.getObject("row_count"),
                rs.getString("token_hash"),
                rs.getString("error_message"),
                instant(rs, "expires_at"),
                instant(rs, "created_at"),
                instant(rs, "started_at"),
                instant(rs, "completed_at"),
                instant(rs, "downloaded_at"));
    }

    private static MapSqlParameterSource exportParams(ExportJob job) {
        return new MapSqlParameterSource()
                .addValue("id", job.id())
                .addValue("reportCode", job.reportCode().name())
                .addValue("format", job.format().name())
                .addValue("status", job.status().name())
                .addValue("filters", job.filters())
                .addValue("requestedBy", job.requestedBy())
                .addValue("requestedByName", job.requestedByName())
                .addValue("fileName", job.fileName())
                .addValue("contentType", job.contentType())
                .addValue("storagePath", job.storagePath())
                .addValue("rowCount", job.rowCount())
                .addValue("tokenHash", job.tokenHash())
                .addValue("errorMessage", job.errorMessage())
                .addValue("expiresAt", Timestamp.from(job.expiresAt()))
                .addValue("createdAt", Timestamp.from(job.createdAt()))
                .addValue("startedAt", job.startedAt() == null ? null : Timestamp.from(job.startedAt()))
                .addValue("completedAt", job.completedAt() == null ? null : Timestamp.from(job.completedAt()));
    }

    private record Grouping(String id, String code, String name, String joins, String groupBy) {
    }
}
