package com.mfb.lending.module.integration.corebanking;

import com.mfb.lending.common.exception.ConflictException;

public final class FinancialStateMachine {

    private FinancialStateMachine() {
    }

    public static FinancialTransactionState next(FinancialTransactionState from, FinancialEvent event) {
        FinancialTransactionState to = switch (from) {
            case INITIATED -> event == FinancialEvent.ACCEPT ? FinancialTransactionState.PENDING : null;
            case PENDING -> switch (event) {
                case ACCEPT_SUBMISSION -> FinancialTransactionState.SUBMITTED;
                case UNCERTAIN -> FinancialTransactionState.UNKNOWN;
                case REJECT -> FinancialTransactionState.FAILED;
                default -> null;
            };
            case SUBMITTED -> switch (event) {
                case CONFIRM -> FinancialTransactionState.SUCCESSFUL;
                case REJECT -> FinancialTransactionState.FAILED;
                case UNCERTAIN -> FinancialTransactionState.UNKNOWN;
                case MISMATCH -> FinancialTransactionState.RECONCILIATION_REQUIRED;
                default -> null;
            };
            case UNKNOWN -> switch (event) {
                case CONFIRM -> FinancialTransactionState.SUCCESSFUL;
                case REJECT -> FinancialTransactionState.FAILED;
                case MISMATCH -> FinancialTransactionState.RECONCILIATION_REQUIRED;
                case CONTINUE_UNSENT -> FinancialTransactionState.PENDING;
                default -> null;
            };
            case SUCCESSFUL -> switch (event) {
                case REVERSE -> FinancialTransactionState.REVERSED;
                case MISMATCH -> FinancialTransactionState.RECONCILIATION_REQUIRED;
                default -> null;
            };
            case RECONCILIATION_REQUIRED -> switch (event) {
                case CONFIRM -> FinancialTransactionState.SUCCESSFUL;
                case REJECT -> FinancialTransactionState.FAILED;
                case REVERSE -> FinancialTransactionState.REVERSED;
                case UNCERTAIN -> FinancialTransactionState.UNKNOWN;
                default -> null;
            };
            case FAILED, REVERSED -> null;
        };
        if (to == null) {
            throw new ConflictException("Cannot move a financial transaction from " + from + " on " + event);
        }
        return to;
    }
}
