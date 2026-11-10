package com.mfb.lending.module.customer.kyc;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.mfb.lending.module.customer.application.IdentityNumbers;
import com.mfb.lending.module.customer.domain.CheckOutcome;
import com.mfb.lending.module.customer.domain.RiskClassification;
import com.mfb.lending.module.customer.domain.ScreeningOutcome;

/**
 * Deterministic stand-in decisions for development and test.
 * A number made only of zeros times out. A number made only of nines fails verification.
 * A name containing SANCTIONED, the whole word PEP, or HIGHRISK drives screening.
 */
public final class MockKycDecisions {

    public static final String PROVIDER = "mock";
    private static final Pattern PEP = Pattern.compile("\\bPEP\\b");
    private static final BigDecimal HIGH_TURNOVER = new BigDecimal("100000000");

    private MockKycDecisions() {
    }

    public static VerificationResult identity(String number, String label) {
        if (IdentityNumbers.repeatedDigit(number, '0')) {
            return new VerificationResult(CheckOutcome.TIMED_OUT, PROVIDER, null, label + " verification timed out");
        }
        if (IdentityNumbers.repeatedDigit(number, '9')) {
            return new VerificationResult(CheckOutcome.FAILED, PROVIDER, null, label + " could not be verified");
        }
        return new VerificationResult(CheckOutcome.PASSED, PROVIDER, "mock-" + label.toLowerCase(Locale.ROOT), label + " matched");
    }

    public static VerificationResult registration(String registrationNumber, String label) {
        String digits = registrationNumber.replaceAll("\\D", "");
        if (IdentityNumbers.repeatedDigit(digits, '0')) {
            return new VerificationResult(CheckOutcome.TIMED_OUT, PROVIDER, null, label + " verification timed out");
        }
        if (IdentityNumbers.repeatedDigit(digits, '9')) {
            return new VerificationResult(CheckOutcome.FAILED, PROVIDER, null, label + " could not be verified");
        }
        return new VerificationResult(CheckOutcome.PASSED, PROVIDER, "mock-" + label.toLowerCase(Locale.ROOT), label + " matched");
    }

    public static ScreeningDecision sanctions(List<String> names) {
        if (contains(names, "SANCTIONED")) {
            return new ScreeningDecision(ScreeningOutcome.MATCH, PROVIDER, "Potential sanctions match");
        }
        return new ScreeningDecision(ScreeningOutcome.CLEAR, PROVIDER, "No sanctions match");
    }

    public static ScreeningDecision pep(List<String> names) {
        for (String name : names) {
            if (name != null && PEP.matcher(name.toUpperCase(Locale.ROOT)).find()) {
                return new ScreeningDecision(ScreeningOutcome.MATCH, PROVIDER, "Potential politically exposed person");
            }
        }
        return new ScreeningDecision(ScreeningOutcome.CLEAR, PROVIDER, "No politically exposed person match");
    }

    public static AmlDecision aml(List<String> names, BigDecimal incomeOrTurnover, BigDecimal expenses) {
        if (contains(names, "HIGHRISK")) {
            return new AmlDecision(RiskClassification.UNACCEPTABLE, 95, List.of("name-marker"), "Elevated AML risk");
        }
        List<String> factors = new ArrayList<>();
        if (incomeOrTurnover != null && expenses != null && expenses.compareTo(incomeOrTurnover) > 0) {
            factors.add("expenses-exceed-income");
            return new AmlDecision(RiskClassification.MEDIUM, 45, factors, "Expenses exceed income");
        }
        if (incomeOrTurnover != null && incomeOrTurnover.compareTo(HIGH_TURNOVER) >= 0) {
            factors.add("turnover-threshold");
            return new AmlDecision(RiskClassification.HIGH, 70, factors, "Turnover requires enhanced review");
        }
        return new AmlDecision(RiskClassification.LOW, 15, factors, "No elevated AML factors");
    }

    private static boolean contains(List<String> names, String marker) {
        for (String name : names) {
            if (name != null && name.toUpperCase(Locale.ROOT).contains(marker)) {
                return true;
            }
        }
        return false;
    }
}
