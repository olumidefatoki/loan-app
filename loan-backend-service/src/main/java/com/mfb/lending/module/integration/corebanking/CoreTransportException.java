package com.mfb.lending.module.integration.corebanking;

public final class CoreTransportException extends RuntimeException {

    public enum Failure {
        NOT_CONFIGURED,
        CIRCUIT_OPEN,
        TIMEOUT,
        UNAVAILABLE,
        AUTHENTICATION,
        REJECTED,
        UNPARSEABLE;

        public boolean uncertain() {
            return this == TIMEOUT || this == UNAVAILABLE || this == UNPARSEABLE;
        }

        public boolean opensCircuit() {
            return this == TIMEOUT || this == UNAVAILABLE || this == AUTHENTICATION;
        }

        public boolean sent() {
            return this != NOT_CONFIGURED && this != CIRCUIT_OPEN;
        }
    }

    private final Failure failure;

    public CoreTransportException(Failure failure, String message) {
        super(message);
        this.failure = failure;
    }

    public Failure failure() {
        return failure;
    }
}
