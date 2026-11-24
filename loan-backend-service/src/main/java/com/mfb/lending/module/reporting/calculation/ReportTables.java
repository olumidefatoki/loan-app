package com.mfb.lending.module.reporting.calculation;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.mfb.lending.module.reporting.domain.ReportingModels.BalanceMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.FlowMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.MeasureRow;
import com.mfb.lending.module.reporting.domain.ReportingModels.WriteOffMetrics;

public final class ReportTables {

    public static final List<String> RECONCILIATION_HEADERS = List.of(
            "Measure", "Subledger", "General ledger", "Variance", "Note");

    private ReportTables() {
    }

    public static List<MeasureRow> reconciliation(BalanceMetrics balances, FlowMetrics flow, WriteOffMetrics writeOff) {
        List<MeasureRow> rows = new ArrayList<>();
        rows.add(balance("Outstanding principal", balances.subledgerPrincipal(), balances.generalLedgerPrincipal(), "1200"));
        rows.add(balance("Outstanding interest", balances.subledgerInterest(), balances.generalLedgerInterest(), "1300"));
        rows.add(balance("Outstanding fees", balances.subledgerFees(), balances.generalLedgerFees(), "1400"));
        rows.add(balance("Outstanding penalties", balances.subledgerPenalties(), balances.generalLedgerPenalties(), "1500"));
        rows.add(balance("Customer overpayment", balances.subledgerOverpayment(), balances.generalLedgerOverpayment(), "2200"));
        rows.add(period("Period disbursement", flow.subledgerDisbursement(), flow.generalLedgerDisbursement(), "1200"));
        rows.add(period("Period collections", flow.subledgerCollection(), flow.generalLedgerCollection(), "2100"));
        rows.add(period("Period interest income", flow.interestSubledger(), flow.generalLedgerInterestIncome(), "4200"));
        rows.add(period("Period fee income", flow.feeSubledger(), flow.generalLedgerFeeIncome(), "4100"));
        rows.add(new MeasureRow(
                "Write-off expense",
                ReportingCalculator.ledger(writeOff.generalLedgerExpense()),
                ReportingCalculator.ledger(writeOff.generalLedgerExpense()),
                ReportingCalculator.ledger(BigDecimal.ZERO),
                "Account 5100. Uncollectible cases are not included in this tie-out."));
        rows.add(new MeasureRow(
                "Confirmed recoveries",
                ReportingCalculator.ledger(writeOff.recoveryAmount()),
                ReportingCalculator.ledger(writeOff.linkedPostedRepayment()),
                ReportingCalculator.variance(writeOff.recoveryAmount(), writeOff.linkedPostedRepayment()),
                "Subledger is the confirmed recovery amount. General ledger column is the linked POSTED repayment amount."));
        return List.copyOf(rows);
    }

    public static List<List<String>> cells(List<MeasureRow> rows) {
        return rows.stream().map(row -> List.of(
                row.measure(),
                row.subledger().toPlainString(),
                row.generalLedger().toPlainString(),
                row.variance().toPlainString(),
                row.note())).toList();
    }

    private static MeasureRow balance(String name, BigDecimal subledger, BigDecimal generalLedger, String account) {
        return new MeasureRow(name, ReportingCalculator.ledger(subledger), ReportingCalculator.ledger(generalLedger),
                ReportingCalculator.variance(subledger, generalLedger), "Current book. Account " + account + ".");
    }

    private static MeasureRow period(String name, BigDecimal subledger, BigDecimal generalLedger, String account) {
        return new MeasureRow(name, ReportingCalculator.ledger(subledger), ReportingCalculator.ledger(generalLedger),
                ReportingCalculator.variance(subledger, generalLedger), "Selected period. Account " + account + ".");
    }
}
