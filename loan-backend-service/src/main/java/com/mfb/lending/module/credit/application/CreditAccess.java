package com.mfb.lending.module.credit.application;

import java.util.UUID;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.module.auth.security.AuthenticatedUser;

public final class CreditAccess {

    private CreditAccess() {
    }

    public static void assertBranch(AuthenticatedUser actor, UUID branchId) {
        if (actor.unrestrictedBranches()) {
            return;
        }
        if (branchId == null || !actor.branchIds().contains(branchId)) {
            throw new ForbiddenException("You cannot access a credit assessment outside your branches");
        }
    }
}
