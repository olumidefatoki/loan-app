package com.mfb.lending.module.servicing.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds balanced double-entry lines from versioned posting rules.
 * A negative component amount is rejected. Zero components are omitted.
 * Lines must balance independently for every currency.
 */
public final class AccountingEngine {

    private AccountingEngine() {
    }

    public static Draft draft(
            String eventCode,
            String currencyCode,
            Map<String, BigDecimal> amounts,
            List<PostingRule> rules,
            int scale,
            RoundingMode rounding) {
        List<PostingRule> selected = rules.stream().filter(rule -> rule.eventCode().equals(eventCode)).toList();
        if (selected.isEmpty()) {
            throw new IllegalStateException("No posting rule is configured for " + eventCode);
        }
        List<DraftLine> lines = new ArrayList<>();
        int number = 1;
        boolean approval = false;
        for (PostingRule rule : selected) {
            approval = approval || rule.requiresApproval();
            BigDecimal amount = amounts.getOrDefault(rule.component(), BigDecimal.ZERO);
            if (amount.signum() < 0) {
                throw new IllegalStateException("A journal amount cannot be negative");
            }
            if (amount.signum() == 0) {
                continue;
            }
            lines.add(new DraftLine(number++, rule.accountCode(), rule.direction(), amount.setScale(scale, rounding), currencyCode, rule.component()));
        }
        if (lines.isEmpty()) {
            throw new IllegalStateException("A journal needs at least one line");
        }
        assertBalanced(lines);
        return new Draft(eventCode, currencyCode, approval, List.copyOf(lines));
    }

    public static Draft reverse(String currencyCode, List<DraftLine> original) {
        List<DraftLine> lines = new ArrayList<>();
        int number = 1;
        for (DraftLine line : original) {
            String direction = "DEBIT".equals(line.direction()) ? "CREDIT" : "DEBIT";
            lines.add(new DraftLine(number++, line.accountCode(), direction, line.amount(), line.currencyCode(), line.component()));
        }
        assertBalanced(lines);
        return new Draft("REVERSAL", currencyCode, false, List.copyOf(lines));
    }

    public static void assertBalanced(List<DraftLine> lines) {
        Map<String, BigDecimal> net = new LinkedHashMap<>();
        for (DraftLine line : lines) {
            if (!"DEBIT".equals(line.direction()) && !"CREDIT".equals(line.direction())) {
                throw new IllegalStateException("A journal line direction must be DEBIT or CREDIT");
            }
            BigDecimal signed = "DEBIT".equals(line.direction()) ? line.amount() : line.amount().negate();
            net.merge(line.currencyCode(), signed, BigDecimal::add);
        }
        for (Map.Entry<String, BigDecimal> entry : net.entrySet()) {
            if (entry.getValue().compareTo(BigDecimal.ZERO) != 0) {
                throw new IllegalStateException("Journal does not balance in " + entry.getKey());
            }
        }
    }

    public record PostingRule(
            String eventCode,
            String component,
            String direction,
            String accountCode,
            boolean requiresApproval,
            int versionNumber
    ) {
    }

    public record DraftLine(
            int number,
            String accountCode,
            String direction,
            BigDecimal amount,
            String currencyCode,
            String component
    ) {
    }

    public record Draft(String eventCode, String currencyCode, boolean requiresApproval, List<DraftLine> lines) {
    }
}
