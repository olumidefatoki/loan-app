package com.mfb.lending.module.reporting.application;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mfb.lending.module.reporting.calculation.ReportingCalculator;
import com.mfb.lending.module.reporting.domain.GroupDimension;
import com.mfb.lending.module.reporting.domain.LedgerMovement;
import com.mfb.lending.module.reporting.domain.ReportingModels.GroupMetrics;
import com.mfb.lending.module.reporting.dto.ReportResponses.GroupRow;
import com.mfb.lending.module.reporting.persistence.ReportingStore;

final class PortfolioGroups {

    static final List<String> HEADERS = List.of(
            "Code", "Name", "Loans", "Outstanding principal", "Overdue principal", "PAR 30", "PAR 60", "PAR 90",
            "Disbursement", "Collections", "Income", "Applications", "Pending");

    private PortfolioGroups() {
    }

    static List<GroupRow> composition(ReportingStore store, ReportingScope scope, GroupDimension dimension) {
        return merge(store.portfolioGroups(scope, dimension), List.of(), List.of(), List.of(), List.of());
    }

    static List<GroupRow> performance(ReportingStore store, ReportingScope scope, GroupDimension dimension, boolean generalLedger) {
        LedgerMovement disbursement = generalLedger ? LedgerMovement.GL_DISBURSEMENT : LedgerMovement.BOOK_DISBURSEMENT;
        LedgerMovement collection = generalLedger ? LedgerMovement.GL_COLLECTION : LedgerMovement.BOOK_COLLECTION;
        LedgerMovement income = generalLedger ? LedgerMovement.GL_INTEREST : null;
        return merge(
                store.portfolioGroups(scope, dimension),
                store.movementGroups(scope, dimension, disbursement),
                store.movementGroups(scope, dimension, collection),
                income == null ? List.of() : store.movementGroups(scope, dimension, income),
                store.applicationGroups(scope, dimension));
    }

    static List<GroupRow> income(ReportingStore store, ReportingScope scope, LedgerMovement movement) {
        return merge(List.of(), List.of(), List.of(), store.movementGroups(scope, GroupDimension.PRODUCT, movement), List.of());
    }

    static List<String> cells(GroupRow row) {
        return List.of(
                text(row.code()), text(row.name()), Long.toString(row.loanCount()), money(row.outstandingPrincipal()),
                money(row.overduePrincipal()), money(row.par30()), money(row.par60()), money(row.par90()),
                money(row.disbursementPrincipal()), money(row.collectionCash()), money(row.income()),
                Long.toString(row.applications()), Long.toString(row.pendingApplications()));
    }

    private static List<GroupRow> merge(List<GroupMetrics> stock, List<GroupMetrics> disbursement, List<GroupMetrics> collection,
            List<GroupMetrics> income, List<GroupMetrics> applications) {
        Map<String, Accumulator> rows = new LinkedHashMap<>();
        stock.forEach(row -> rows.computeIfAbsent(key(row), ignored -> new Accumulator(row)).stock(row));
        disbursement.forEach(row -> rows.computeIfAbsent(key(row), ignored -> new Accumulator(row)).disbursement(row));
        collection.forEach(row -> rows.computeIfAbsent(key(row), ignored -> new Accumulator(row)).collection(row));
        income.forEach(row -> rows.computeIfAbsent(key(row), ignored -> new Accumulator(row)).income(row));
        applications.forEach(row -> rows.computeIfAbsent(key(row), ignored -> new Accumulator(row)).applications(row));
        return rows.values().stream().map(Accumulator::toRow).toList();
    }

    private static String key(GroupMetrics row) {
        return row.code() == null ? "UNASSIGNED" : row.code();
    }

    private static String text(String value) {
        return value == null ? "" : value;
    }

    private static String money(BigDecimal value) {
        return ReportingCalculator.ledger(value).toPlainString();
    }

    private static final class Accumulator {
        private final String code;
        private String name;
        private long loans;
        private BigDecimal outstanding = BigDecimal.ZERO;
        private BigDecimal overdue = BigDecimal.ZERO;
        private BigDecimal par30 = BigDecimal.ZERO;
        private BigDecimal par60 = BigDecimal.ZERO;
        private BigDecimal par90 = BigDecimal.ZERO;
        private BigDecimal disbursement = BigDecimal.ZERO;
        private BigDecimal collected = BigDecimal.ZERO;
        private BigDecimal income = BigDecimal.ZERO;
        private long applications;
        private long pending;

        private Accumulator(GroupMetrics row) {
            this.code = row.code();
            this.name = row.name();
        }

        private void stock(GroupMetrics row) {
            name = row.name() == null ? name : row.name();
            loans += row.loanCount();
            outstanding = outstanding.add(row.outstandingPrincipal());
            overdue = overdue.add(row.overduePrincipal());
            par30 = par30.add(row.par30Principal());
            par60 = par60.add(row.par60Principal());
            par90 = par90.add(row.par90Principal());
        }

        private void disbursement(GroupMetrics row) {
            disbursement = disbursement.add(row.disbursementPrincipal());
        }

        private void collection(GroupMetrics row) {
            collected = collected.add(row.collectionCash());
        }

        private void income(GroupMetrics row) {
            income = income.add(row.income());
            if (loans == 0) {
                loans = row.loanCount();
            }
        }

        private void applications(GroupMetrics row) {
            applications += row.applications();
            pending += row.pendingApplications();
        }

        private GroupRow toRow() {
            return new GroupRow(code, name, loans, ReportingCalculator.ledger(outstanding), ReportingCalculator.ledger(overdue),
                    ReportingCalculator.ratio(par30, outstanding), ReportingCalculator.ratio(par60, outstanding),
                    ReportingCalculator.ratio(par90, outstanding), ReportingCalculator.ledger(disbursement),
                    ReportingCalculator.ledger(collected), ReportingCalculator.ledger(income), applications, pending);
        }
    }
}
