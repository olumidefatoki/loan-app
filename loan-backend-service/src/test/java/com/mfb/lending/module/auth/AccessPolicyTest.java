package com.mfb.lending.module.auth;

import java.util.Set;
import java.util.UUID;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.auth.application.AccessPolicy;
import com.mfb.lending.module.auth.application.PasswordPolicy;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.RoleRecord;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccessPolicyTest {

    private final UUID actorId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    private final UUID branchA = UUID.randomUUID();
    private final UUID branchB = UUID.randomUUID();

    @Test
    void passwordPolicyRejectsWeakAndAccountDerivedPasswords() {
        assertThatThrownBy(() -> PasswordPolicy.check("short", "ada@example.com", "+2348012345678"))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> PasswordPolicy.check("Ada.user-1234!", "ada.user@example.com", "+2348012345678"))
                .isInstanceOf(ValidationException.class);
        PasswordPolicy.check("Str0ng-Passw0rd!", "ada@example.com", "+2348012345678");
    }

    @Test
    void customersCannotReadAnotherCustomerAndBranchesAreEnforced() {
        AccessPolicy.Actor customer = new AccessPolicy.Actor(actorId, Set.of(), Set.of(), false);
        assertThatThrownBy(() -> AccessPolicy.assertCanView(customer, new AccessPolicy.Subject(otherId, Set.of())))
                .isInstanceOf(ForbiddenException.class);

        AccessPolicy.Actor branchOfficer = new AccessPolicy.Actor(
                actorId,
                Set.of(PermissionCodes.CUSTOMER_VIEW, PermissionCodes.USER_MANAGE),
                Set.of(branchA),
                false);
        AccessPolicy.assertCanView(branchOfficer, new AccessPolicy.Subject(otherId, Set.of(branchA)));
        assertThatThrownBy(() -> AccessPolicy.assertCanView(branchOfficer, new AccessPolicy.Subject(otherId, Set.of(branchB))))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> AccessPolicy.assertBranchAllowed(branchOfficer, branchB))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void roleAssignmentCannotEscalatePrivilegesOrChangeTheCaller() {
        AccessPolicy.Actor manager = new AccessPolicy.Actor(
                actorId,
                Set.of(PermissionCodes.USER_MANAGE, PermissionCodes.CUSTOMER_VIEW),
                Set.of(branchA),
                false);
        AccessPolicy.Subject self = new AccessPolicy.Subject(actorId, Set.of(branchA));
        AccessPolicy.Subject colleague = new AccessPolicy.Subject(otherId, Set.of(branchA));
        RoleRecord administrator = new RoleRecord(
                UUID.randomUUID(), "SYSTEM_ADMIN", "System administrator", null, true, true, true);

        assertThatThrownBy(() -> AccessPolicy.assertCanAssign(manager, self, Set.of(administrator), Set.of()))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("own account");
        assertThatThrownBy(() -> AccessPolicy.assertCanAssign(
                manager,
                colleague,
                Set.of(administrator),
                Set.of(PermissionCodes.SYSTEM_CONFIGURE)))
                .isInstanceOf(ForbiddenException.class);

        AccessPolicy.Actor administratorActor = new AccessPolicy.Actor(
                actorId,
                Set.of(PermissionCodes.SYSTEM_CONFIGURE, PermissionCodes.USER_MANAGE),
                Set.of(),
                true);
        AccessPolicy.assertCanAssign(
                administratorActor,
                colleague,
                Set.of(administrator),
                Set.of(PermissionCodes.SYSTEM_CONFIGURE));
    }
}
