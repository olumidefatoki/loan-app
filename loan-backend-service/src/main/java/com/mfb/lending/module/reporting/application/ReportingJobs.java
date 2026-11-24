package com.mfb.lending.module.reporting.application;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.mfb.lending.config.ReportingProperties;
import com.mfb.lending.module.reporting.calculation.ReportingCalculator;
import com.mfb.lending.module.reporting.domain.GroupDimension;
import com.mfb.lending.module.reporting.domain.LedgerMovement;
import com.mfb.lending.module.reporting.domain.ReportingModels.BalanceMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.FlowMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.GroupMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.SnapshotRow;
import com.mfb.lending.module.reporting.domain.ReportingModels.StockMetrics;
import com.mfb.lending.module.reporting.persistence.ReportingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ReportingJobs {

    private static final Logger log = LoggerFactory.getLogger(ReportingJobs.class);

    private final ReportingStore store;
    private final ReportExportService exports;
    private final ReportingProperties properties;
    private final java.time.Clock clock;

    public ReportingJobs(ReportingStore store, ReportExportService exports, ReportingProperties properties, java.time.Clock clock) {
        this.store = store;
        this.exports = exports;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${app.reporting.export-poll-ms:15000}")
    public void exports() {
        if (properties.isJobsEnabled()) {
            exports.processPending();
        }
    }

    @Scheduled(cron = "${app.reporting.snapshot-cron:0 50 1 * * *}", zone = "${app.reporting.zone:Africa/Lagos}")
    public void snapshot() {
        if (properties.isJobsEnabled()) {
            runSnapshot();
        }
    }

    public void runSnapshot() {
        Instant started = clock.instant();
        try {
            store.refreshPortfolioView();
        } catch (RuntimeException ex) {
            log.warn("Portfolio position view could not be refreshed", ex);
        }
        ReportingScope day = ReportingScope.businessDay(ZoneId.of(properties.getZone()), clock);
        StockMetrics stock = store.stock(day);
        FlowMetrics flow = store.flow(day);
        BalanceMetrics balances = store.balances(day);
        store.captureSnapshot(day.stockDate(), ReportingScope.BANK_WIDE, bank(stock, flow, balances), clock.instant());
        Map<String, GroupMetrics> disbursed = index(store.movementGroups(day, GroupDimension.BRANCH, LedgerMovement.GL_DISBURSEMENT));
        Map<String, GroupMetrics> collected = index(store.movementGroups(day, GroupDimension.BRANCH, LedgerMovement.GL_COLLECTION));
        List<GroupMetrics> branches = store.portfolioGroups(day, GroupDimension.BRANCH);
        for (GroupMetrics branch : branches) {
            UUID key = branch.groupId() == null ? ReportingScope.UNASSIGNED_BRANCH : branch.groupId();
            String code = branch.code() == null ? "UNASSIGNED" : branch.code();
            BigDecimal disbursement = disbursed.get(code) == null ? BigDecimal.ZERO : disbursed.get(code).disbursementPrincipal();
            BigDecimal collection = collected.get(code) == null ? BigDecimal.ZERO : collected.get(code).collectionCash();
            store.captureSnapshot(day.stockDate(), key, branchRow(branch, disbursement, collection), clock.instant());
        }
        store.recordJob(UUID.randomUUID(), "PORTFOLIO_SNAPSHOT", day.stockDate(), branches.size() + 1, started);
    }

    private static SnapshotRow bank(StockMetrics stock, FlowMetrics flow, BalanceMetrics balances) {
        return new SnapshotRow(count(stock.loanCount()), ledger(stock.outstandingPrincipal()), ledger(stock.outstandingInterest()),
                ledger(stock.overduePrincipal()), ratio(stock.par30Principal(), stock.outstandingPrincipal()),
                ratio(stock.par60Principal(), stock.outstandingPrincipal()), ratio(stock.par90Principal(), stock.outstandingPrincipal()),
                ratio(stock.par90Principal(), stock.outstandingPrincipal()), ledger(flow.generalLedgerDisbursement()),
                ledger(flow.generalLedgerCollection()), ledger(balances.generalLedgerPrincipal()),
                ReportingCalculator.variance(balances.subledgerPrincipal(), balances.generalLedgerPrincipal()));
    }

    private static SnapshotRow branchRow(GroupMetrics branch, BigDecimal disbursement, BigDecimal collection) {
        return new SnapshotRow(count(branch.loanCount()), ledger(branch.outstandingPrincipal()), BigDecimal.ZERO.setScale(6),
                ledger(branch.overduePrincipal()), ratio(branch.par30Principal(), branch.outstandingPrincipal()),
                ratio(branch.par60Principal(), branch.outstandingPrincipal()), ratio(branch.par90Principal(), branch.outstandingPrincipal()),
                ratio(branch.par90Principal(), branch.outstandingPrincipal()), ledger(disbursement), ledger(collection),
                ledger(branch.outstandingPrincipal()), BigDecimal.ZERO.setScale(6));
    }

    private static Map<String, GroupMetrics> index(List<GroupMetrics> rows) {
        return rows.stream().collect(Collectors.toMap(row -> row.code() == null ? "UNASSIGNED" : row.code(), Function.identity(), (left, right) -> left));
    }

    private static int count(long value) {
        return Math.toIntExact(value);
    }

    private static BigDecimal ledger(BigDecimal value) {
        return ReportingCalculator.ledger(value);
    }

    private static BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        return ReportingCalculator.ratio(numerator, denominator);
    }
}
