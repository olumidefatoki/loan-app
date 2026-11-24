package com.mfb.lending.module.reporting.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.SecurityProfile;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.reporting.api.ReportQuery;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReportingScopeTest {

    private static final ZoneId LAGOS = ZoneId.of("Africa/Lagos");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void datesMustBeAClosedRangeThatDoesNotPassToday() {
        AuthenticatedUser staff = staff(true, List.of());
        ReportQuery oneSide = new ReportQuery();
        oneSide.setStartDate(LocalDate.of(2026, 11, 1));
        assertThatThrownBy(() -> ReportingScope.parse(oneSide, staff, LAGOS, CLOCK, 3660))
                .isInstanceOf(ValidationException.class);

        ReportQuery future = query(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 25));
        assertThatThrownBy(() -> ReportingScope.parse(future, staff, LAGOS, CLOCK, 3660))
                .isInstanceOf(ValidationException.class);

        ReportQuery huge = query(LocalDate.of(2010, 1, 1), LocalDate.of(2026, 11, 24));
        assertThatThrownBy(() -> ReportingScope.parse(huge, staff, LAGOS, CLOCK, 3660))
                .isInstanceOf(ValidationException.class);

        ReportingScope historical = ReportingScope.parse(query(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 23)), staff, LAGOS, CLOCK, 3660);
        assertThat(historical.historical()).isTrue();
        assertThat(historical.stockDate()).isEqualTo(LocalDate.of(2026, 11, 23));

        ReportingScope today = ReportingScope.parse(query(LocalDate.of(2026, 11, 24), LocalDate.of(2026, 11, 24)), staff, LAGOS, CLOCK, 3660);
        assertThat(today.historical()).isFalse();
    }

    @Test
    void aRestrictedOfficerCannotLeaveTheAssignedBranches() {
        UUID branch = UUID.randomUUID();
        AuthenticatedUser officer = staff(false, List.of(branch));
        ReportQuery other = new ReportQuery();
        other.setBranchId(UUID.randomUUID());
        assertThatThrownBy(() -> ReportingScope.parse(other, officer, LAGOS, CLOCK, 3660))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("branches");

        AuthenticatedUser unassigned = staff(false, List.of());
        assertThatThrownBy(() -> ReportingScope.parse(new ReportQuery(), unassigned, LAGOS, CLOCK, 3660))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("not assigned");

        ReportingScope own = ReportingScope.parse(query(null, null), officer, LAGOS, CLOCK, 3660);
        assertThat(own.branchIds()).containsExactly(branch);
        ReportingScope restored = ReportingScope.restore(own.snapshot());
        assertThat(restored.branchIds()).containsExactly(branch);
        assertThat(restored.unrestricted()).isFalse();
        assertThat(restored.zone()).isEqualTo(LAGOS);
    }

    @Test
    void customersCannotOpenAStaffReportScope() {
        assertThatThrownBy(() -> ReportingScope.parse(new ReportQuery(), staff(PrincipalType.CUSTOMER, true, List.of()), LAGOS, CLOCK, 3660))
                .isInstanceOf(ForbiddenException.class);
    }

    private static ReportQuery query(LocalDate start, LocalDate end) {
        ReportQuery query = new ReportQuery();
        query.setStartDate(start);
        query.setEndDate(end);
        return query;
    }

    private static AuthenticatedUser staff(boolean unrestricted, List<UUID> branches) {
        return staff(PrincipalType.STAFF, unrestricted, branches);
    }

    private static AuthenticatedUser staff(PrincipalType type, boolean unrestricted, List<UUID> branches) {
        UUID id = UUID.randomUUID();
        UserRecord user = new UserRecord(id, type, null, "Ada", null, "ada@mfb.test", null, "hash", UserStatus.ACTIVE,
                true, true, 0, null, Instant.EPOCH, null, false, null, null, Instant.EPOCH, Instant.EPOCH, "test", "test");
        return AuthenticatedUser.from(user, UUID.randomUUID(),
                new SecurityProfile(List.of("STAFF"), List.of("REPORT_VIEW"), branches, false, unrestricted));
    }
}
