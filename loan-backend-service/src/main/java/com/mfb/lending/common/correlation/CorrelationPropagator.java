package com.mfb.lending.common.correlation;

import org.springframework.http.HttpHeaders;

public final class CorrelationPropagator {

    private CorrelationPropagator() {
    }

    public static void apply(HttpHeaders headers) {
        String correlationId = CorrelationIds.current();
        if (correlationId != null && !headers.containsKey(CorrelationIds.HEADER)) {
            headers.set(CorrelationIds.HEADER, correlationId);
        }
    }
}
