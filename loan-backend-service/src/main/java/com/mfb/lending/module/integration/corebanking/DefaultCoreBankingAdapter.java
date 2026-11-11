package com.mfb.lending.module.integration.corebanking;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.mfb.lending.common.correlation.CorrelationIds;

public class DefaultCoreBankingAdapter implements CoreBankingAdapter {

    private final CoreBankingClient client;
    private final CoreBankingMapper mapper;
    private final CoreBankingStore store;
    private final IntegrationCircuitBreaker circuit;
    private final SafeRetry retry;
    private final CoreBankingMetrics metrics;
    private final java.time.Clock clock;

    public DefaultCoreBankingAdapter(
            CoreBankingClient client,
            CoreBankingMapper mapper,
            CoreBankingStore store,
            IntegrationCircuitBreaker circuit,
            SafeRetry retry,
            CoreBankingMetrics metrics,
            java.time.Clock clock) {
        this.client = client;
        this.mapper = mapper;
        this.store = store;
        this.circuit = circuit;
        this.retry = retry;
        this.metrics = metrics;
        this.clock = clock;
    }

    @Override
    public AdapterResult<CustomerView> findCustomer(CustomerQuery query) {
        return call(CoreOperation.FIND_CUSTOMER, null, null, query.correlationId(), attributes("customerReference", query.customerReference()), mapper::customer);
    }

    @Override
    public AdapterResult<CustomerView> verifyCustomer(CustomerQuery query) {
        return call(CoreOperation.VERIFY_CUSTOMER, null, null, query.correlationId(), attributes("customerReference", query.customerReference()), mapper::customer);
    }

    @Override
    public AdapterResult<CustomerView> createCustomer(CustomerRegistration command) {
        Map<String, Object> attributes = attributes("customerReference", command.customerReference());
        attributes.put("approved", command.approved());
        return call(CoreOperation.CREATE_CUSTOMER, command.customerReference(), null, command.correlationId(), attributes, mapper::customer);
    }

    @Override
    public AdapterResult<AccountList> listAccounts(CustomerQuery query) {
        return call(CoreOperation.LIST_ACCOUNTS, null, null, query.correlationId(), attributes("customerReference", query.customerReference()), mapper::accounts);
    }

    @Override
    public AdapterResult<AccountView> validateAccount(AccountQuery query) {
        return call(CoreOperation.VALIDATE_ACCOUNT, null, null, query.correlationId(), accountAttributes(query), mapper::account);
    }

    @Override
    public AdapterResult<BalanceView> enquireBalance(AccountQuery query) {
        return call(CoreOperation.ENQUIRE_BALANCE, null, null, query.correlationId(), accountAttributes(query), mapper::balance);
    }

    @Override
    public AdapterResult<AccountView> accountStatus(AccountQuery query) {
        return call(CoreOperation.ACCOUNT_STATUS, null, null, query.correlationId(), accountAttributes(query), mapper::account);
    }

    @Override
    public AdapterResult<LoanAccountView> createLoanAccount(LoanAccountCommand command) {
        Map<String, Object> attributes = attributes("customerReference", command.customerReference());
        attributes.put("amount", command.amount().toPlainString());
        attributes.put("currencyCode", command.currencyCode());
        return call(
                CoreOperation.CREATE_LOAN_ACCOUNT,
                command.idempotencyKey(),
                command.transactionReference(),
                command.correlationId(),
                attributes,
                mapper::loanAccount);
    }

    @Override
    public AdapterResult<LoanAccountView> getLoanAccount(LoanAccountLookup query) {
        return call(CoreOperation.GET_LOAN_ACCOUNT, null, query.transactionReference(), query.correlationId(), loanAttributes(query), mapper::loanAccount);
    }

    @Override
    public AdapterResult<LoanAccountView> loanAccountStatus(LoanAccountLookup query) {
        return call(CoreOperation.LOAN_ACCOUNT_STATUS, null, query.transactionReference(), query.correlationId(), loanAttributes(query), mapper::loanAccount);
    }

    @Override
    public AdapterResult<PostingView> submitDisbursement(PaymentCommand command) {
        return payment(CoreOperation.SUBMIT_DISBURSEMENT, command);
    }

    @Override
    public AdapterResult<PostingView> disbursementStatus(StatusQuery query) {
        return status(CoreOperation.DISBURSEMENT_STATUS, query);
    }

    @Override
    public AdapterResult<PostingView> transactionReference(StatusQuery query) {
        return status(CoreOperation.TRANSACTION_REFERENCE, query);
    }

    @Override
    public AdapterResult<PostingView> submitRepayment(PaymentCommand command) {
        return payment(CoreOperation.SUBMIT_REPAYMENT, command);
    }

    @Override
    public AdapterResult<PostingView> repaymentStatus(StatusQuery query) {
        return status(CoreOperation.REPAYMENT_STATUS, query);
    }

