package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;

import com.mfb.lending.common.correlation.CorrelationIds;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import org.springframework.stereotype.Service;

@Service
public class CoreLoanAccountService {

    private final CoreBankingAdapter adapter;

    public CoreLoanAccountService(CoreBankingAdapter adapter) {
        this.adapter = adapter;
    }

    public CoreBankingAdapter.LoanAccountView register(String transactionReference, String idempotencyKey, String customerReference, BigDecimal amount) {
        try {
            var result = adapter.createLoanAccount(new CoreBankingAdapter.LoanAccountCommand(
                    transactionReference,
                    CoreIds.idempotency(idempotencyKey),
                    customerReference,
                    CoreMoney.ngn(amount, "amount"),
                    "NGN",
                    CoreIds.correlation(CorrelationIds.current())));
            if (result.outcome() != CoreOutcome.SUCCESSFUL || result.body() == null) {
                throw new ConflictException("Core banking did not register the loan account");
            }
            return result.body();
        } catch (CoreTransportException ex) {
            throw CoreFailures.api(ex);
        }
    }

    public CoreBankingAdapter.LoanAccountView get(String transactionReference) {
        try {
            return read(adapter.getLoanAccount(lookup(transactionReference)));
        } catch (CoreTransportException ex) {
            throw CoreFailures.api(ex);
        }
    }

    public CoreBankingAdapter.LoanAccountView status(String transactionReference) {
        try {
            return read(adapter.loanAccountStatus(lookup(transactionReference)));
        } catch (CoreTransportException ex) {
            throw CoreFailures.api(ex);
        }
    }

    private CoreBankingAdapter.LoanAccountView read(CoreBankingAdapter.AdapterResult<CoreBankingAdapter.LoanAccountView> result) {
        if (result.outcome() == CoreOutcome.NOT_FOUND) {
            throw new ResourceNotFoundException("Loan account");
        }
        if (result.outcome() != CoreOutcome.SUCCESSFUL || result.body() == null) {
            throw new ConflictException("Core banking loan account enquiry failed");
        }
        return result.body();
    }

    private static CoreBankingAdapter.LoanAccountLookup lookup(String transactionReference) {
        if (transactionReference == null || transactionReference.isBlank()) {
            throw ValidationException.field("transactionReference", "transactionReference is required");
        }
        return new CoreBankingAdapter.LoanAccountLookup(null, transactionReference, CoreIds.correlation(CorrelationIds.current()));
    }
}
