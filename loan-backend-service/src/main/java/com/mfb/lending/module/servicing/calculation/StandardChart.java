package com.mfb.lending.module.servicing.calculation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.mfb.lending.module.servicing.calculation.AccountingEngine.PostingRule;

public final class StandardChart {

    private StandardChart() {
    }

    public static List<Account> accounts() {
        return List.of(
                new Account("1200", "Loans receivable - principal", "ASSET", "DEBIT"),
                new Account("1300", "Interest receivable", "ASSET", "DEBIT"),
                new Account("1400", "Fee receivable", "ASSET", "DEBIT"),
                new Account("1500", "Penalty receivable", "ASSET", "DEBIT"),
                new Account("2100", "Core banking settlement", "LIABILITY", "CREDIT"),
                new Account("2200", "Customer overpayment", "LIABILITY", "CREDIT"),
                new Account("2300", "Tax payable", "LIABILITY", "CREDIT"),
                new Account("4100", "Fee income", "INCOME", "CREDIT"),
                new Account("4200", "Interest income", "INCOME", "CREDIT"),
                new Account("4300", "Penalty income", "INCOME", "CREDIT"));
    }

    public static List<PostingRule> rules() {
        return List.of(
                rule("DISBURSEMENT", "PRINCIPAL", "DEBIT", "1200", false),
                rule("DISBURSEMENT", "FEE", "CREDIT", "4100", false),
                rule("DISBURSEMENT", "TAX", "CREDIT", "2300", false),
                rule("DISBURSEMENT", "NET_CASH", "CREDIT", "2100", false),
                rule("INTEREST_ACCRUAL", "INTEREST", "DEBIT", "1300", false),
                rule("INTEREST_ACCRUAL", "INTEREST", "CREDIT", "4200", false),
                rule("INTEREST_REVERSAL", "INTEREST", "DEBIT", "4200", true),
                rule("INTEREST_REVERSAL", "INTEREST", "CREDIT", "1300", true),
                rule("PENALTY_ACCRUAL", "PENALTY", "DEBIT", "1500", false),
                rule("PENALTY_ACCRUAL", "PENALTY", "CREDIT", "4300", false),
                rule("FEE_CHARGE", "FEE", "DEBIT", "1400", false),
                rule("FEE_CHARGE", "FEE", "CREDIT", "4100", false),
                rule("REPAYMENT", "CASH", "DEBIT", "2100", false),
                rule("REPAYMENT", "PENALTY", "CREDIT", "1500", false),
                rule("REPAYMENT", "FEE", "CREDIT", "1400", false),
                rule("REPAYMENT", "INTEREST", "CREDIT", "1300", false),
                rule("REPAYMENT", "PRINCIPAL", "CREDIT", "1200", false),
                rule("REPAYMENT", "OVERPAYMENT", "CREDIT", "2200", false),
                rule("CAPITALISE_INTEREST", "PRINCIPAL", "DEBIT", "1200", false),
                rule("CAPITALISE_INTEREST", "INTEREST", "CREDIT", "1300", false));
    }

    public static AllocationPolicy policy() {
        return new AllocationPolicy(1, LocalDate.of(2020, 1, 1), List.of(
                AllocationComponent.PENALTY,
                AllocationComponent.FEE,
                AllocationComponent.INTEREST,
                AllocationComponent.PRINCIPAL));
    }

    private static PostingRule rule(String event, String component, String direction, String account, boolean approval) {
        return new PostingRule(event, component, direction, account, approval, 1);
    }

    public record Account(String code, String name, String accountType, String normalBalance) {
    }

    public record AllocationPolicy(int versionNumber, LocalDate effectiveFrom, List<AllocationComponent> priority) {
        public String encoded() {
            return String.join(",", priority.stream().map(Enum::name).toList());
        }

        public static AllocationPolicy parse(int version, LocalDate effectiveFrom, String encoded) {
            List<AllocationComponent> priority = java.util.Arrays.stream(encoded.split(","))
                    .map(String::trim)
                    .filter(part -> !part.isEmpty())
                    .map(AllocationComponent::valueOf)
                    .toList();
            return new AllocationPolicy(version, effectiveFrom, priority);
        }
    }

    public static BigDecimal zero() {
        return BigDecimal.ZERO;
    }
}
