package com.mfb.lending.module.collections.application;

import java.util.Set;
import java.util.UUID;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionCase;
import com.mfb.lending.module.collections.domain.CollectionModels.LoanPosition;
import com.mfb.lending.module.customer.application.CustomerAccess;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import com.mfb.lending.module.loanapplication.application.ApplicationAccess;
import org.springframework.stereotype.Component;

@Component
public class CollectionsAccess {

    private final CustomerRepository customers;

    public CollectionsAccess(CustomerRepository customers) {
        this.customers = customers;
    }

    public Set<UUID> caseBranches(AuthenticatedUser actor, UUID requestedBranchId) {
        requireStaff(actor, canViewCases(actor), "You do not have permission to view collection cases");
        if (actor.unrestrictedBranches()) {
            return requestedBranchId == null ? null : Set.of(requestedBranchId);
        }
        if (requestedBranchId != null && !actor.branchIds().contains(requestedBranchId)) {
            throw new ForbiddenException("You cannot access a collection case outside your branches");
        }
        return requestedBranchId == null ? actor.branchIds() : Set.of(requestedBranchId);
    }

    public void viewCase(AuthenticatedUser actor, CollectionCase collectionCase) {
        requireStaff(actor, canViewCases(actor), "You do not have permission to view collection cases");
        ApplicationAccess.assertBranch(actor, collectionCase.branchId());
    }

    public void manage(AuthenticatedUser actor, LoanPosition position) {
        requireStaff(actor, actor.permissions().contains(PermissionCodes.COLLECTION_MANAGE), "You do not have permission to manage collections");
        ApplicationAccess.assertBranch(actor, position.branchId());
    }

    public void manage(AuthenticatedUser actor, CollectionCase collectionCase) {
        requireStaff(actor, actor.permissions().contains(PermissionCodes.COLLECTION_MANAGE), "You do not have permission to manage collections");
        ApplicationAccess.assertBranch(actor, collectionCase.branchId());
    }

    public void viewReports(AuthenticatedUser actor) {
        requireStaff(actor, actor.permissions().contains(PermissionCodes.REPORT_VIEW) || canViewCases(actor), "You do not have permission to view collection reports");
    }

    public void viewRestructuring(AuthenticatedUser actor, LoanPosition position) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            own(actor, position);
            return;
        }
        requireStaff(actor, canViewCases(actor)
                || actor.permissions().contains(PermissionCodes.RESTRUCTURING_APPROVE)
                || actor.permissions().contains(PermissionCodes.REPAYMENT_VIEW), "You do not have permission to view restructuring history");
        ApplicationAccess.assertBranch(actor, position.branchId());
    }

    public void requestRestructuring(AuthenticatedUser actor, LoanPosition position) {
        manage(actor, position);
    }

    public void decideRestructuring(AuthenticatedUser actor, LoanPosition position) {
        requireStaff(actor, actor.permissions().contains(PermissionCodes.RESTRUCTURING_APPROVE), "You do not have permission to decide a restructuring");
        ApplicationAccess.assertBranch(actor, position.branchId());
    }

    private void own(AuthenticatedUser actor, LoanPosition position) {
        CustomerRecord customer = customers.findByUserId(actor.getId()).orElseThrow(() -> new ResourceNotFoundException("Loan account"));
        if (!CustomerAccess.isOwner(actor, customer) || !customer.id().equals(position.customerId())) {
            throw new ResourceNotFoundException("Loan account");
        }
    }

    private static void requireStaff(AuthenticatedUser actor, boolean allowed, String message) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER || !allowed) {
            throw new ForbiddenException(message);
        }
    }

    private static boolean canViewCases(AuthenticatedUser actor) {
        return actor.permissions().contains(PermissionCodes.COLLECTION_VIEW) || actor.permissions().contains(PermissionCodes.COLLECTION_MANAGE);
    }
}
