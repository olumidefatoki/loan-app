package com.mfb.lending.module.credit.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.Map;

import com.mfb.lending.module.credit.domain.CalculationLine;

public final class CreditCalculations {

    public static final int RATIO_SCALE = 6;
    public static final int MONEY_SCALE = 2;
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public static final String DTI = "DTI_V1";
    public static final String DSCR = "DSCR_V1";
    public static final String COLLATERAL = "COLLATERAL_COVERAGE_V1";
    public static final String DISPOSABLE = "DISPOSABLE_INCOME_V1";

    private CreditCalculations() {
    }

    public static CalculationLine dti(BigDecimal monthlyDebtObligations, BigDecimal monthlyGrossIncome) {
        Map<String, BigDecimal> inputs = moneyInputs(
                "monthlyDebtObligations", monthlyDebtObligations,
                "monthlyGrossIncome", monthlyGrossIncome);
        return divide(DTI, "monthlyDebtObligations / monthlyGrossIncome", inputs, monthlyDebtObligations, monthlyGrossIncome);
    }

    public static CalculationLine dscr(BigDecimal availableOperatingCashFlow, BigDecimal totalDebtService) {
        Map<String, BigDecimal> inputs = moneyInputs(
                "availableOperatingCashFlow", availableOperatingCashFlow,
                "totalDebtService", totalDebtService);
        return divide(DSCR, "availableOperatingCashFlow / totalDebtService", inputs, availableOperatingCashFlow, totalDebtService);
    }

    public static CalculationLine collateralCoverage(BigDecimal eligibleCollateralValue, BigDecimal totalSecuredExposure) {
        Map<String, BigDecimal> inputs = moneyInputs(
                "eligibleCollateralValue", eligibleCollateralValue,
                "totalSecuredExposure", totalSecuredExposure);
        return divide(
                COLLATERAL,
                "eligibleCollateralValue / totalSecuredExposure",
                inputs,
                eligibleCollateralValue,
                totalSecuredExposure);
    }

    public static CalculationLine disposableIncome(
            BigDecimal netIncome,
            BigDecimal monthlyExpenses,
            BigDecimal monthlyDebtObligations) {
        Map<String, BigDecimal> inputs = moneyInputs(
                "netIncome", netIncome,
                "monthlyExpenses", monthlyExpenses,
                "monthlyDebtObligations", monthlyDebtObligations);
        if (netIncome == null || monthlyExpenses == null || monthlyDebtObligations == null) {
            return unavailable(DISPOSABLE, "netIncome - monthlyExpenses - monthlyDebtObligations", inputs, "INPUT_MISSING");
        }
        if (netIncome.signum() < 0 || monthlyExpenses.signum() < 0 || monthlyDebtObligations.signum() < 0) {
            return unavailable(DISPOSABLE, "netIncome - monthlyExpenses - monthlyDebtObligations", inputs, "NEGATIVE_INPUT");
        }
        BigDecimal output = money(netIncome).subtract(money(monthlyExpenses)).subtract(money(monthlyDebtObligations));
        return new CalculationLine(
                DISPOSABLE,
                "netIncome - monthlyExpenses - monthlyDebtObligations",
                inputs,
                output,
                null);
    }

    public static BigDecimal monthlyDebt(BigDecimal existingLoanObligations, BigDecimal proposedMonthlyInstalment) {
        if (existingLoanObligations == null || proposedMonthlyInstalment == null) {
            return null;
        }
        return money(existingLoanObligations).add(money(proposedMonthlyInstalment));
    }

    public static BigDecimal debtService(BigDecimal existingDebtService, BigDecimal proposedMonthlyInstalment) {
        if (existingDebtService == null || proposedMonthlyInstalment == null) {
            return null;
        }
        return money(existingDebtService).add(money(proposedMonthlyInstalment));
    }

    private static CalculationLine divide(
            String formula,
            String expression,
            Map<String, BigDecimal> inputs,
            BigDecimal numerator,
            BigDecimal divisor) {
        if (numerator == null || divisor == null) {
            return unavailable(formula, expression, inputs, "INPUT_MISSING");
        }
        if (numerator.signum() < 0 || divisor.signum() < 0) {
            return unavailable(formula, expression, inputs, "NEGATIVE_INPUT");
        }
        if (divisor.signum() == 0) {
            return unavailable(formula, expression, inputs, "DIVISOR_NOT_POSITIVE");
        }
        BigDecimal output = money(numerator).divide(money(divisor), RATIO_SCALE, ROUNDING);
        return new CalculationLine(formula, expression, inputs, output, null);
    }

    private static CalculationLine unavailable(
            String formula,
            String expression,
            Map<String, BigDecimal> inputs,
            String reason) {
        return new CalculationLine(formula, expression, inputs, null, reason);
    }

    private static Map<String, BigDecimal> moneyInputs(String firstKey, BigDecimal first, String secondKey, BigDecimal second) {
        Map<String, BigDecimal> inputs = new LinkedHashMap<>();
        putMoney(inputs, firstKey, first);
        putMoney(inputs, secondKey, second);
        return Map.copyOf(inputs);
    }

    private static Map<String, BigDecimal> moneyInputs(
            String firstKey,
            BigDecimal first,
            String secondKey,
            BigDecimal second,
            String thirdKey,
            BigDecimal third) {
        Map<String, BigDecimal> inputs = new LinkedHashMap<>();
        putMoney(inputs, firstKey, first);
        putMoney(inputs, secondKey, second);
        putMoney(inputs, thirdKey, third);
        return Map.copyOf(inputs);
    }

    private static void putMoney(Map<String, BigDecimal> inputs, String key, BigDecimal value) {
        if (value != null) {
            inputs.put(key, money(value));
        }
    }

    public static BigDecimal money(BigDecimal value) {
        return value.setScale(MONEY_SCALE, ROUNDING);
    }

    public static BigDecimal ratio(BigDecimal numerator, BigDecimal divisor) {
        if (numerator == null || divisor == null || divisor.signum() <= 0) {
            return null;
        }
        return numerator.divide(divisor, RATIO_SCALE, ROUNDING);
    }
}
