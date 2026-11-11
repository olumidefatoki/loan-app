package com.mfb.lending.module.integration.corebanking.dto;

public record CoreBankingHealthResponse(
        String provider,
        String status,
        String circuit,
        boolean remoteCallsEnabled,
        int pending,
        int unknown,
        int reconciliationRequired,
        int deadLetters
) {
}
