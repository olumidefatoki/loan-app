package com.mfb.lending.common.security;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class SensitivePayloads {

    private SensitivePayloads() {
    }

    public static boolean sensitiveKey(String key) {
        if (key == null || key.isBlank()) {
            return true;
        }
        String normalized = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "");
        return normalized.contains("password")
                || normalized.contains("secret")
                || normalized.contains("token")
                || normalized.contains("bvn")
                || normalized.contains("nin")
                || normalized.contains("otp")
                || normalized.contains("authorization")
                || normalized.contains("credential")
                || normalized.contains("apikey")
                || normalized.contains("mfasecret")
                || "pin".equals(normalized);
    }

    public static Map<String, Object> scrub(Map<String, ?> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (key != null && !sensitiveKey(key)) {
                copy.put(key, scrubValue(value));
            }
        });
        return copy;
    }

    public static Map<String, String> strings(Map<String, ?> source) {
        Map<String, String> copy = new LinkedHashMap<>();
        if (source == null) {
            return copy;
        }
        source.forEach((key, value) -> {
            if (key != null && value != null && !sensitiveKey(key)) {
                copy.put(key, String.valueOf(value));
            }
        });
        return copy;
    }

    public static String narrative(String value) {
        if (value == null || value.isBlank()) {
            return "Review required";
        }
        String cleaned = value.replaceAll("\\d{6,}", "[redacted]").trim();
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500);
    }

    public static String safeError(String error) {
        if (error == null || error.isBlank()) {
            return "Delivery failed";
        }
        String cleaned = error
                .replaceAll("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+", "[redacted]")
                .replaceAll("\\+?[0-9]{8,}", "[redacted]")
                .trim();
        return cleaned.length() <= 500 ? cleaned : cleaned.substring(0, 500);
    }

    private static Object scrubValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> nested = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (key != null && !sensitiveKey(String.valueOf(key))) {
                    nested.put(String.valueOf(key), scrubValue(item));
                }
            });
            return nested;
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> items = new ArrayList<>();
            for (Object item : iterable) {
                items.add(scrubValue(item));
            }
            return items;
        }
        return value;
    }
}
