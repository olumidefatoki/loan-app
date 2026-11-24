package com.mfb.lending.module.collections.calculation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.module.collections.domain.CollectionModels.BucketBand;
import com.mfb.lending.module.collections.domain.CollectionModels.DelinquencyAssessment;
import com.mfb.lending.module.collections.domain.CollectionModels.DelinquencySnapshot;
import com.mfb.lending.module.collections.domain.CollectionModels.InstalmentFact;
import com.mfb.lending.module.collections.domain.CollectionModels.ParReport;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelinquencyCalculatorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 3, 15);

    @Test
    void classifiesTheConfiguredBucketsAndTreatsTheDueDateAsNotPastDue() {
        assertThat(DelinquencyCalculator.classify(0, BucketBand.defaults())).isEqualTo("CURRENT");
        assertThat(DelinquencyCalculator.classify(1, BucketBand.defaults())).isEqualTo("DPD_1_30");
        assertThat(DelinquencyCalculator.classify(30, BucketBand.defaults())).isEqualTo("DPD_1_30");
        assertThat(DelinquencyCalculator.classify(31, BucketBand.defaults())).isEqualTo("DPD_31_60");
        assertThat(DelinquencyCalculator.classify(60, BucketBand.defaults())).isEqualTo("DPD_31_60");
        assertThat(DelinquencyCalculator.classify(61, BucketBand.defaults())).isEqualTo("DPD_61_90");
        assertThat(DelinquencyCalculator.classify(90, BucketBand.defaults())).isEqualTo("DPD_61_90");
        assertThat(DelinquencyCalculator.classify(91, BucketBand.defaults())).isEqualTo("DPD_ABOVE_90");

        DelinquencyAssessment dueToday = assess(List.of(fact(AS_OF, "100.00", "10.00", "0", "0")), "100.00");
        assertThat(dueToday.daysPastDue()).isZero();
        assertThat(dueToday.overdueAmount()).isEqualByComparingTo("0");
        assertThat(dueToday.approachingDue()).isTrue();
        assertThat(dueToday.bucket()).isEqualTo("CURRENT");
    }

    @Test
    void partialConfirmedPaymentsReduceTheOverdueAmountAndLeaveTheMissedInstalment() {
        InstalmentFact partial = fact(AS_OF.minusDays(5), "100.00", "10.00", "40.00", "0.00");
        DelinquencyAssessment assessment = assess(List.of(partial, fact(AS_OF.plusDays(20), "100.00", "10.00", "0", "0")), "250.00");

        assertThat(assessment.daysPastDue()).isEqualTo(5);
        assertThat(assessment.bucket()).isEqualTo("DPD_1_30");
        assertThat(assessment.overdueAmount()).isEqualByComparingTo("70.00");
        assertThat(assessment.overdueInterest()).isEqualByComparingTo("10.00");
        assertThat(assessment.missedInstalments()).isEqualTo(1);
        assertThat(assessment.partialPayment()).isTrue();
        assertThat(assessment.outstandingPrincipal()).isEqualByComparingTo("250.00");
        assertThat(assessment.collectionExposure()).isEqualByComparingTo("75.00");
        assertThat(assessment.delinquent()).isTrue();
        assertThat(assessment.escalationRequired()).isFalse();
    }

    @Test
    void portfolioAtRiskUsesOutstandingPrincipalAndIncludesALoanInEveryThresholdItMeets() {
        List<DelinquencySnapshot> population = List.of(
                snapshot(30, "100.00", true),
                snapshot(29, "20.00", true),
                snapshot(10, "300.00", true),
                snapshot(90, "50.00", true),
                snapshot(120, "0.00", false));
        ParReport report = DelinquencyCalculator.portfolio(AS_OF, population);

        assertThat(report.denominatorRule()).contains("outstanding principal").contains("ACTIVE");
        assertThat(report.numeratorRule()).contains("PAR 30").contains("PAR 90");
        assertThat(report.denominatorOutstandingPrincipal()).isEqualByComparingTo("470.00");
        assertThat(report.accountsInDenominator()).isEqualTo(4);
        assertThat(report.par30().outstandingPrincipal()).isEqualByComparingTo("150.00");
        assertThat(report.par30().accounts()).isEqualTo(2);
        assertThat(report.par30().ratio()).isEqualByComparingTo("0.319149");
        assertThat(report.par60().outstandingPrincipal()).isEqualByComparingTo("50.00");
        assertThat(report.par60().ratio()).isEqualByComparingTo("0.106383");
        assertThat(report.par90().outstandingPrincipal()).isEqualByComparingTo("50.00");
        assertThat(report.par90().accounts()).isEqualTo(1);
        assertThat(report.confirmedAllocationRule()).contains("POSTED");
    }

    private static DelinquencyAssessment assess(List<InstalmentFact> facts, String principal) {
        return DelinquencyCalculator.assess(
                AS_OF, new BigDecimal(principal), new BigDecimal("5.00"), new BigDecimal("2.00"), new BigDecimal("3.00"),
                true, facts, BucketBand.defaults(), 7, 61, 1);
    }

    private static InstalmentFact fact(LocalDate due, String principal, String interest, String principalPaid, String interestPaid) {
        return new InstalmentFact(UUID.randomUUID(), 1, due, new BigDecimal(principal), new BigDecimal(interest), new BigDecimal(principalPaid), new BigDecimal(interestPaid));
    }

    private static DelinquencySnapshot snapshot(int daysPastDue, String principal, boolean included) {
        return new DelinquencySnapshot(
                UUID.randomUUID(), UUID.randomUUID(), AS_OF, UUID.randomUUID(), daysPastDue, "BUCKET", BigDecimal.ZERO,
                new BigDecimal(principal), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                false, false, daysPastDue > 0, daysPastDue > 0, daysPastDue >= 61, included, Instant.EPOCH);
    }
}
