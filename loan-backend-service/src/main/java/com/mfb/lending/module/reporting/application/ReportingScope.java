package com.mfb.lending.module.reporting.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.reporting.api.ReportQuery;
import com.mfb.lending.module.servicing.domain.LoanAccountStatus;
import com.mfb.lending.module.servicing.domain.RepaymentStatus;

public record ReportingScope(
        boolean bounded,
        LocalDate startDate,
        LocalDate endDate,
        Instant periodStart,
        Instant periodEndExclusive,
        LocalDate dueFrom,
        LocalDate dueToExclusive,
        LocalDate stockDate,
        Instant stockExclusive,
        boolean historical,
        UUID branchId,
        boolean unrestricted,
        List<UUID> branchIds,
        UUID productId,
        String segment,
        String loanStatus,
        String riskCategory,
        UUID officerId,
        String securityClass,
        String repaymentStatus,
        ZoneId zone
) {

    public static final UUID UNASSIGNED_BRANCH = new UUID(0L, 0L);
    public static final UUID BANK_WIDE = new UUID(-1L, -1L);

    public static ReportingScope parse(ReportQuery query, AuthenticatedUser actor, ZoneId zone, Clock clock, int maxRangeDays) {
        if (actor == null || actor.getPrincipalType() != PrincipalType.STAFF) {
            throw new ForbiddenException("You do not have permission to view this report");
        }
        ReportQuery safe = query == null ? new ReportQuery() : query;
        LocalDate today = LocalDate.now(clock.withZone(zone));
        DateBounds dates = dates(safe.getStartDate(), safe.getEndDate(), today, zone, clock, maxRangeDays);
        boolean unrestricted = actor.unrestrictedBranches();
        List<UUID> branches = unrestricted ? List.of() : List.copyOf(actor.branchIds());
        if (!unrestricted && branches.isEmpty()) {
            throw new ForbiddenException("You are not assigned to a branch");
        }
        if (!unrestricted && safe.getBranchId() != null && !branches.contains(safe.getBranchId())) {
            throw new ForbiddenException("You cannot access a report outside your branches");
        }
        return new ReportingScope(
                dates.bounded(),
                dates.start(),
                dates.end(),
                dates.periodStart(),
                dates.periodEndExclusive(),
                dates.dueFrom(),
                dates.dueToExclusive(),
                dates.stockDate(),
                dates.stockExclusive(),
                dates.historical(),
                safe.getBranchId(),
                unrestricted,
                branches,
                safe.getProductId(),
                segment(safe.getCustomerSegment()),
                loanStatus(safe.getLoanStatus()),
                risk(safe.getRiskCategory()),
                safe.getLoanOfficerId(),
                security(safe.getSecurity()),
                repaymentStatus(safe.getRepaymentStatus()),
                zone);
    }

    public static ReportingScope businessDay(ZoneId zone, Clock clock) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant start = today.atStartOfDay(zone).toInstant();
        Instant end = today.plusDays(1).atStartOfDay(zone).toInstant();
        return new ReportingScope(true, today, today, start, end, today, today.plusDays(1), today, end, false,
                null, true, List.of(), null, null, null, null, null, null, null, zone);
    }

    public static DateWindow customerWindow(LocalDate start, LocalDate end, ZoneId zone, Clock clock, int maxRangeDays) {
        LocalDate today = LocalDate.now(clock.withZone(zone));
        DateBounds dates = dates(start, end, today, zone, clock, maxRangeDays);
        return new DateWindow(dates.dueFrom(), dates.dueToExclusive(), dates.bounded(), dates.start(), dates.end());
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("bounded", bounded);
        values.put("startDate", text(startDate));
        values.put("endDate", text(endDate));
        values.put("periodStart", periodStart.toString());
        values.put("periodEndExclusive", periodEndExclusive.toString());
        values.put("dueFrom", dueFrom.toString());
        values.put("dueToExclusive", dueToExclusive.toString());
        values.put("stockDate", stockDate.toString());
        values.put("stockExclusive", stockExclusive.toString());
        values.put("historical", historical);
        values.put("branchId", text(branchId));
        values.put("unrestricted", unrestricted);
        values.put("branchIds", branchIds.stream().map(UUID::toString).toList());
        values.put("productId", text(productId));
        values.put("segment", segment);
        values.put("loanStatus", loanStatus);
        values.put("riskCategory", riskCategory);
        values.put("officerId", text(officerId));
        values.put("securityClass", securityClass);
        values.put("repaymentStatus", repaymentStatus);
        values.put("zone", zone.getId());
        return values;
    }

    @SuppressWarnings("unchecked")
    public static ReportingScope restore(Map<String, Object> values) {
        List<String> rawBranches = values.get("branchIds") instanceof List<?> list
                ? list.stream().map(String::valueOf).toList()
                : List.of();
        List<UUID> branches = new ArrayList<>();
        for (String branch : rawBranches) {
            branches.add(UUID.fromString(branch));
        }
        return new ReportingScope(
                Boolean.TRUE.equals(values.get("bounded")),
                date(values.get("startDate")),
                date(values.get("endDate")),
                Instant.parse(String.valueOf(values.get("periodStart"))),
                Instant.parse(String.valueOf(values.get("periodEndExclusive"))),
                LocalDate.parse(String.valueOf(values.get("dueFrom"))),
                LocalDate.parse(String.valueOf(values.get("dueToExclusive"))),
                LocalDate.parse(String.valueOf(values.get("stockDate"))),
                Instant.parse(String.valueOf(values.get("stockExclusive"))),
                Boolean.TRUE.equals(values.get("historical")),
                uuid(values.get("branchId")),
                Boolean.TRUE.equals(values.get("unrestricted")),
                List.copyOf(branches),
                uuid(values.get("productId")),
                textOrNull(values.get("segment")),
                textOrNull(values.get("loanStatus")),
                textOrNull(values.get("riskCategory")),
                uuid(values.get("officerId")),
                textOrNull(values.get("securityClass")),
                textOrNull(values.get("repaymentStatus")),
                ZoneId.of(String.valueOf(values.get("zone"))));
    }

    private static DateBounds dates(LocalDate start, LocalDate end, LocalDate today, ZoneId zone, Clock clock, int maxRangeDays) {
        if ((start == null) != (end == null)) {
            throw ValidationException.field("endDate", "Start date and end date must both be supplied");
        }
        if (start == null) {
            Instant now = clock.instant();
            return new DateBounds(false, null, null, Instant.EPOCH, now, LocalDate.of(1970, 1, 1), today.plusDays(1),
                    today, now, false);
        }
        if (end.isBefore(start)) {
            throw ValidationException.field("endDate", "End date must be on or after the start date");
        }
        if (end.isAfter(today)) {
            throw ValidationException.field("endDate", "End date cannot be after today");
        }
        long days = ChronoUnit.DAYS.between(start, end);
        if (days > maxRangeDays) {
            throw ValidationException.field("endDate", "The reporting period is too long");
        }
        Instant periodStart = start.atStartOfDay(zone).toInstant();
        Instant periodEnd = end.plusDays(1).atStartOfDay(zone).toInstant();
        boolean historical = end.isBefore(today);
        return new DateBounds(true, start, end, periodStart, periodEnd, start, end.plusDays(1), end, periodEnd, historical);
    }

    private static String segment(String value) {
        String token = token(value, "customerSegment");
        if (token == null) {
            return null;
        }
        if (!token.equals("INDIVIDUAL") && !token.equals("SME")) {
            throw ValidationException.field("customerSegment", "Customer segment must be INDIVIDUAL or SME");
        }
        return token;
    }

    private static String loanStatus(String value) {
        String token = token(value, "loanStatus");
        if (token == null) {
            return null;
        }
        try {
            LoanAccountStatus.valueOf(token);
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("loanStatus", "Loan status is not recognised");
        }
        return token;
    }

    private static String repaymentStatus(String value) {
        String token = token(value, "repaymentStatus");
        if (token == null) {
            return null;
        }
        try {
            RepaymentStatus.valueOf(token);
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("repaymentStatus", "Repayment status is not recognised");
        }
        return token;
    }

    private static String security(String value) {
        String token = token(value, "security");
        if (token == null) {
            return null;
        }
        if (!token.equals("SECURED") && !token.equals("UNSECURED")) {
            throw ValidationException.field("security", "Security must be SECURED or UNSECURED");
        }
        return token;
    }

    private static String risk(String value) {
        String token = token(value, "riskCategory");
        if (token == null) {
            return null;
        }
        if (!token.matches("[A-Z0-9_]{1,32}")) {
            throw ValidationException.field("riskCategory", "Risk category is not recognised");
        }
        return token;
    }

    private static String token(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private static String text(LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static String text(UUID value) {
        return value == null ? null : value.toString();
    }

    private static LocalDate date(Object value) {
        if (value == null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value))) {
            return null;
        }
        return LocalDate.parse(String.valueOf(value));
    }

    private static UUID uuid(Object value) {
        if (value == null || String.valueOf(value).isBlank() || "null".equals(String.valueOf(value))) {
            return null;
        }
        return UUID.fromString(String.valueOf(value));
    }

    private static String textOrNull(Object value) {
        if (value == null || "null".equals(String.valueOf(value))) {
            return null;
        }
        String text = String.valueOf(value);
        return text.isBlank() ? null : text;
    }

    public record DateBounds(
            boolean bounded,
            LocalDate start,
            LocalDate end,
            Instant periodStart,
            Instant periodEndExclusive,
            LocalDate dueFrom,
            LocalDate dueToExclusive,
            LocalDate stockDate,
            Instant stockExclusive,
            boolean historical
    ) {
    }

    public record DateWindow(LocalDate dueFrom, LocalDate dueToExclusive, boolean bounded, LocalDate start, LocalDate end) {
    }
}
