package com.mfb.lending.module.administration.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public final class AdministrationModels {

    private AdministrationModels() {
    }

    public record Branch(
            UUID id, String code, String name, String addressLine, String city, String state, String countryCode,
            String phoneNumber, String email, String status) {
    }

    public record SystemConfiguration(
            UUID id, String key, String value, String description, String valueType, boolean sensitive, boolean masked) {
    }

    public record ChangeRequest(
            UUID id, String targetType, String targetKey, String currentValue, String proposedValue, String valueType,
            String description, boolean sensitive, String operation, String status, UUID requestedBy, String requestedByName,
            UUID decidedBy, String decidedByName, String decisionReason, Instant requestedAt, Instant decidedAt) {
    }

    public record BusinessCalendar(
            UUID id, String code, String name, String timezone, String weekendDays, boolean active) {
    }

    public record PublicHoliday(UUID id, UUID calendarId, LocalDate holidayDate, String name) {
    }

    public record FeatureFlag(UUID id, String key, boolean enabled, String description, boolean sensitive) {
    }

    public record OperationalHealth(
            boolean database, long pendingNotifications, long deadNotifications, long openComplianceCases,
            long breachedComplaints, long pendingApprovals) {
    }
}
