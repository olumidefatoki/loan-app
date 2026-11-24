package com.mfb.lending.module.notification.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.common.security.SensitivePayloads;
import com.mfb.lending.config.PlatformOperationsProperties;

public final class TemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z0-9_]+)\\s*}}");

    private TemplateRenderer() {
    }

    public static void assertBody(String body) {
        if (body == null || body.isBlank()) {
            throw ValidationException.field("body", "body is required");
        }
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            if (SensitivePayloads.sensitiveKey(matcher.group(1))) {
                throw ValidationException.field("body", "Templates cannot include secrets or identity numbers");
            }
        }
    }

    public static String render(String template, Map<String, String> values) {
        if (template == null || template.isBlank()) {
            return "";
        }
        Map<String, String> safe = values == null ? Map.of() : values;
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = SensitivePayloads.sensitiveKey(key) ? "" : safe.getOrDefault(key, "");
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        String text = rendered.toString().trim();
        return text.length() <= 4000 ? text : text.substring(0, 4000);
    }
}

final class RetryPolicy {

    private final int maxAttempts;
    private final Duration initial;
    private final Duration maximum;
    private final int multiplier;

    RetryPolicy(int maxAttempts, Duration initial, Duration maximum, int multiplier) {
        this.maxAttempts = Math.max(1, maxAttempts);
        this.initial = initial == null || initial.isNegative() || initial.isZero() ? Duration.ofMinutes(1) : initial;
        this.maximum = maximum == null || maximum.compareTo(this.initial) < 0 ? this.initial : maximum;
        this.multiplier = Math.max(1, multiplier);
    }

    static RetryPolicy from(PlatformOperationsProperties properties) {
        return new RetryPolicy(
                properties.getMaxAttempts(),
                properties.getInitialBackoff(),
                properties.getMaxBackoff(),
                properties.getBackoffMultiplier());
    }

    int maxAttempts() {
        return maxAttempts;
    }

    boolean exhausted(int attemptsMade) {
        return attemptsMade >= maxAttempts;
    }

    Instant next(int attemptsMade, Instant now) {
        long factor = 1L;
        for (int attempt = 1; attempt < attemptsMade; attempt++) {
            factor = Math.min(factor * multiplier, 1_000_000L);
        }
        Duration delay = initial.multipliedBy(factor);
        if (delay.compareTo(maximum) > 0 || delay.isNegative()) {
            delay = maximum;
        }
        return now.plus(delay);
    }
}
