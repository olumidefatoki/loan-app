package com.mfb.lending.module.integration.corebanking;

import java.util.UUID;
import java.util.regex.Pattern;

import com.mfb.lending.common.correlation.CorrelationIds;
import com.mfb.lending.common.exception.ValidationException;

final class CoreIds {

    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9._:-]{8,64}");

    private CoreIds() {
    }

    static String idempotency(String value) {
        if (value == null || !KEY.matcher(value).matches()) {
            throw ValidationException.field("idempotencyKey", "idempotencyKey must be 8 to 64 letters, numbers, or . _ : -");
        }
        return value;
    }

    static String correlation(String value) {
        if (CorrelationIds.isValid(value)) {
            return value;
        }
        String current = CorrelationIds.current();
        return CorrelationIds.isValid(current) ? current : UUID.randomUUID().toString();
    }
}
