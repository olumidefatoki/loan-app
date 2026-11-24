package com.mfb.lending.module.collections.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.module.collections.domain.CollectionModels.MessageTemplate;

public final class CollectionMessages {

    public static final Set<String> ALLOWED_FIELDS = Set.of(
            "loanReference", "amount", "dueDate", "daysPastDue", "overdueAmount", "promiseDate", "promiseAmount", "decision");

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z]+)}}");

    private CollectionMessages() {
    }

    public static List<MessageTemplate> approvedTemplates() {
        return List.of(
                template("UPCOMING_INSTALMENT", "Instalment {{amount}} on loan {{loanReference}} is due on {{dueDate}}."),
                template("DUE_DATE_REMINDER", "Loan {{loanReference}} has {{amount}} due on {{dueDate}}."),
                template("MISSED_REPAYMENT", "Loan {{loanReference}} missed {{amount}} due on {{dueDate}}. {{daysPastDue}} days past due."),
                template("OVERDUE_BALANCE", "Loan {{loanReference}} has an overdue balance of {{overdueAmount}} and is {{daysPastDue}} days past due."),
                template("PAYMENT_CONFIRMATION", "Payment of {{amount}} on loan {{loanReference}} was confirmed on {{dueDate}}."),
                template("PAYMENT_FOLLOW_UP", "Promise of {{promiseAmount}} on loan {{loanReference}} is due on {{promiseDate}}."),
                template("RESTRUCTURING_DECISION", "Restructuring for loan {{loanReference}} was {{decision}}."));
    }

    public static String payload(Map<String, String> values) {
        Map<String, String> safe = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!ALLOWED_FIELDS.contains(entry.getKey())) {
                throw new ConflictException("Collection notifications cannot include " + entry.getKey());
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw new ConflictException("Collection notification field " + entry.getKey() + " is empty");
            }
            safe.put(entry.getKey(), entry.getValue());
        }
        StringBuilder json = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : safe.entrySet()) {
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append('"').append(entry.getKey()).append("\":\"").append(escape(entry.getValue())).append('"');
        }
        return json.append('}').toString();
    }

    public static Map<String, String> read(String payload) {
        Map<String, String> values = new LinkedHashMap<>();
        if (payload == null || payload.isBlank() || "{}".equals(payload)) {
            return values;
        }
        String body = payload.trim();
        if (body.startsWith("{")) {
            body = body.substring(1);
        }
        if (body.endsWith("}")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.isBlank()) {
            return values;
        }
        for (String pair : body.split(",")) {
            int split = pair.indexOf(':');
            if (split < 0) {
                continue;
            }
            String key = pair.substring(0, split).trim().replace("\"", "");
            String value = pair.substring(split + 1).trim();
            if (value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, unescape(value));
        }
        return values;
    }

    public static String render(String template, Map<String, String> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder rendered = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!ALLOWED_FIELDS.contains(key)) {
                throw new ConflictException("Collection template uses an unapproved placeholder");
            }
            String value = values.get(key);
            if (value == null) {
                throw new ConflictException("Collection template is missing " + key);
            }
            matcher.appendReplacement(rendered, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(rendered);
        return rendered.toString();
    }

    private static MessageTemplate template(String code, String body) {
        return new MessageTemplate(code, body, true);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
