package com.mfb.lending.module.loanapplication.application;

import java.util.UUID;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.application.CustomerAccess;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;

public final class ApplicationAccess {

    private ApplicationAccess() {
    }

    public static void assertCanView(AuthenticatedUser actor, ApplicationRecord application, CustomerRecord customer) {
        if (CustomerAccess.isOwner(actor, customer)) {
            return;
        }
        assertStaff(actor, application, false);
    }

    public static void assertCanMutate(AuthenticatedUser actor, ApplicationRecord application, CustomerRecord customer) {
        if (CustomerAccess.isOwner(actor, customer)) {
            return;
        }
        assertStaff(actor, application, true);
    }

    public static void assertCanOriginate(AuthenticatedUser actor, CustomerRecord customer) {
        if (CustomerAccess.isOwner(actor, customer)) {
            return;
        }
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            throw new ResourceNotFoundException("Customer");
        }
        if (!actor.permissions().contains(PermissionCodes.LOAN_APPLICATION_CREATE)) {
            throw new ForbiddenException("You do not have permission to create a loan application");
        }
        assertBranch(actor, customer.branchId());
    }

    public static void assertCanTransition(AuthenticatedUser actor, ApplicationStatus target) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            throw new ForbiddenException("You do not have permission to move this application");
        }
        boolean allowed = switch (target) {
            case REJECTED -> actor.permissions().contains(PermissionCodes.LOAN_REJECT);
            case APPROVED, OFFER_ISSUED -> actor.permissions().contains(PermissionCodes.LOAN_APPROVE);
            case DISBURSED -> actor.permissions().contains(PermissionCodes.LOAN_DISBURSE);
            case PENDING_DISBURSEMENT -> actor.permissions().contains(PermissionCodes.LOAN_DISBURSE)
                    || actor.permissions().contains(PermissionCodes.LOAN_APPROVE);
            default -> actor.permissions().contains(PermissionCodes.LOAN_APPLICATION_CREATE)
                    || actor.permissions().contains(PermissionCodes.LOAN_APPROVE)
                    || actor.permissions().contains(PermissionCodes.LOAN_REJECT)
                    || actor.permissions().contains(PermissionCodes.LOAN_DISBURSE);
        };
        if (!allowed) {
            throw new ForbiddenException("You do not have permission to move this application");
        }
    }

    private static void assertStaff(AuthenticatedUser actor, ApplicationRecord application, boolean mutate) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            throw new ResourceNotFoundException("Loan application");
        }
        boolean allowed = mutate
                ? actor.permissions().contains(PermissionCodes.LOAN_APPLICATION_CREATE)
                : canRead(actor);
        if (!allowed) {
            throw new ForbiddenException(mutate
                    ? "You do not have permission to update this application"
                    : "You do not have permission to view this application");
        }
        assertBranch(actor, application.branchId());
    }

    public static boolean canRead(AuthenticatedUser actor) {
        return actor.permissions().contains(PermissionCodes.LOAN_APPLICATION_VIEW)
                || actor.permissions().contains(PermissionCodes.LOAN_APPLICATION_CREATE)
                || actor.permissions().contains(PermissionCodes.LOAN_APPROVE)
                || actor.permissions().contains(PermissionCodes.LOAN_REJECT)
                || actor.permissions().contains(PermissionCodes.LOAN_DISBURSE);
    }

    public static void assertBranch(AuthenticatedUser actor, UUID branchId) {
        if (actor.unrestrictedBranches()) {
            return;
        }
        if (branchId == null || !actor.branchIds().contains(branchId)) {
            throw new ForbiddenException("You cannot access an application outside your branches");
        }
    }
}
