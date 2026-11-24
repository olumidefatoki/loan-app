package com.mfb.lending.module.audit.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.SecurityProfile;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AuditAccessTest {

    @Test
    void auditLogsRequireTheAuditPermission() {
        AuditQueryService audits = new AuditQueryService(mock(NamedParameterJdbcTemplate.class));
        AuthenticatedUser staff = user(List.of());
        assertThatThrownBy(() -> audits.list(staff, null, null, null, null, 0, 20)).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> audits.get(staff, UUID.randomUUID())).isInstanceOf(ForbiddenException.class);
    }

    private static AuthenticatedUser user(List<String> permissions) {
        UserRecord record = new UserRecord(UUID.randomUUID(), PrincipalType.STAFF, null, "Ada", null, "ada@mfb.test", null, "hash",
                UserStatus.ACTIVE, true, true, 0, null, Instant.EPOCH, null, false, null, null, Instant.EPOCH, Instant.EPOCH, "test", "test");
        return AuthenticatedUser.from(record, UUID.randomUUID(), new SecurityProfile(List.of(), permissions, List.of(), false, true));
    }

    @Test
    void thePermissionConstantIsTheGate() {
        assertThatThrownBy(() -> new AuditQueryService(mock(NamedParameterJdbcTemplate.class)).list(
                user(List.of(PermissionCodes.COMPLIANCE_VIEW)), null, null, null, null, 0, 20)).isInstanceOf(ForbiddenException.class);
    }
}