    @Override
    public AdapterResult<PostingView> notifyReversal(ReversalCommand command) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("originalReference", command.originalReference());
        attributes.put("amount", command.amount().toPlainString());
        attributes.put("currencyCode", command.currencyCode());
        return call(CoreOperation.REVERSAL, command.idempotencyKey(), command.transactionReference(), command.correlationId(), attributes, mapper::posting);
    }

    @Override
    public AdapterResult<PostingList> transactionPostings(StatusQuery query) {
        return call(CoreOperation.TRANSACTION_POSTINGS, null, query.transactionReference(), query.correlationId(), statusAttributes(query), mapper::postings);
    }

    @Override
    public AdapterResult<PostingList> glTransactions(StatusQuery query) {
        return call(CoreOperation.GL_TRANSACTIONS, null, query.transactionReference(), query.correlationId(), statusAttributes(query), mapper::postings);
    }

    private AdapterResult<PostingView> payment(CoreOperation operation, PaymentCommand command) {
        Map<String, Object> attributes = attributes("customerReference", command.customerReference());
        attributes.put("accountReference", command.accountReference());
        attributes.put("loanAccountReference", command.loanAccountReference());
        attributes.put("amount", command.amount().toPlainString());
        attributes.put("currencyCode", command.currencyCode());
        return call(operation, command.idempotencyKey(), command.transactionReference(), command.correlationId(), attributes, mapper::posting);
    }

    private AdapterResult<PostingView> status(CoreOperation operation, StatusQuery query) {
        return call(operation, null, query.transactionReference(), query.correlationId(), statusAttributes(query), mapper::posting);
    }

    private <T> AdapterResult<T> call(
            CoreOperation operation,
            String idempotencyKey,
            String transactionReference,
            String correlationId,
            Map<String, Object> attributes,
            Function<CoreBankingClient.CoreTransportResponse, T> body) {
        CoreBankingClient.CoreTransportRequest request = mapper.request(
                operation,
                idempotencyKey,
                transactionReference,
                correlationId == null ? CorrelationIds.current() : correlationId,
                attributes);
        int attempts = operation.safeToRetry() ? retry.maxAttempts() : 1;
        CoreTransportException last = null;
        for (int attempt = 1; attempt <= attempts; attempt++) {
            if (!circuit.allow(Instant.now(clock))) {
                metrics.call(operation, CoreTransportException.Failure.CIRCUIT_OPEN.name());
                throw new CoreTransportException(CoreTransportException.Failure.CIRCUIT_OPEN, "Core banking circuit is open");
            }
            UUID requestId = store.insertRequest(
                    transactionId(transactionReference),
                    operation.name(),
                    idempotencyKey,
                    request.correlationId(),
                    mapper.json(Map.of("operation", operation.name(), "transactionReference", transactionReference == null ? "" : transactionReference)));
            long started = System.nanoTime();
            try {
                CoreBankingClient.CoreTransportResponse response = client.exchange(request);
                circuit.success();
                store.insertResponse(requestId, response.outcome().name(), response.providerReference(), mapper.json(response.attributes()), latency(started));
                metrics.call(operation, response.outcome().name());
                return mapper.result(response, body.apply(response));
            } catch (CoreTransportException ex) {
                last = ex;
                if (ex.failure().opensCircuit()) {
                    circuit.failure(Instant.now(clock));
                }
                store.insertResponse(requestId, ex.failure().name(), null, "{}", latency(started));
                metrics.call(operation, ex.failure().name());
                if (!(operation.safeToRetry() && ex.failure().uncertain() && attempt < attempts)) {
                    throw ex;
                }
                retry.pause(attempt);
            }
        }
        throw last == null
                ? new CoreTransportException(CoreTransportException.Failure.UNAVAILABLE, "Core banking request failed")
                : last;
    }

    private UUID transactionId(String reference) {
        if (reference == null || reference.isBlank()) {
            return null;
        }
        return store.findByReference(reference).map(CoreTransaction::id).orElse(null);
    }

    private static int latency(long started) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - started) / 1_000_000L);
    }

    private static Map<String, Object> attributes(String key, String value) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put(key, value);
        return attributes;
    }

    private static Map<String, Object> accountAttributes(AccountQuery query) {
        Map<String, Object> attributes = attributes("customerReference", query.customerReference());
        attributes.put("accountReference", query.accountReference());
        return attributes;
    }

    private static Map<String, Object> loanAttributes(LoanAccountLookup query) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("loanAccountReference", query.loanAccountReference());
        attributes.put("transactionReference", query.transactionReference());
        return attributes;
    }

    private static Map<String, Object> statusAttributes(StatusQuery query) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("transactionReference", query.transactionReference());
        attributes.put("providerReference", query.providerReference());
        return attributes;
    }
}
