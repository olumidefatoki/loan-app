package com.mfb.lending.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.reporting")
public class ReportingProperties {

    private String zone = "Africa/Lagos";
    private boolean jobsEnabled = true;
    private int asyncThreshold = 500;
    private int maxRows = 50_000;
    private int pdfMaxRows = 2_000;
    private int maxActiveExports = 3;
    private int maxRangeDays = 3_660;
    private Duration exportTtl = Duration.ofHours(24);
    private String directory = System.getProperty("java.io.tmpdir") + "/mfb-report-exports";
    private String snapshotCron = "0 50 1 * * *";
    private long exportPollMs = 15_000;

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public boolean isJobsEnabled() {
        return jobsEnabled;
    }

    public void setJobsEnabled(boolean jobsEnabled) {
        this.jobsEnabled = jobsEnabled;
    }

    public int getAsyncThreshold() {
        return asyncThreshold;
    }

    public void setAsyncThreshold(int asyncThreshold) {
        this.asyncThreshold = asyncThreshold;
    }

    public int getMaxRows() {
        return maxRows;
    }

    public void setMaxRows(int maxRows) {
        this.maxRows = maxRows;
    }

    public int getPdfMaxRows() {
        return pdfMaxRows;
    }

    public void setPdfMaxRows(int pdfMaxRows) {
        this.pdfMaxRows = pdfMaxRows;
    }

    public int getMaxActiveExports() {
        return maxActiveExports;
    }

    public void setMaxActiveExports(int maxActiveExports) {
        this.maxActiveExports = maxActiveExports;
    }

    public int getMaxRangeDays() {
        return maxRangeDays;
    }

    public void setMaxRangeDays(int maxRangeDays) {
        this.maxRangeDays = maxRangeDays;
    }

    public Duration getExportTtl() {
        return exportTtl;
    }

    public void setExportTtl(Duration exportTtl) {
        this.exportTtl = exportTtl;
    }

    public String getDirectory() {
        return directory;
    }

    public void setDirectory(String directory) {
        this.directory = directory;
    }

    public String getSnapshotCron() {
        return snapshotCron;
    }

    public void setSnapshotCron(String snapshotCron) {
        this.snapshotCron = snapshotCron;
    }

    public long getExportPollMs() {
        return exportPollMs;
    }

    public void setExportPollMs(long exportPollMs) {
        this.exportPollMs = exportPollMs;
    }
}
