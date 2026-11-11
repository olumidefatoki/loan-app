package com.mfb.lending.module.integration.corebanking;

import com.mfb.lending.common.exception.ConflictException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinancialStateMachineTest {

    @Test
    void timeoutMovesAnInFlightInstructionToUnknown() {
        assertThat(FinancialStateMachine.next(FinancialTransactionState.PENDING, FinancialEvent.UNCERTAIN))
                .isEqualTo(FinancialTransactionState.UNKNOWN);
        assertThat(FinancialStateMachine.next(FinancialTransactionState.SUBMITTED, FinancialEvent.UNCERTAIN))
                .isEqualTo(FinancialTransactionState.UNKNOWN);
    }

    @Test
    void enquiryCanResolveUnknownWithoutRetryingTheInstruction() {
        assertThat(FinancialStateMachine.next(FinancialTransactionState.UNKNOWN, FinancialEvent.CONFIRM))
                .isEqualTo(FinancialTransactionState.SUCCESSFUL);
        assertThat(FinancialStateMachine.next(FinancialTransactionState.UNKNOWN, FinancialEvent.REJECT))
                .isEqualTo(FinancialTransactionState.FAILED);
        assertThat(FinancialStateMachine.next(FinancialTransactionState.UNKNOWN, FinancialEvent.CONTINUE_UNSENT))
                .isEqualTo(FinancialTransactionState.PENDING);
    }

    @Test
    void terminalStatesDoNotMove() {
        assertThatThrownBy(() -> FinancialStateMachine.next(FinancialTransactionState.FAILED, FinancialEvent.CONFIRM))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> FinancialStateMachine.next(FinancialTransactionState.REVERSED, FinancialEvent.CONFIRM))
                .isInstanceOf(ConflictException.class);
    }
}
