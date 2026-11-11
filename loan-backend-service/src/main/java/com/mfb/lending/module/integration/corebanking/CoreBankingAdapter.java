package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.util.List;

public interface CoreBankingAdapter {

    AdapterResult<CustomerView> findCustomer(CustomerQuery query);

    AdapterResult<CustomerView> verifyCustomer(CustomerQuery query);

    AdapterResult<CustomerView> createCustomer(CustomerRegistration command);

    AdapterResult<AccountList> listAccounts(CustomerQuery query);

    AdapterResult<AccountView> validateAccount(AccountQuery query);

    AdapterResult<BalanceView> enquireBalance(AccountQuery query);

    AdapterResult<AccountView> accountStatus(AccountQuery query);

    AdapterResult<LoanAccountView> createLoanAccount(LoanAccountCommand command);

    AdapterResult<LoanAccountView> getLoanAccount(LoanAccountLookup query);

    AdapterResult<LoanAccountView> loanAccountStatus(LoanAccountLookup query);

    AdapterResult<PostingView> submitDisbursement(PaymentCommand command);

    AdapterResult<PostingView> disbursementStatus(StatusQuery query);

    AdapterResult<PostingView> transactionReference(StatusQuery query);

    AdapterResult<PostingView> submitRepayment(PaymentCommand command);

    AdapterResult<PostingView> repaymentStatus(StatusQuery query);

    AdapterResult<PostingView> notifyReversal(ReversalCommand command);

    AdapterResult<PostingList> transactionPostings(StatusQuery query);

    AdapterResult<PostingList> glTransactions(StatusQuery query);

    record AdapterResult<T>(CoreOutcome outcome, String providerReference, String message, T body) {
    }

    record CustomerQuery(String customerReference, String correlationId) {
    }

    record CustomerRegistration(String customerReference, boolean approved, String correlationId) {
    }

    record AccountQuery(String customerReference, String accountReference, String correlationId) {
    }

    record LoanAccountCommand(
            String transactionReference,
            String idempotencyKey,
            String customerReference,
            BigDecimal amount,
            String currencyCode,
            String correlationId
    ) {
    }

    record LoanAccountLookup(String loanAccountReference, String transactionReference, String correlationId) {
    }

    record PaymentCommand(
            String transactionReference,
            String idempotencyKey,
            String customerReference,
            String accountReference,
            String loanAccountReference,
            BigDecimal amount,
            String currencyCode,
            String correlationId
    ) {
    }

    record StatusQuery(String transactionReference, String providerReference, String correlationId) {
    }

    record ReversalCommand(
            String transactionReference,
            String idempotencyKey,
            String originalReference,
            BigDecimal amount,
            String currencyCode,
            String correlationId
    ) {
    }

    record CustomerView(String customerReference, boolean verified, String displayName) {
    }

    record AccountView(String accountReference, boolean valid, String status, String maskedNumber) {
    }

    record AccountList(List<AccountView> accounts) {
    }

    record BalanceView(String accountReference, BigDecimal amount, String currencyCode, String status) {
    }

    record LoanAccountView(String loanAccountReference, String status) {
    }

    record PostingView(String providerReference, boolean posted, boolean reversed, BigDecimal amount, String currencyCode) {
    }

    record PostingLine(String reference, BigDecimal amount, String currencyCode, String direction) {
    }

    record PostingList(List<PostingLine> lines) {
    }
}
