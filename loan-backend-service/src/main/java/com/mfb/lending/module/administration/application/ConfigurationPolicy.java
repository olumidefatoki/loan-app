package com.mfb.lending.module.administration.application;

import java.util.Locale;
import java.util.Set;

import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.common.security.SensitivePayloads;

public final class ConfigurationPolicy {

    private static final Set<String> VALUE_TYPES = Set.of("STRING", "NUMBER", "BOOLEAN", "JSON");

    private ConfigurationPolicy() {
    }

    public static void assertKey(String key) {
        if (key == null || key.isBlank() || key.length() > 150 || key.startsWith(".") || key.endsWith(".")) {
            throw ValidationException.field("configKey", "Configuration key is invalid");
        }
        if (SensitivePayloads.sensitiveKey(key)) {
            throw ValidationException.field("configKey", "Passwords, tokens, and identity numbers cannot be stored in configuration");
        }
    }

    public static boolean requiresApproval(String key, boolean sensitive) {
        return sensitive || key.startsWith("integration.") || key.startsWith("notifications.provider.");
    }

    public static String valueType(String valueType) {
        if (valueType == null || valueType.isBlank()) {
            return "STRING";
        }
        String selected = valueType.trim().toUpperCase(Locale.ROOT);
        if (!VALUE_TYPES.contains(selected)) {
            throw ValidationException.field("valueType", "Unknown configuration value type");
        }
        return selected;
    }

    public static void assertApprover(java.util.UUID requester, java.util.UUID approver) {
        if (requester.equals(approver)) {
            throw new com.mfb.lending.common.exception.ForbiddenException("A different administrator must approve this change");
        }
    }
}
