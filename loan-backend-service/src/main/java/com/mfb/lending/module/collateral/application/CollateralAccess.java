package com.mfb.lending.module.collateral.application;

import java.util.UUID;

import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.collateral.domain.CollateralRecord;
import com.mfb.lending.module.collateral.persistence.CollateralRepository;
import com.mfb.lending.module.customer.application.CustomerAccess;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.persistence.CustomerRepository;

final class CollateralAccess {

    private CollateralAccess() {
    }

    static CustomerRecord customer(CustomerRepository customers, UUID customerId, AuthenticatedUser actor) {
        CustomerRecord customer = customers.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer"));
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            if (!CustomerAccess.isOwner(actor, customer)) {
                throw new ResourceNotFoundException("Customer");
            }
            return customer;
        }
        CustomerAccess.assertCanView(actor, customer);
        return customer;
    }

    static CollateralRecord visible(
            CollateralRepository collaterals,
            CustomerRepository customers,
            UUID collateralId,
            AuthenticatedUser actor) {
        CollateralRecord collateral = collaterals.find(collateralId)
                .orElseThrow(() -> new ResourceNotFoundException("Collateral"));
        customer(customers, collateral.customerId(), actor);
        return collateral;
    }

    static void assertStaff(AuthenticatedUser actor) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            throw new ForbiddenException("You do not have permission to change collateral");
        }
    }
}
