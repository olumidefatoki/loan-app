package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

public class MockCoreBankingClient implements CoreBankingClient {

    private final MockScenarioRegistry registry;
    private final Map<String, Posting> ledger = new ConcurrentHashMap<>();
    private final Map<String, String> loanAccounts = new ConcurrentHashMap<>();
    private final Map<CoreOperation, AtomicInteger> calls = new ConcurrentHashMap<>();

    public MockCoreBankingClient(MockScenarioRegistry registry) {
        this.registry = registry;
    }

    public int calls(CoreOperation operation) {
        AtomicInteger count = calls.get(operation);
        return count == null ? 0 : count.get();
    }

    @Override
    public CoreTransportResponse exchange(CoreTransportRequest request) {
        calls.computeIfAbsent(request.operation(), ignored -> new AtomicInteger()).incrementAndGet();
        if (registry.authenticationFailure() && !request.operation().safeToRetry()) {
            throw new CoreTransportException(
                    CoreTransportException.Failure.AUTHENTICATION,
                    "Mock core banking rejected the credentials");
        }
        if (registry.unavailable() && !request.operation().safeToRetry()) {
            throw new CoreTransportException(
                    CoreTransportException.Failure.UNAVAILABLE,
                    "Mock core banking is unavailable");
        }
        CoreOutcome forced = registry.forced(request.operation());
        if (forced != null && forced != CoreOutcome.SUCCESSFUL) {
            return new CoreTransportResponse(forced, null, "Mock " + request.operation().name() + " failed", Map.of());
        }
        MockScenarioRegistry.Script script = registry.script(request.idempotencyKey());
        if (script == MockScenarioRegistry.Script.TIMEOUT && submits(request.operation())) {
            throw timeout();
        }
        if (script == MockScenarioRegistry.Script.LOST_RESPONSE && request.operation() == CoreOperation.CREATE_LOAN_ACCOUNT) {
            createLoan(request);
            throw timeout();
        }
        if (script == MockScenarioRegistry.Script.LOST_RESPONSE && submits(request.operation())) {
            remember(request, true);
            throw timeout();
        }
        if (script == MockScenarioRegistry.Script.REJECTED && submits(request.operation())) {
            return new CoreTransportResponse(CoreOutcome.REJECTED, null, "Mock instruction rejected", attributes(request, false));
        }
        return switch (request.operation()) {
            case FIND_CUSTOMER, VERIFY_CUSTOMER, CREATE_CUSTOMER -> customer(request);
            case LIST_ACCOUNTS -> accounts(request);
            case VALIDATE_ACCOUNT, ACCOUNT_STATUS -> account(request);
            case ENQUIRE_BALANCE -> balance(request);
            case CREATE_LOAN_ACCOUNT -> createLoan(request);
            case GET_LOAN_ACCOUNT, LOAN_ACCOUNT_STATUS -> readLoan(request);
            case SUBMIT_DISBURSEMENT, SUBMIT_REPAYMENT, REVERSAL -> submit(request);
            case DISBURSEMENT_STATUS, REPAYMENT_STATUS, TRANSACTION_REFERENCE -> status(request);
            case TRANSACTION_POSTINGS, GL_TRANSACTIONS -> postings(request);
        };
    }

