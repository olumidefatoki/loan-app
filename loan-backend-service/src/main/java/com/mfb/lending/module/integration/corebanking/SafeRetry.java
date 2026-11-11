package com.mfb.lending.module.integration.corebanking;

import java.time.Duration;

public final class SafeRetry {

    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int multiplier;
    private final Sleeper sleeper;

    public SafeRetry(int maxAttempts, Duration initialBackoff, Duration maxBackoff, int multiplier, Sleeper sleeper) {
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.multiplier = multiplier;
        this.sleeper = sleeper;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    public void pause(int completedAttempts) {
        long delay = initialBackoff.toMillis();
        for (int i = 1; i < completedAttempts; i++) {
            delay = Math.min(maxBackoff.toMillis(), delay * multiplier);
        }
        sleeper.sleep(Duration.ofMillis(Math.min(maxBackoff.toMillis(), delay)));
    }

    public interface Sleeper {
        void sleep(Duration duration);
    }
}
