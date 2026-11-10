package com.mfb.lending.common.correlation;

import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.MDC;

public final class CorrelationIds {

    public static final String HEADER = "X-Correlation-Id";
    public static final String MDC_KEY = "correlationId";
    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9\\-_.]{1,64}");

    private CorrelationIds() {
    }

    public static String current() {
        return MDC.get(MDC_KEY);
    }

    public static boolean isValid(String value) {
        return value != null && SAFE.matcher(value).matches();
    }

    public static String resolve(String incoming) {
        if (isValid(incoming)) {
            return incoming;
        }
        return UUID.randomUUID().toString();
    }
}
