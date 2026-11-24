package com.mfb.lending.module.reporting.calculation;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.mfb.lending.module.reporting.application.ReportWriters;
import com.mfb.lending.module.reporting.calculation.ReportingCalculator.Exposure;
import com.mfb.lending.module.reporting.calculation.ReportingCalculator.PortfolioMath;
import com.mfb.lending.module.reporting.domain.ExportFormat;
import com.mfb.lending.module.reporting.domain.ReportingModels.BalanceMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.FlowMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.MeasureRow;
import com.mfb.lending.module.reporting.domain.ReportingModels.WriteOffMetrics;
import com.mfb.lending.module.reporting.persistence.CustomerReportSql;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReportingCalculationTest {

    @Test
    void parIncludesANinetyDayLoanInEveryThresholdAndKeepsItInTheSixtyOneToNinetyBucket() {
        assertThat(ReportingCalculator.inPar(29, ReportingCalculator.PAR_30)).isFalse();
        assertThat(ReportingCalculator.inPar(30, ReportingCalculator.PAR_30)).isTrue();
        assertThat(ReportingCalculator.inPar(90, ReportingCalculator.PAR_30)).isTrue();
        assertThat(ReportingCalculator.inPar(90, ReportingCalculator.PAR_60)).isTrue();
        assertThat(ReportingCalculator.inPar(90, ReportingCalculator.PAR_90)).isTrue();
        assertThat(ReportingCalculator.inPar(89, ReportingCalculator.PAR_90)).isFalse();

        assertThat(ReportingRules.bucket(0)).isEqualTo("CURRENT");
        assertThat(ReportingRules.bucket(30)).isEqualTo("DPD_1_30");
        assertThat(ReportingRules.bucket(90)).isEqualTo("DPD_61_90");
        assertThat(ReportingRules.bucket(91)).isEqualTo("DPD_ABOVE_90");

        PortfolioMath portfolio = ReportingCalculator.summarise(List.of(
                new Exposure(new BigDecimal("100"), 90),
                new Exposure(new BigDecimal("50"), 0)));
        assertThat(portfolio.par30Ratio()).isEqualByComparingTo("0.666667");
        assertThat(portfolio.par60Ratio()).isEqualByComparingTo("0.666667");
        assertThat(portfolio.par90Ratio()).isEqualByComparingTo("0.666667");
        assertThat(portfolio.defaultRate()).isEqualByComparingTo("0.666667");
        assertThat(ReportingCalculator.ratio(new BigDecimal("10"), BigDecimal.ZERO)).isEqualByComparingTo("0.000000");
    }

    @Test
    void ratesAreUndefinedWhenTheDenominatorIsEmptyAndAveragesUseTheDisbursedCount() {
        assertThat(ReportingCalculator.definedRate(BigDecimal.TEN, BigDecimal.ZERO).defined()).isFalse();
        assertThat(ReportingCalculator.decisionRate(1, 1, 0).value()).isEqualByComparingTo("1.000000");
        assertThat(ReportingCalculator.decisionRate(1, 1, 3).value()).isEqualByComparingTo("0.250000");
        assertThat(ReportingCalculator.decisionRate(0, 0, 0).defined()).isFalse();
        assertThat(ReportingCalculator.averageLoan(new BigDecimal("100"), 0)).isEqualByComparingTo("0.000000");
        assertThat(ReportingCalculator.averageLoan(new BigDecimal("100"), 2)).isEqualByComparingTo("50.000000");
        assertThat(ReportingCalculator.variance(new BigDecimal("10"), new BigDecimal("8"))).isEqualByComparingTo("2.000000");
        assertThat(ReportingCalculator.turnaroundDays(null)).isNull();
        assertThat(ReportingCalculator.turnaroundDays(new BigDecimal("1.235"))).isEqualByComparingTo("1.24");
        assertThat(ReportingCalculator.recoveryException(null)).isTrue();
        assertThat(ReportingCalculator.recoveryException("PENDING")).isTrue();
        assertThat(ReportingCalculator.recoveryException("POSTED")).isFalse();
    }

    @Test
    void applicationStatusesPartitionEveryNonDraftStatusOnce() {
        List<String> parts = new ArrayList<>();
        parts.addAll(ReportingRules.PENDING);
        parts.addAll(ReportingRules.AWAITING_APPROVAL);
        parts.addAll(ReportingRules.APPROVED);
        parts.addAll(ReportingRules.REJECTED);
        parts.addAll(ReportingRules.DISBURSED);
        parts.addAll(ReportingRules.WITHDRAWN);
        parts.addAll(ReportingRules.CANCELLED);
        parts.addAll(ReportingRules.EXPIRED);
        assertThat(parts).doesNotHaveDuplicates();
        assertThat(new HashSet<>(parts)).isEqualTo(new HashSet<>(ReportingRules.NON_DRAFT));
        assertThat(parts).doesNotContain("DRAFT");
        assertThat(ReportingRules.PENDING).doesNotContain("PENDING_APPROVAL");
        assertThat(ReportingRules.AWAITING_APPROVAL).containsExactly("PENDING_APPROVAL");
        assertThat(ReportingRules.sqlList(List.of("ACTIVE"))).isEqualTo("'ACTIVE'");
    }

    @Test
    void reconciliationMatchesEqualBooksAndWriteOffExpenseTiesToItself() {
        BigDecimal zero = BigDecimal.ZERO;
        BalanceMetrics balances = new BalanceMetrics(zero, zero, zero, zero, zero, zero, zero, zero, zero, zero);
        FlowMetrics flow = FlowMetrics.empty();
        WriteOffMetrics writeOff = WriteOffMetrics.empty();
        List<MeasureRow> rows = ReportTables.reconciliation(balances, flow, writeOff);
        assertThat(rows).allSatisfy(row -> assertThat(row.variance()).isEqualByComparingTo("0"));
        assertThat(rows).anySatisfy(row -> assertThat(row.measure()).isEqualTo("Write-off expense"));
    }

    @Test
    void namesAreShortenedAndCsvCellsCannotStartAFormula() {
        assertThat(ReportMask.displayName("Ada Lovelace")).isEqualTo("Ada L.");
        assertThat(ReportMask.displayName("Ada")).isEqualTo("A***");
        assertThat(ReportMask.csv("=1+1", false)).isEqualTo("\"'=1+1\"");
        assertThat(ReportMask.csv("+cmd", false)).startsWith("\"'");
        assertThat(ReportMask.csv("10.50", true)).isEqualTo("\"10.50\"");
        assertThat(ReportMask.plainNumber("-10.50")).isTrue();
        assertThat(ReportMask.plainNumber("-cmd")).isFalse();
    }

    @Test
    void csvWriterQuotesFormulaText() throws Exception {
        Path file = Files.createTempFile("report", ".csv");
        ReportWriters.write(ExportFormat.CSV, file, List.of("Name"), sink -> sink.accept(List.of("=cmd")));
        String body = Files.readString(file);
        assertThat(body).contains("\"'=cmd\"");
        Files.deleteIfExists(file);
    }

    @Test
    void customerStatementsNameOnlyTheAuthenticatedCustomer() {
        String sql = String.join("\n",
                CustomerReportSql.POSITION, CustomerReportSql.NEXT_REPAYMENT, CustomerReportSql.STATEMENT,
                CustomerReportSql.LOANS, CustomerReportSql.LOAN_COUNT, CustomerReportSql.REPAYMENTS,
                CustomerReportSql.REPAYMENT_COUNT, CustomerReportSql.APPLICATIONS, CustomerReportSql.APPLICATION_COUNT);
        assertThat(sql).contains(":customerId");
        String lowered = sql.toLowerCase();
        assertThat(lowered).doesNotContain("email", "phone", "bvn", "nin", "display_name", "account_number");
        Set<String> forbiddenHeaders = Set.of("email", "phone", "bvn", "nin");
        assertThat(forbiddenHeaders).noneMatch(lowered::contains);
    }
}
