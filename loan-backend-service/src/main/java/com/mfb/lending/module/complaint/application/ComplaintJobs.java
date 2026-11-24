package com.mfb.lending.module.complaint.application;

import java.time.Instant;

import com.mfb.lending.config.PlatformOperationsProperties;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ComplaintJobs {

    private final ComplaintService complaints;
    private final PlatformOperationsProperties properties;
    private final java.time.Clock clock;

    public ComplaintJobs(ComplaintService complaints, PlatformOperationsProperties properties, java.time.Clock clock) {
        this.complaints = complaints;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(cron = "${app.platform.complaint-escalation-cron:0 10 * * * *}", zone = "Africa/Lagos")
    public void escalate() {
        if (properties.isJobsEnabled()) {
            complaints.escalateBreaches(Instant.now(clock));
        }
    }
}
