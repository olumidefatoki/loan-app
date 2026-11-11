package com.mfb.lending.module.integration.corebanking;

import java.time.Duration;
import java.time.Instant;

public final class IntegrationCircuitBreaker {

    private enum Position {
        CLOSED,
        OPEN,
        HALF_OPEN
    }

    private final int failureThreshold;
    private final Duration openDuration;
    private int failures;
    private Position position = Position.CLOSED;
    private Instant openedUntil = Instant.EPOCH;

    public IntegrationCircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    public synchronized boolean permitting(Instant now) {
        if (position == Position.OPEN && now.isBefore(openedUntil)) {
            return false;
        }
        return position != Position.HALF_OPEN;
    }

    public synchronized boolean allow(Instant now) {
        if (position == Position.OPEN) {
            if (now.isBefore(openedUntil)) {
                return false;
            }
            position = Position.HALF_OPEN;
            return true;
        }
        if (position == Position.HALF_OPEN) {
            return false;
        }
        return true;
    }

    public synchronized void success() {
        failures = 0;
        position = Position.CLOSED;
    }

    public synchronized void failure(Instant now) {
        failures++;
        if (position == Position.HALF_OPEN || failures >= failureThreshold) {
            position = Position.OPEN;
            openedUntil = now.plus(openDuration);
            failures = 0;
        }
    }

    public synchronized String position() {
        return position.name();
    }
}
