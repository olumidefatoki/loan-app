package com.mfb.lending.module.servicing.application;

import java.util.UUID;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.application.CustomerAccess;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import com.mfb.lending.module.loanapplication.application.ApplicationAccess;
import com.mfb.lending.module.servicing.domain.LoanAccount;
import org.springframework.stereotype.Component;

@Component
public class ServicingAccess {

    private final CustomerRepository customers;

    public ServicingAccess(CustomerRepository customers) {
        this.customers = customers;
    }

    public UUID customerScope(AuthenticatedUser actor, UUID requestedCustomerId) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            CustomerRecord customer = ownCustomer(actor);
            if (requestedCustomerId != null && !requestedCustomerId.equals(customer.id())) {
                throw new ResourceNotFoundException("Loan account");
            }
            return customer.id();
        }
        if (!canRead(actor)) {
            throw new ForbiddenException("You do not have permission to view loan accounts");
        }
        return requestedCustomerId;
    }

    public void view(AuthenticatedUser actor, LoanAccount account) {
        if (owns(actor, account)) {
            return;
        }
        requireStaff(actor, account, canRead(actor), "You do not have permission to view this loan account");
    }

    public void repay(AuthenticatedUser actor, LoanAccount account) {
        if (owns(actor, account)) {
            return;
        }
        requireStaff(actor, account, actor.permissions().contains(PermissionCodes.REPAYMENT_PROCESS), "You do not have permission to process a repayment");
    }

    public void administer(AuthenticatedUser actor, LoanAccount account) {
        requireStaff(actor, account, actor.permissions().contains(PermissionCodes.REPAYMENT_PROCESS), "You do not have permission to change this loan account");
    }

    public void viewJournals(AuthenticatedUser actor) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER
                || !(actor.permissions().contains(PermissionCodes.REPORT_VIEW) || actor.permissions().contains(PermissionCodes.SYSTEM_CONFIGURE))) {
            throw new ForbiddenException("You do not have permission to view journals");
        }
    }

    public void approve(AuthenticatedUser actor) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER || !actor.permissions().contains(PermissionCodes.SYSTEM_CONFIGURE)) {
            throw new ForbiddenException("You do not have permission to approve a journal");
        }
    }

    private void requireStaff(AuthenticatedUser actor, LoanAccount account, boolean allowed, String message) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            throw new ResourceNotFoundException("Loan account");
        }
        if (!allowed) {
            throw new ForbiddenException(message);
        }
        ApplicationAccess.assertBranch(actor, account.branchId());
    }

    private boolean owns(AuthenticatedUser actor, LoanAccount account) {
        if (actor.getPrincipalType() != PrincipalType.CUSTOMER) {
            return false;
        }
        CustomerRecord customer = ownCustomer(actor);
        if (!CustomerAccess.isOwner(actor, customer) || !customer.id().equals(account.customerId())) {
            throw new ResourceNotFoundException("Loan account");
        }
        return true;
    }

    private CustomerRecord ownCustomer(AuthenticatedUser actor) {
        return customers.findByUserId(actor.getId()).orElseThrow(() -> new ResourceNotFoundException("Customer"));
    }

    private static boolean canRead(AuthenticatedUser actor) {
        return actor.permissions().contains(PermissionCodes.REPAYMENT_VIEW)
                || actor.permissions().contains(PermissionCodes.REPAYMENT_PROCESS)
                || actor.permissions().contains(PermissionCodes.LOAN_APPLICATION_VIEW)
                || actor.permissions().contains(PermissionCodes.REPORT_VIEW);
    }
}
