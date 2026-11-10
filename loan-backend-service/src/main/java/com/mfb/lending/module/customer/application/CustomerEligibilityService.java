package com.mfb.lending.module.customer.application;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.persistence.CustomerAccountRepository;
import com.mfb.lending.module.customer.persistence.CustomerKycRepository;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class CustomerEligibilityService {

    private final CustomerRepository customers;
    private final CustomerAccountRepository accounts;
    private final CustomerKycRepository kyc;

    public CustomerEligibilityService(
            CustomerRepository customers,
            CustomerAccountRepository accounts,
            CustomerKycRepository kyc) {
        this.customers = customers;
        this.accounts = accounts;
        this.kyc = kyc;
    }

    public List<String> blockedReasons(CustomerRecord customer) {
        return DisbursementPolicy.blockedReasons(
                customer,
                kyc.openMandatoryCount(customer.id()) > 0,
                accounts.verifiedCount(customer.id()) > 0);
    }

    public void assertDisbursementAllowed(UUID customerId) {
        CustomerRecord customer = customers.findById(customerId).orElseThrow(() -> new ResourceNotFoundException("Customer"));
        if (!blockedReasons(customer).isEmpty()) {
            throw new ApiException(
                    ErrorCode.COMPLIANCE_HOLD,
                    HttpStatus.CONFLICT,
                    "The customer cannot proceed to disbursement");
        }
    }
}
