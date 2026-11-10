package com.mfb.lending.module.customer.application;

import java.util.UUID;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.domain.CustomerRecord;

public final class CustomerAccess {

    private CustomerAccess() {
    }

    public static void assertCanSearch(AuthenticatedUser actor) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER || !actor.permissions().contains(PermissionCodes.CUSTOMER_VIEW)) {
            throw new ForbiddenException("You do not have permission to search customers");
        }
    }

    public static void assertCanView(AuthenticatedUser actor, CustomerRecord customer) {
        if (isOwner(actor, customer)) {
            return;
        }
        assertStaff(actor, customer, false);
    }

    public static void assertCanMutate(AuthenticatedUser actor, CustomerRecord customer) {
        if (isOwner(actor, customer)) {
            return;
        }
        assertStaff(actor, customer, true);
    }

    public static void assertBranch(AuthenticatedUser actor, UUID branchId) {
        if (actor.unrestrictedBranches()) {
            return;
        }
        if (branchId == null || !actor.branchIds().contains(branchId)) {
            throw new ForbiddenException("You cannot access this branch");
        }
    }

    public static boolean isOwner(AuthenticatedUser actor, CustomerRecord customer) {
        return actor.getPrincipalType() == PrincipalType.CUSTOMER
                && customer.userId() != null
                && customer.userId().equals(actor.getId());
    }

    private static void assertStaff(AuthenticatedUser actor, CustomerRecord customer, boolean mutate) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            throw new ResourceNotFoundException("Customer");
        }
        boolean allowed = mutate
                ? actor.permissions().contains(PermissionCodes.CUSTOMER_CREATE)
                : actor.permissions().contains(PermissionCodes.CUSTOMER_VIEW)
                        || actor.permissions().contains(PermissionCodes.CUSTOMER_CREATE);
        if (!allowed) {
            throw new ForbiddenException(mutate
                    ? "You do not have permission to update this customer"
                    : "You do not have permission to view this customer");
        }
        if (actor.unrestrictedBranches()) {
            return;
        }
        if (customer.branchId() == null || !actor.branchIds().contains(customer.branchId())) {
            throw new ForbiddenException("You cannot access a customer outside your branches");
        }
    }
}
