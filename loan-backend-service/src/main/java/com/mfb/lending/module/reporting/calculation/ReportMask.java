package com.mfb.lending.module.reporting.calculation;

/**
 * Staff reports show a short name and never an identity number, phone number, email address, or account number.
 */
public final class ReportMask {

    private ReportMask() {
    }

    public static String displayName(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String[] parts = name.trim().split("\\s+");
        if (parts.length == 1) {
            return initial(parts[0]) + "***";
        }
        return parts[0] + " " + initial(parts[parts.length - 1]) + ".";
    }

    public static String csv(String value, boolean numeric) {
        String safe = value == null ? "" : value;
        if (!numeric && !safe.isEmpty() && startsLikeFormula(safe)) {
            safe = "'" + safe;
        }
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }

    public static boolean plainNumber(String value) {
        return value != null && value.matches("-?\\d+(\\.\\d+)?");
    }

    private static boolean startsLikeFormula(String value) {
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t';
    }

    private static String initial(String word) {
        return word.isEmpty() ? "" : word.substring(0, 1).toUpperCase();
    }
}
