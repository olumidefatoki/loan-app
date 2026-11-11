package com.mfb.lending.module.loancalculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;

import com.mfb.lending.module.loancalculation.LoanCalculationEngine.CalculatedInstalment;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.FeeInput;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.LoanQuote;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.LoanQuoteRequest;
import com.mfb.lending.module.loanproduct.domain.DayCountConvention;
import com.mfb.lending.module.loanproduct.domain.FeeCalculation;
import com.mfb.lending.module.loanproduct.domain.InterestMethod;
import com.mfb.lending.module.loanproduct.domain.InterestPeriod;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LoanCalculationEngineTest {

    @Test
    void flatInterestSplitsTheRemainderOntoTheLastInstalment() {
        LoanQuote quote = LoanCalculationEngine.quote(request(
                "10000.00", "10", InterestMethod.FLAT, 12, TenureUnit.MONTHS, RepaymentFrequency.MONTHLY,
                DayCountConvention.ACTUAL_365, List.of()));

        assertThat(quote.totalInterest()).isEqualByComparingTo("1000.00");
        assertThat(quote.totalRepayment()).isEqualByComparingTo("11000.00");
        assertThat(quote.schedule().get(0).instalmentAmount()).isEqualByComparingTo("916.66");
        assertThat(quote.schedule().get(11).principalDue()).isEqualByComparingTo("833.37");
        assertThat(quote.schedule().get(11).interestDue()).isEqualByComparingTo("83.37");
        assertThat(sum(quote, CalculatedInstalment::principalDue)).isEqualByComparingTo("10000.00");
        assertThat(quote.schedule().get(11).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(quote.schedule().get(0).dueDate()).isEqualTo(LocalDate.of(2026, 2, 15));
    }

    @Test
    void reducingBalanceUsesTheStandardInstalmentAndClearsTheBalance() {
        LoanQuote quote = LoanCalculationEngine.quote(request(
                "10000.00", "12", InterestMethod.REDUCING_BALANCE, 12, TenureUnit.MONTHS, RepaymentFrequency.MONTHLY,
                DayCountConvention.ACTUAL_365, List.of()));

        assertThat(quote.instalmentAmount()).isEqualByComparingTo("888.49");
        assertThat(quote.schedule().get(0).interestDue()).isEqualByComparingTo("100.00");
        assertThat(quote.schedule().get(0).principalDue()).isEqualByComparingTo("788.49");
        assertThat(sum(quote, CalculatedInstalment::principalDue)).isEqualByComparingTo("10000.00");
        assertThat(quote.schedule().get(11).closingBalance()).isEqualByComparingTo("0.00");
        assertThat(quote.totalRepayment()).isEqualByComparingTo(sum(quote, CalculatedInstalment::instalmentAmount));
    }

    @Test
    void feesAndTaxAreSeparateFromTheInstalment() {
        LoanQuote quote = LoanCalculationEngine.quote(request(
                "10000.00", "0", InterestMethod.REDUCING_BALANCE, 12, TenureUnit.MONTHS, RepaymentFrequency.MONTHLY,
                DayCountConvention.ACTUAL_365,
                List.of(new FeeInput("PROCESSING", "Processing", FeeCalculation.PERCENTAGE, null, new BigDecimal("2")),
                        new FeeInput("MANAGEMENT", "Management", FeeCalculation.FLAT_AMOUNT, new BigDecimal("100.00"), null))));

        assertThat(quote.processingFees()).isEqualByComparingTo("200.00");
        assertThat(quote.otherFees()).isEqualByComparingTo("100.00");
        assertThat(quote.taxes()).isEqualByComparingTo("22.50");
        assertThat(quote.netDisbursement()).isEqualByComparingTo("9677.50");
        assertThat(quote.totalInterest()).isEqualByComparingTo("0.00");
        assertThat(quote.fingerprint()).hasSize(64);
        assertThat(LoanCalculationEngine.quote(request(
                "10000.00", "0", InterestMethod.REDUCING_BALANCE, 12, TenureUnit.MONTHS, RepaymentFrequency.MONTHLY,
                DayCountConvention.ACTUAL_365,
                List.of(new FeeInput("MANAGEMENT", "Management", FeeCalculation.FLAT_AMOUNT, new BigDecimal("100.00"), null),
                        new FeeInput("PROCESSING", "Processing", FeeCalculation.PERCENTAGE, null, new BigDecimal("2"))))).fingerprint())
                .isEqualTo(quote.fingerprint());
    }

    @Test
    void dailyFlatInterestFollowsTheDayCount() {
        LoanQuote actual = LoanCalculationEngine.quote(request(
                "1000.00", "10", InterestMethod.FLAT, 365, TenureUnit.DAYS, RepaymentFrequency.DAILY,
                DayCountConvention.ACTUAL_365, List.of()));
        LoanQuote thirty = LoanCalculationEngine.quote(request(
                "1000.00", "10", InterestMethod.FLAT, 365, TenureUnit.DAYS, RepaymentFrequency.DAILY,
                DayCountConvention.THIRTY_360, List.of()));

        assertThat(actual.totalInterest()).isEqualByComparingTo("100.00");
        assertThat(thirty.totalInterest()).isEqualByComparingTo("101.39");
        assertThat(actual.fingerprint()).isNotEqualTo(thirty.fingerprint());
    }

    private static BigDecimal sum(LoanQuote quote, java.util.function.Function<CalculatedInstalment, BigDecimal> field) {
        return quote.schedule().stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static LoanQuoteRequest request(
            String principal,
            String rate,
            InterestMethod method,
            int tenure,
            TenureUnit unit,
            RepaymentFrequency frequency,
            DayCountConvention dayCount,
            List<FeeInput> fees) {
        return new LoanQuoteRequest(
                new BigDecimal(principal),
                new BigDecimal(rate),
                method,
                InterestPeriod.ANNUAL,
                tenure,
                unit,
                frequency,
                dayCount,
                2,
                RoundingMode.HALF_UP,
                0,
                LocalDate.of(2026, 1, 15),
                new BigDecimal("7.5"),
                fees);
    }
}
