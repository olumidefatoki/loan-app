package com.mfb.lending.module.integration.corebanking;

import java.math.BigDecimal;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

@Component
public class MockScenarioRegistry {

    public enum Script {
        TIMEOUT,
        LOST_RESPONSE,
        REJECTED
    }

    private final Map<String, Script> scripts = new ConcurrentHashMap<>();
    private final Map<CoreOperation, CoreOutcome> operations = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> mismatches = new ConcurrentHashMap<>();
    private final Map<String, BigDecimal> externalPostings = new ConcurrentHashMap<>();
    private final java.util.Set<String> reversed = ConcurrentHashMap.newKeySet();
    private volatile boolean unavailable;
    private volatile boolean authenticationFailure;

    public void timeout(String idempotencyKey) {
        scripts.put(idempotencyKey, Script.TIMEOUT);
    }

    public void lostResponse(String idempotencyKey) {
        scripts.put(idempotencyKey, Script.LOST_RESPONSE);
    }

    public void reject(String idempotencyKey) {
        scripts.put(idempotencyKey, Script.REJECTED);
    }

    public void unavailable(boolean down) {
        this.unavailable = down;
    }

    public void authenticationFailure(boolean failed) {
        this.authenticationFailure = failed;
    }

    public void failOperation(CoreOperation operation, CoreOutcome outcome) {
        operations.put(operation, outcome);
    }

    public void mismatch(String transactionReference, BigDecimal actualAmount) {
        mismatches.put(transactionReference, actualAmount);
    }

    public void seedPosting(String providerReference, BigDecimal amount) {
        externalPostings.put(providerReference, amount);
    }

    public void markReversed(String transactionReference) {
        reversed.add(transactionReference);
    }

    public void reset() {
        scripts.clear();
        operations.clear();
        mismatches.clear();
        externalPostings.clear();
        reversed.clear();
        unavailable = false;
        authenticationFailure = false;
    }

    Script script(String idempotencyKey) {
        return idempotencyKey == null ? null : scripts.get(idempotencyKey);
    }

    CoreOutcome forced(CoreOperation operation) {
        return operations.get(operation);
    }

    BigDecimal mismatch(String transactionReference) {
        return mismatches.get(transactionReference);
    }

    BigDecimal externalPosting(String providerReference) {
        return providerReference == null ? null : externalPostings.get(providerReference);
    }

    boolean reversed(String transactionReference) {
        return reversed.contains(transactionReference);
    }

    boolean unavailable() {
        return unavailable;
    }

    boolean authenticationFailure() {
        return authenticationFailure;
    }
}
