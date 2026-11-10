package com.mfb.lending.module.customer.domain;

import com.mfb.lending.common.exception.ValidationException;

public enum ClientChannel {
    WEB,
    MOBILE;

    public static ClientChannel parse(String value) {
        if (value == null || value.isBlank()) {
            return WEB;
        }
        try {
            return ClientChannel.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("X-Client-Channel", "X-Client-Channel must be WEB or MOBILE");
        }
    }
}
