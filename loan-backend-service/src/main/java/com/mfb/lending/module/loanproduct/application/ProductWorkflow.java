package com.mfb.lending.module.loanproduct.application;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.loanproduct.domain.ProductStatus;

public final class ProductWorkflow {

    private ProductWorkflow() {
    }

    public static void assertSubmittable(ProductStatus status) {
        if (status != ProductStatus.DRAFT) {
            throw ValidationException.field("status", "Only a draft version can be submitted");
        }
    }

    public static void assertApprovable(ProductStatus status) {
        if (status != ProductStatus.PENDING_APPROVAL) {
            throw ValidationException.field("status", "Only a version awaiting approval can be approved");
        }
    }

    public static void assertActivatable(ProductStatus status) {
        if (status != ProductStatus.APPROVED) {
            throw ValidationException.field("status", "Approve the product before activation");
        }
    }

    public static void assertReactivatable(ProductStatus status) {
        if (status != ProductStatus.SUSPENDED) {
            throw ValidationException.field("status", "Approve the product before activation");
        }
    }

    public static void assertSuspendable(ProductStatus status) {
        if (status != ProductStatus.ACTIVE) {
            throw ValidationException.field("status", "Only an active product can be suspended");
        }
    }

    public static void assertDeactivatable(ProductStatus status) {
        if (status == ProductStatus.DEACTIVATED || status == ProductStatus.SUPERSEDED) {
            throw ValidationException.field("status", "This product is already deactivated");
        }
    }

    public static void assertDistinctChecker(String makerId, String actorId, String action) {
        if (makerId != null && makerId.equals(actorId)) {
            throw new ForbiddenException("A different administrator must " + action);
        }
    }

    public static boolean submitterMayRevise(ProductStatus status, String submittedBy, String actorId) {
        return status == ProductStatus.PENDING_APPROVAL && actorId.equals(submittedBy);
    }
}
