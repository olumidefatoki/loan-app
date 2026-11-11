package com.mfb.lending.module.integration.corebanking;

import com.mfb.lending.common.correlation.CorrelationIds;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ValidationException;
import org.springframework.stereotype.Service;

@Service
public class CoreCustomerService {

    private final CoreBankingAdapter adapter;

    public CoreCustomerService(CoreBankingAdapter adapter) {
        this.adapter = adapter;
    }

    public CoreBankingAdapter.CustomerView find(String customerReference) {
        try {
            return read(adapter.findCustomer(query(customerReference)));
        } catch (CoreTransportException ex) {
            throw CoreFailures.api(ex);
        }
    }

    public CoreBankingAdapter.CustomerView verify(String customerReference) {
        try {
            CoreBankingAdapter.CustomerView customer = read(adapter.verifyCustomer(query(customerReference)));
            if (!customer.verified()) {
                throw new ConflictException("Core banking did not verify the customer");
            }
            return customer;
        } catch (CoreTransportException ex) {
            throw CoreFailures.api(ex);
        }
    }

    public CoreBankingAdapter.CustomerView create(String customerReference, boolean approved) {
        if (!approved) {
            throw ValidationException.field("approved", "A customer is created in core banking only after approval");
        }
        try {
            var result = adapter.createCustomer(new CoreBankingAdapter.CustomerRegistration(customerReference, true, correlation()));
            if (result.outcome() != CoreOutcome.SUCCESSFUL || result.body() == null) {
                throw new ConflictException("Core banking did not create the customer");
            }
            return result.body();
        } catch (CoreTransportException ex) {
            throw CoreFailures.api(ex);
        }
    }

    private CoreBankingAdapter.CustomerView read(CoreBankingAdapter.AdapterResult<CoreBankingAdapter.CustomerView> result) {
        if (result.outcome() == CoreOutcome.NOT_FOUND) {
            throw new com.mfb.lending.common.exception.ResourceNotFoundException("Customer");
        }
        if (result.outcome() != CoreOutcome.SUCCESSFUL || result.body() == null) {
            throw new ConflictException("Core banking customer enquiry failed");
        }
        return result.body();
    }

    private static CoreBankingAdapter.CustomerQuery query(String customerReference) {
        if (customerReference == null || customerReference.isBlank()) {
            throw ValidationException.field("customerReference", "customerReference is required");
        }
        return new CoreBankingAdapter.CustomerQuery(customerReference, correlation());
    }

    private static String correlation() {
        return CoreIds.correlation(CorrelationIds.current());
    }
}
