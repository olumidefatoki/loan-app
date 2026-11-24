package com.mfb.lending.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.collections")
public class CollectionsProperties {

    private String zone = "Africa/Lagos";
    private int upcomingDays = 7;
    private int escalationDays = 61;
    private int delinquentDays = 1;
    private boolean jobsEnabled = true;

    public String getZone() {
        return zone;
    }

    public void setZone(String zone) {
        this.zone = zone;
    }

    public int getUpcomingDays() {
        return upcomingDays;
    }

    public void setUpcomingDays(int upcomingDays) {
        this.upcomingDays = upcomingDays;
    }

    public int getEscalationDays() {
        return escalationDays;
    }

    public void setEscalationDays(int escalationDays) {
        this.escalationDays = escalationDays;
    }

    public int getDelinquentDays() {
        return delinquentDays;
    }

    public void setDelinquentDays(int delinquentDays) {
        this.delinquentDays = delinquentDays;
    }

    public boolean isJobsEnabled() {
        return jobsEnabled;
    }

    public void setJobsEnabled(boolean jobsEnabled) {
        this.jobsEnabled = jobsEnabled;
    }
}
