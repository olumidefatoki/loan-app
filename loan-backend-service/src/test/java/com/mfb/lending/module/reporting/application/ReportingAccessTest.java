package com.mfb.lending.module.reporting.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.SecurityProfile;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.domain.CustomerCategory;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.CustomerStatus;
import com.mfb.lending.module.customer.domain.KycStatus;
import com.mfb.lending.module.customer.domain.RiskClassification;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReportingAccessTest {

    private final CustomerRepository customers = mock(CustomerRepository.class);
    private final ReportingAccess access = new ReportingAccess(customers);

    @Test
    void aCustomerSeesOnlyTheMatchingCustomerRecord() {
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        AuthenticatedUser customer = user(userId, PrincipalType.CUSTOMER, List.of());
        when(customers.findByUserId(userId)).thenReturn(Optional.of(customer(customerId, userId)));
        assertThat(access.ownCustomer(customer)).isEqualTo(customerId);

        when(customers.findByUserId(userId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> access.ownCustomer(customer)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> access.ownCustomer(user(UUID.randomUUID(), PrincipalType.STAFF, List.of(PermissionCodes.REPORT_VIEW))))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void viewingAReportDoesNotGrantAnExport() {
        AuthenticatedUser auditor = user(UUID.randomUUID(), PrincipalType.STAFF, List.of(PermissionCodes.REPORT_VIEW));
        access.viewFinancial(auditor);
        assertThatThrownBy(() -> access.export(auditor)).isInstanceOf(ForbiddenException.class);

        AuthenticatedUser officer = user(UUID.randomUUID(), PrincipalType.STAFF, List.of(PermissionCodes.LOAN_APPLICATION_VIEW));
        access.viewOperations(officer);
        assertThatThrownBy(() -> access.viewFinancial(officer)).isInstanceOf(ForbiddenException.class);

        AuthenticatedUser exporter = user(UUID.randomUUID(), PrincipalType.STAFF, List.of(PermissionCodes.REPORT_EXPORT));
        access.export(exporter);
        assertThatThrownBy(() -> access.viewFinancial(exporter)).isInstanceOf(ForbiddenException.class);
    }

    private static CustomerRecord customer(UUID id, UUID userId) {
        return new CustomerRecord(id, "CU000000000001", CustomerKind.INDIVIDUAL, CustomerStatus.ACTIVE, CustomerCategory.RETAIL,
                "Ada", null, null, userId, null, KycStatus.VERIFIED, RiskClassification.LOW, false, null, false, null,
                Instant.EPOCH, Instant.EPOCH, "test", "test");
    }

    private static AuthenticatedUser user(UUID id, PrincipalType type, List<String> permissions) {
        UserRecord record = new UserRecord(id, type, null, "Ada", null, "ada@mfb.test", null, "hash", UserStatus.ACTIVE,
                true, true, 0, null, Instant.EPOCH, null, false, null, null, Instant.EPOCH, Instant.EPOCH, "test", "test");
        return AuthenticatedUser.from(record, UUID.randomUUID(), new SecurityProfile(List.of(), permissions, List.of(), false, true));
    }
}
