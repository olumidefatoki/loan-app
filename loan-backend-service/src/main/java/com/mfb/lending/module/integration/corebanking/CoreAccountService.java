package com.mfb.lending.module.integration.corebanking;

import com.mfb.lending.common.correlation.CorrelationIds;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import org.springframework.stereotype.Service;

@Service
public class CoreAccountService {

    private final CoreBankingAdapter adapter;

    public CoreAccountService(CoreBankingAdapter adapter) {
        this.adapter = adapter;
    }

    public CoreBankingAdapter.AccountList list(String customerReference) {
        if (customerReference == null || customerReference.isBlank()) {
            throw ValidationException.field("customerReference", "customerReference is required");
        }
        return call(() -> adapter.listAccounts(new CoreBankingAdapter.CustomerQuery(
                customerReference, CoreIds.correlation(CorrelationIds.current()))));
    }

    public CoreBankingAdapter.AccountView validate(String customerReference, String accountReference) {
        CoreBankingAdapter.AccountView account = call(() -> adapter.validateAccount(query(customerReference, accountReference)));
        if (!account.valid()) {
            throw ValidationException.field("accountReference", "Core banking did not validate the account");
        }
        return account;
    }

    public CoreBankingAdapter.BalanceView balance(String customerReference, String accountReference) {
        return call(() -> adapter.enquireBalance(query(customerReference, accountReference)));
    }

    public CoreBankingAdapter.AccountView status(String customerReference, String accountReference) {
        return call(() -> adapter.accountStatus(query(customerReference, accountReference)));
    }

    private <T> T call(java.util.function.Supplier<CoreBankingAdapter.AdapterResult<T>> supplier) {
        try {
            CoreBankingAdapter.AdapterResult<T> result = supplier.get();
            if (result.outcome() == CoreOutcome.NOT_FOUND) {
                throw new ResourceNotFoundException("Account");
            }
            if (result.outcome() != CoreOutcome.SUCCESSFUL || result.body() == null) {
                throw new ConflictException("Core banking account enquiry failed");
            }
            return result.body();
        } catch (CoreTransportException ex) {
            throw CoreFailures.api(ex);
        }
    }

    private static CoreBankingAdapter.AccountQuery query(String customerReference, String accountReference) {
        if (customerReference == null || customerReference.isBlank()) {
            throw ValidationException.field("customerReference", "customerReference is required");
        }
        return new CoreBankingAdapter.AccountQuery(customerReference, accountReference, CoreIds.correlation(CorrelationIds.current()));
    }
}
