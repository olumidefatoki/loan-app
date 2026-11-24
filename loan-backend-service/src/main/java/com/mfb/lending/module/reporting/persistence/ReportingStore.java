package com.mfb.lending.module.reporting.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

import com.mfb.lending.module.reporting.application.ReportingScope;
import com.mfb.lending.module.reporting.domain.GroupDimension;
import com.mfb.lending.module.reporting.domain.LedgerMovement;
import com.mfb.lending.module.reporting.domain.ReportCode;
import com.mfb.lending.module.reporting.domain.ReportingModels.ApplicationCounts;
import com.mfb.lending.module.reporting.domain.ReportingModels.BalanceMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerApplication;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerLoanPage;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerPosition;
import com.mfb.lending.module.reporting.domain.ReportingModels.CustomerRepaymentPage;
import com.mfb.lending.module.reporting.domain.ReportingModels.DecisionMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.DetailPage;
import com.mfb.lending.module.reporting.domain.ReportingModels.ExportJob;
import com.mfb.lending.module.reporting.domain.ReportingModels.FlowMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.GroupMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.SnapshotRow;
import com.mfb.lending.module.reporting.domain.ReportingModels.StockMetrics;
import com.mfb.lending.module.reporting.domain.ReportingModels.WriteOffMetrics;

public interface ReportingStore {

    CustomerPosition customerPosition(UUID customerId);

    CustomerLoanPage customerLoans(UUID customerId, int limit, int offset, int page, int size);

    CustomerRepaymentPage customerRepayments(UUID customerId, LocalDate dueFrom, LocalDate dueTo, int limit, int offset, int page, int size);

    List<CustomerApplication> customerApplications(UUID customerId, int limit);

    long customerApplicationCount(UUID customerId);

    ApplicationCounts applications(ReportingScope scope);

    DecisionMetrics decisions(ReportingScope scope);

    StockMetrics stock(ReportingScope scope);

    FlowMetrics flow(ReportingScope scope);

    BalanceMetrics balances(ReportingScope scope);

    WriteOffMetrics writeOff(ReportingScope scope);

    long openCases(ReportingScope scope);

    List<GroupMetrics> portfolioGroups(ReportingScope scope, GroupDimension dimension);

    List<GroupMetrics> movementGroups(ReportingScope scope, GroupDimension dimension, LedgerMovement movement);

    List<GroupMetrics> applicationGroups(ReportingScope scope, GroupDimension dimension);

    DetailPage details(ReportCode code, ReportingScope scope, int limit, int offset, int page, int size);

    long countDetails(ReportCode code, ReportingScope scope);

    List<String> detailHeaders(ReportCode code, ReportingScope scope);

    long streamDetails(ReportCode code, ReportingScope scope, int maxRows, Consumer<List<String>> rows);

    void insertExport(ExportJob job);

    Optional<ExportJob> lockNextPending(Instant now);

    void markReady(UUID id, int rowCount, String path, String fileName, String contentType, Instant completedAt);

    void markFailed(UUID id, String message, Instant completedAt);

    void markExpired(UUID id);

    List<String> expireReady(Instant now);

    Optional<ExportJob> findExport(UUID id);

    int activeExports(UUID userId);

    void touchDownload(UUID id, Instant at);

    void captureSnapshot(LocalDate date, UUID branchKey, SnapshotRow row, Instant capturedAt);

    Optional<LocalDate> latestSnapshot(UUID branchKey);

    void refreshPortfolioView();

    void recordJob(UUID id, String name, LocalDate date, int processed, Instant started);
}
