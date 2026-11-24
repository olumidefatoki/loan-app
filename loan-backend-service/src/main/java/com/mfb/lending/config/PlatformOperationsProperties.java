package com.mfb.lending.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.platform")
public class PlatformOperationsProperties {

    private boolean jobsEnabled = true;
    private long notificationPollMs = 15_000;
    private int notificationBatch = 50;
    private int maxAttempts = 3;
    private Duration initialBackoff = Duration.ofMinutes(1);
    private Duration maxBackoff = Duration.ofHours(1);
    private int backoffMultiplier = 2;
    private Duration complaintSla = Duration.ofHours(48);
    private int evidenceRetentionYears = 7;
    private String complaintEscalationCron = "0 10 * * * *";
    private String smsProvider = "mock-sms";
    private String emailProvider = "mock-email";
    private String pushProvider = "mock-push";
    private String inAppProvider = "in-app";

    public boolean isJobsEnabled() {
        return jobsEnabled;
    }

    public void setJobsEnabled(boolean jobsEnabled) {
        this.jobsEnabled = jobsEnabled;
    }

    public long getNotificationPollMs() {
        return notificationPollMs;
    }

    public void setNotificationPollMs(long notificationPollMs) {
        this.notificationPollMs = notificationPollMs;
    }

    public int getNotificationBatch() {
        return notificationBatch;
    }

    public void setNotificationBatch(int notificationBatch) {
        this.notificationBatch = notificationBatch;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getInitialBackoff() {
        return initialBackoff;
    }

    public void setInitialBackoff(Duration initialBackoff) {
        this.initialBackoff = initialBackoff;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }

    public int getBackoffMultiplier() {
        return backoffMultiplier;
    }

    public void setBackoffMultiplier(int backoffMultiplier) {
        this.backoffMultiplier = backoffMultiplier;
    }

    public Duration getComplaintSla() {
        return complaintSla;
    }

    public void setComplaintSla(Duration complaintSla) {
        this.complaintSla = complaintSla;
    }

    public int getEvidenceRetentionYears() {
        return evidenceRetentionYears;
    }

    public void setEvidenceRetentionYears(int evidenceRetentionYears) {
        this.evidenceRetentionYears = evidenceRetentionYears;
    }

    public String getComplaintEscalationCron() {
        return complaintEscalationCron;
    }

    public void setComplaintEscalationCron(String complaintEscalationCron) {
        this.complaintEscalationCron = complaintEscalationCron;
    }

    public String getSmsProvider() {
        return smsProvider;
    }

    public void setSmsProvider(String smsProvider) {
        this.smsProvider = smsProvider;
    }

    public String getEmailProvider() {
        return emailProvider;
    }

    public void setEmailProvider(String emailProvider) {
        this.emailProvider = emailProvider;
    }

    public String getPushProvider() {
        return pushProvider;
    }

    public void setPushProvider(String pushProvider) {
        this.pushProvider = pushProvider;
    }

    public String getInAppProvider() {
        return inAppProvider;
    }

    public void setInAppProvider(String inAppProvider) {
        this.inAppProvider = inAppProvider;
    }

    public String providerCode(String channel) {
        return switch (channel) {
            case "SMS" -> smsProvider;
            case "EMAIL" -> emailProvider;
            case "PUSH" -> pushProvider;
            case "IN_APP" -> inAppProvider;
            default -> channel.toLowerCase();
        };
    }
}
