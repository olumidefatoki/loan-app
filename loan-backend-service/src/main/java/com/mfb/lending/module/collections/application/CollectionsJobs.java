package com.mfb.lending.module.collections.application;

import java.time.Instant;
import java.time.LocalDate;

import com.mfb.lending.config.CollectionsProperties;
import com.mfb.lending.module.collections.persistence.CollectionsStore;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CollectionsJobs {

    private final DelinquencyService delinquency;
    private final CollectionCaseService cases;
    private final CollectionNoticeService notices;
    private final CollectionsReportService reports;
    private final CollectionsStore store;
    private final CollectionsProperties properties;
    private final java.time.Clock clock;

    public CollectionsJobs(
            DelinquencyService delinquency,
            CollectionCaseService cases,
            CollectionNoticeService notices,
            CollectionsReportService reports,
            CollectionsStore store,
            CollectionsProperties properties,
            java.time.Clock clock) {
        this.delinquency = delinquency;
        this.cases = cases;
        this.notices = notices;
        this.reports = reports;
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.collections.delinquency-cron:0 15 1 * * *}", zone = "${app.collections.zone:Africa/Lagos}")
    public void delinquency() {
        if (properties.isJobsEnabled()) {
            runDelinquency(today());
        }
    }

    @Scheduled(cron = "${app.collections.upcoming-cron:0 20 1 * * *}", zone = "${app.collections.zone:Africa/Lagos}")
    public void upcoming() {
        if (properties.isJobsEnabled()) {
            runUpcoming(today());
        }
    }

    @Scheduled(cron = "${app.collections.case-cron:0 25 1 * * *}", zone = "${app.collections.zone:Africa/Lagos}")
    public void cases() {
        if (properties.isJobsEnabled()) {
            runCases(today());
        }
    }

    @Scheduled(cron = "${app.collections.reminder-cron:0 30 1 * * *}", zone = "${app.collections.zone:Africa/Lagos}")
    public void reminders() {
        if (properties.isJobsEnabled()) {
            runReminders(today());
        }
    }

    @Scheduled(cron = "${app.collections.promise-cron:0 35 1 * * *}", zone = "${app.collections.zone:Africa/Lagos}")
    public void promises() {
        if (properties.isJobsEnabled()) {
            runPromises(today());
        }
    }

    @Scheduled(cron = "${app.collections.escalation-cron:0 40 1 * * *}", zone = "${app.collections.zone:Africa/Lagos}")
    public void escalation() {
        if (properties.isJobsEnabled()) {
            runEscalation(today());
        }
    }

    @Scheduled(cron = "${app.collections.reporting-cron:0 45 1 * * *}", zone = "${app.collections.zone:Africa/Lagos}")
    public void reporting() {
        if (properties.isJobsEnabled()) {
            runReporting(today());
        }
    }

    public int runDelinquency(LocalDate asOf) {
        int processed = delinquency.calculate(asOf);
        finish("DELINQUENCY", asOf, processed);
        return processed;
    }

    public int runUpcoming(LocalDate asOf) {
        if (store.snapshotsOn(asOf).isEmpty()) {
            delinquency.calculate(asOf);
        }
        int processed = notices.prepare(asOf);
        finish("UPCOMING_REPAYMENTS", asOf, processed);
        return processed;
    }

    public int runCases(LocalDate asOf) {
        int processed = cases.generateCases(asOf);
        finish("COLLECTION_CASES", asOf, processed);
        return processed;
    }

    public int runReminders(LocalDate asOf) {
        if (store.snapshotsOn(asOf).isEmpty()) {
            delinquency.calculate(asOf);
        }
        notices.prepare(asOf);
        int processed = notices.dispatch();
        finish("REMINDERS", asOf, processed);
        return processed;
    }

    public int runPromises(LocalDate asOf) {
        int processed = cases.monitorPromises(asOf);
        finish("PROMISE_MONITORING", asOf, processed);
        return processed;
    }

    public int runEscalation(LocalDate asOf) {
        int processed = cases.escalateOverdue(asOf);
        finish("ESCALATION", asOf, processed);
        return processed;
    }

    public int runReporting(LocalDate asOf) {
        reports.publish(asOf);
        finish("COLLECTIONS_REPORTING", asOf, 1);
        return 1;
    }

    private void finish(String jobName, LocalDate asOf, int processed) {
        store.insertJobRun(jobName, asOf, processed, Instant.now(clock));
    }

    private LocalDate today() {
        return delinquency.today();
    }
}
