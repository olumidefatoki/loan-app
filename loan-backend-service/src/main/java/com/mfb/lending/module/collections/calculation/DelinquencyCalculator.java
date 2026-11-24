package com.mfb.lending.module.collections.calculation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.mfb.lending.module.collections.domain.CollectionModels.BucketBand;
import com.mfb.lending.module.collections.domain.CollectionModels.DelinquencyAssessment;
import com.mfb.lending.module.collections.domain.CollectionModels.DelinquencySnapshot;
import com.mfb.lending.module.collections.domain.CollectionModels.InstalmentFact;
import com.mfb.lending.module.collections.domain.CollectionModels.ParBand;
import com.mfb.lending.module.collections.domain.CollectionModels.ParReport;

public final class DelinquencyCalculator {

    private static final BigDecimal ZERO = BigDecimal.ZERO;

    private DelinquencyCalculator() {
    }

    public static BigDecimal unpaid(InstalmentFact fact) {
        return unpaidPart(fact.principalDue(), fact.principalConfirmed()).add(unpaidPart(fact.interestDue(), fact.interestConfirmed()));
    }

    public static BigDecimal unpaidInterest(InstalmentFact fact) {
        return unpaidPart(fact.interestDue(), fact.interestConfirmed());
    }

    public static boolean partial(InstalmentFact fact) {
        BigDecimal confirmed = fact.principalConfirmed().add(fact.interestConfirmed());
        return confirmed.signum() > 0 && unpaid(fact).signum() > 0;
    }

    public static DelinquencyAssessment assess(
            LocalDate asOf,
            BigDecimal outstandingPrincipal,
            BigDecimal outstandingInterest,
            BigDecimal outstandingFees,
            BigDecimal outstandingPenalties,
            boolean portfolioIncluded,
            List<InstalmentFact> instalments,
            List<BucketBand> bands,
            int upcomingDays,
            int escalationDays,
            int delinquentDays) {
        int daysPastDue = 0;
        BigDecimal overdueAmount = ZERO;
        BigDecimal overdueInterest = ZERO;
        int missed = 0;
        boolean partialPayment = false;
        boolean approaching = false;
        LocalDate windowEnd = asOf.plusDays(Math.max(upcomingDays, 0));
        for (InstalmentFact fact : instalments) {
            BigDecimal unpaid = unpaid(fact);
            if (partial(fact)) {
                partialPayment = true;
            }
            if (fact.dueDate() == null || unpaid.signum() == 0) {
                continue;
            }
            if (fact.dueDate().isBefore(asOf)) {
                missed++;
                overdueAmount = overdueAmount.add(unpaid);
                overdueInterest = overdueInterest.add(unpaidInterest(fact));
                int days = (int) ChronoUnit.DAYS.between(fact.dueDate(), asOf);
                daysPastDue = Math.max(daysPastDue, days);
            } else if (!fact.dueDate().isAfter(windowEnd)) {
                approaching = true;
            }
        }
        String bucket = classify(daysPastDue, bands);
        boolean overdue = overdueAmount.signum() > 0;
        boolean delinquent = daysPastDue >= Math.max(delinquentDays, 1);
        boolean escalation = daysPastDue >= Math.max(escalationDays, 1);
        BigDecimal exposure = overdueAmount.add(nonNegative(outstandingFees)).add(nonNegative(outstandingPenalties));
        return new DelinquencyAssessment(
                daysPastDue, bucket, overdueAmount, nonNegative(outstandingPrincipal), overdueInterest, missed, exposure,
                approaching && daysPastDue == 0, partialPayment, overdue, delinquent, escalation, portfolioIncluded);
    }

    public static ParReport portfolio(LocalDate asOf, List<DelinquencySnapshot> snapshots) {
        List<DelinquencySnapshot> denominator = snapshots.stream()
                .filter(DelinquencySnapshot::portfolioIncluded)
                .filter(snapshot -> snapshot.outstandingPrincipal().signum() > 0)
                .toList();
        BigDecimal base = denominator.stream().map(DelinquencySnapshot::outstandingPrincipal).reduce(ZERO, BigDecimal::add);
        return new ParReport(
                asOf,
                DelinquencyRules.DENOMINATOR,
                DelinquencyRules.NUMERATOR,
                DelinquencyRules.DAYS_PAST_DUE,
                DelinquencyRules.CONFIRMED_ALLOCATION,
                DelinquencyRules.COLLECTION_EXPOSURE,
                base,
                denominator.size(),
                band(30, base, denominator),
                band(60, base, denominator),
                band(90, base, denominator));
    }

    public static String classify(int daysPastDue, List<BucketBand> bands) {
        List<BucketBand> available = bands == null || bands.isEmpty() ? BucketBand.defaults() : bands;
        return available.stream()
                .filter(band -> daysPastDue >= band.minDays() && (band.maxDays() == null || daysPastDue <= band.maxDays()))
                .map(BucketBand::code)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No delinquency bucket covers " + daysPastDue + " days"));
    }

    private static ParBand band(int threshold, BigDecimal denominator, List<DelinquencySnapshot> population) {
        List<DelinquencySnapshot> selected = population.stream().filter(snapshot -> snapshot.daysPastDue() >= threshold).toList();
        BigDecimal principal = selected.stream().map(DelinquencySnapshot::outstandingPrincipal).reduce(ZERO, BigDecimal::add);
        BigDecimal ratio = denominator.signum() == 0
                ? ZERO.setScale(6, RoundingMode.HALF_UP)
                : principal.divide(denominator, 6, RoundingMode.HALF_UP);
        return new ParBand(threshold, selected.size(), principal, ratio);
    }

    private static BigDecimal unpaidPart(BigDecimal due, BigDecimal confirmed) {
        BigDecimal safeDue = nonNegative(due);
        BigDecimal safeConfirmed = nonNegative(confirmed);
        BigDecimal unpaid = safeDue.subtract(safeConfirmed);
        return unpaid.signum() < 0 ? ZERO : unpaid;
    }

    private static BigDecimal nonNegative(BigDecimal amount) {
        return amount == null || amount.signum() < 0 ? ZERO : amount;
    }
}