    private CoreTransportResponse customer(CoreTransportRequest request) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("customerReference", request.attributes().get("customerReference"));
        attributes.put("verified", true);
        attributes.put("displayName", "Mock customer");
        return new CoreTransportResponse(CoreOutcome.SUCCESSFUL, null, "Mock customer", attributes);
    }

    private CoreTransportResponse accounts(CoreTransportRequest request) {
        Map<String, Object> account = accountAttributes(request);
        return new CoreTransportResponse(CoreOutcome.SUCCESSFUL, null, "Mock accounts", Map.of("accounts", List.of(account)));
    }

    private CoreTransportResponse account(CoreTransportRequest request) {
        return new CoreTransportResponse(CoreOutcome.SUCCESSFUL, null, "Mock account", accountAttributes(request));
    }

    private CoreTransportResponse balance(CoreTransportRequest request) {
        Map<String, Object> attributes = accountAttributes(request);
        attributes.put("amount", "0.00");
        attributes.put("currencyCode", "NGN");
        return new CoreTransportResponse(CoreOutcome.SUCCESSFUL, null, "Mock balance", attributes);
    }

    private CoreTransportResponse createLoan(CoreTransportRequest request) {
        String reference = request.transactionReference();
        String loanReference = "MOCK-LA-" + reference;
        loanAccounts.put(reference, loanReference);
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("loanAccountReference", loanReference);
        attributes.put("status", "OPEN");
        return new CoreTransportResponse(CoreOutcome.SUCCESSFUL, loanReference, "Mock loan account", attributes);
    }

    private CoreTransportResponse readLoan(CoreTransportRequest request) {
        String loanReference = loanAccounts.get(request.transactionReference());
        if (loanReference == null) {
            return new CoreTransportResponse(CoreOutcome.NOT_FOUND, null, "Mock loan account was not found", Map.of());
        }
        return new CoreTransportResponse(
                CoreOutcome.SUCCESSFUL,
                loanReference,
                "Mock loan account",
                Map.of("loanAccountReference", loanReference, "status", "OPEN"));
    }

    private CoreTransportResponse submit(CoreTransportRequest request) {
        remember(request, true);
        return posted(request, ledger.get(request.transactionReference()));
    }

    private CoreTransportResponse status(CoreTransportRequest request) {
        Posting posting = findPosting(request);
        if (posting == null) {
            return new CoreTransportResponse(CoreOutcome.NOT_FOUND, null, "Mock posting was not found", Map.of());
        }
        if (registry.reversed(request.transactionReference())) {
            posting = posting.asReversed();
        }
        return posted(request, posting);
    }

    private CoreTransportResponse postings(CoreTransportRequest request) {
        Posting posting = findPosting(request);
        if (posting == null) {
            return new CoreTransportResponse(CoreOutcome.NOT_FOUND, null, "Mock posting was not found", Map.of("lines", List.of()));
        }
        BigDecimal amount = registry.mismatch(request.transactionReference());
        if (amount == null) {
            amount = posting.amount();
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("reference", posting.providerReference());
        line.put("amount", amount.toPlainString());
        line.put("currencyCode", posting.currencyCode());
        line.put("direction", posting.originalWasReversed() ? "REVERSAL" : "POSTING");
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("lines", List.of(line));
        CoreOutcome outcome = amount.compareTo(posting.amount()) == 0 ? CoreOutcome.SUCCESSFUL : CoreOutcome.SUCCESSFUL;
        return new CoreTransportResponse(outcome, posting.providerReference(), "Mock postings", attributes);
    }

    private void remember(CoreTransportRequest request, boolean posted) {
        BigDecimal amount = new BigDecimal(String.valueOf(request.attributes().getOrDefault("amount", "0.00")));
        String currency = String.valueOf(request.attributes().getOrDefault("currencyCode", "NGN"));
        ledger.put(request.transactionReference(), new Posting(
                "MOCK-" + request.transactionReference(),
                amount,
                currency,
                posted,
                false));
        if (request.operation() == CoreOperation.REVERSAL) {
            String original = String.valueOf(request.attributes().get("originalReference"));
            Posting existing = ledger.get(original);
            if (existing != null) {
                ledger.put(original, existing.asReversed());
            }
        }
    }

    private CoreTransportResponse posted(CoreTransportRequest request, Posting posting) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("posted", posting.posted() && !posting.originalWasReversed());
        attributes.put("reversed", posting.originalWasReversed());
        attributes.put("amount", posting.amount().toPlainString());
        attributes.put("currencyCode", posting.currencyCode());
        return new CoreTransportResponse(CoreOutcome.SUCCESSFUL, posting.providerReference(), "Mock posting", attributes);
    }

    private static Map<String, Object> accountAttributes(CoreTransportRequest request) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("accountReference", request.attributes().get("accountReference"));
        attributes.put("valid", true);
        attributes.put("status", "ACTIVE");
        attributes.put("maskedNumber", "******");
        return attributes;
    }

    private static Map<String, Object> attributes(CoreTransportRequest request, boolean posted) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("posted", posted);
        attributes.put("amount", request.attributes().get("amount"));
        attributes.put("currencyCode", request.attributes().get("currencyCode"));
        return attributes;
    }

    private Posting findPosting(CoreTransportRequest request) {
        Posting posting = ledger.get(request.transactionReference());
        if (posting != null) {
            return posting;
        }
        Object providerReference = request.attributes().get("providerReference");
        if (providerReference == null) {
            return null;
        }
        BigDecimal seeded = registry.externalPosting(providerReference.toString());
        if (seeded == null) {
            return null;
        }
        return new Posting(providerReference.toString(), seeded, "NGN", true, false);
    }

    private static boolean submits(CoreOperation operation) {
        return operation == CoreOperation.SUBMIT_DISBURSEMENT
                || operation == CoreOperation.SUBMIT_REPAYMENT
                || operation == CoreOperation.REVERSAL
                || operation == CoreOperation.CREATE_LOAN_ACCOUNT
                || operation == CoreOperation.CREATE_CUSTOMER;
    }

    private static CoreTransportException timeout() {
        return new CoreTransportException(CoreTransportException.Failure.TIMEOUT, "Mock core banking timed out");
    }

    private record Posting(String providerReference, BigDecimal amount, String currencyCode, boolean posted, boolean originalWasReversed) {
        private Posting asReversed() {
            return new Posting(providerReference, amount, currencyCode, posted, true);
        }
    }
}
