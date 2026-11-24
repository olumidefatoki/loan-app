package com.mfb.lending.module.reporting.application;

import java.util.UUID;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import org.springframework.stereotype.Component;

@Component
public class ReportingAccess {

    private final CustomerRepository customers;

    public ReportingAccess(CustomerRepository customers) {
        this.customers = customers;
    }

    public UUID ownCustomer(AuthenticatedUser actor) {
        if (actor == null || actor.getPrincipalType() != PrincipalType.CUSTOMER) {
            throw new ForbiddenException("This dashboard is for customer accounts");
        }
        CustomerRecord customer = customers.findByUserId(actor.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer"));
        if (customer.userId() == null || !customer.userId().equals(actor.getId())) {
            throw new ResourceNotFoundException("Customer");
        }
        return customer.id();
    }

    public void viewOperations(AuthenticatedUser actor) {
        requireStaff(actor, operational(actor) || reports(actor), "You do not have permission to view the operations dashboard");
    }

    public void viewCredit(AuthenticatedUser actor) {
        requireStaff(actor, reports(actor)
                || holds(actor, PermissionCodes.CREDIT_ASSESSMENT_VIEW)
                || holds(actor, PermissionCodes.LOAN_APPROVE), "You do not have permission to view the credit dashboard");
    }

    public void viewCollections(AuthenticatedUser actor) {
        requireStaff(actor, operational(actor) || reports(actor)
                || holds(actor, PermissionCodes.COLLECTION_VIEW)
                || holds(actor, PermissionCodes.REPAYMENT_VIEW), "You do not have permission to view the collections dashboard");
    }

    public void viewBranch(AuthenticatedUser actor) {
        requireStaff(actor, operational(actor) || reports(actor), "You do not have permission to view branch performance");
    }

    public void viewFinancial(AuthenticatedUser actor) {
        requireStaff(actor, reports(actor), "You do not have permission to view financial reports");
    }

    public void export(AuthenticatedUser actor) {
        requireStaff(actor, holds(actor, PermissionCodes.REPORT_EXPORT), "You do not have permission to export reports");
    }

    private static boolean operational(AuthenticatedUser actor) {
        return holds(actor, PermissionCodes.LOAN_APPLICATION_VIEW)
                || holds(actor, PermissionCodes.LOAN_APPLICATION_CREATE)
                || holds(actor, PermissionCodes.LOAN_DISBURSE)
                || holds(actor, PermissionCodes.LOAN_APPROVE);
    }

    private static boolean reports(AuthenticatedUser actor) {
        return holds(actor, PermissionCodes.REPORT_VIEW);
    }

    private static boolean holds(AuthenticatedUser actor, String permission) {
        return actor.permissions().contains(permission);
    }

    private static void requireStaff(AuthenticatedUser actor, boolean allowed, String message) {
        if (actor == null || actor.getPrincipalType() != PrincipalType.STAFF || !allowed) {
            throw new ForbiddenException(message);
        }
    }
}
