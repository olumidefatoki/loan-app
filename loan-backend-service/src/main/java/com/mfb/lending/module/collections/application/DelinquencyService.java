package com.mfb.lending.module.collections.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.config.CollectionsProperties;
import com.mfb.lending.module.collections.calculation.DelinquencyCalculator;
import com.mfb.lending.module.collections.domain.CollectionModels.BucketBand;
import com.mfb.lending.module.collections.domain.CollectionModels.DelinquencyAssessment;
import com.mfb.lending.module.collections.domain.CollectionModels.DelinquencySnapshot;
import com.mfb.lending.module.collections.domain.CollectionModels.LoanPosition;
import com.mfb.lending.module.collections.domain.CollectionModels.ParReport;
import com.mfb.lending.module.collections.persistence.CollectionsStore;
import org.springframework.stereotype.Service;

@Service
public class DelinquencyService {

    private final CollectionsStore store;
    private final ServicingExposureSource exposures;
    private final CollectionsProperties properties;
    private final java.time.Clock clock;

    public DelinquencyService(
            CollectionsStore store,
            ServicingExposureSource exposures,
            CollectionsProperties properties,
            java.time.Clock clock) {
        this.store = store;
        this.exposures = exposures;
        this.properties = properties;
        this.clock = clock;
    }

    public int calculate(LocalDate asOf) {
        List<BucketBand> bands = store.buckets();
        Instant now = Instant.now(clock);
        int processed = 0;
        for (LoanPosition position : exposures.portfolio()) {
            DelinquencyAssessment assessment = DelinquencyCalculator.assess(
                    asOf,
                    position.outstandingPrincipal(),
                    position.outstandingInterest(),
                    position.outstandingFees(),
                    position.outstandingPenalties(),
                    position.portfolioIncluded(),
                    position.instalments(),
                    bands,
                    properties.getUpcomingDays(),
                    properties.getEscalationDays(),
                    properties.getDelinquentDays());
            store.saveSnapshot(new DelinquencySnapshot(
                    UUID.randomUUID(), position.loanAccountId(), asOf, position.scheduleId(), assessment.daysPastDue(), assessment.bucket(),
                    assessment.overdueAmount(), assessment.outstandingPrincipal(), assessment.overdueInterest(), position.outstandingInterest(),
                    position.outstandingFees(), position.outstandingPenalties(), assessment.missedInstalments(), assessment.collectionExposure(),
                    assessment.approachingDue(), assessment.partialPayment(), assessment.overdue(), assessment.delinquent(),
                    assessment.escalationRequired(), assessment.portfolioIncluded(), now));
            processed++;
        }
        return processed;
    }

    public ParReport par(LocalDate asOf) {
        LocalDate date = asOf == null ? today() : asOf;
        if (store.snapshotsOn(date).isEmpty()) {
            calculate(date);
        }
        return DelinquencyCalculator.portfolio(date, store.snapshotsOn(date));
    }

    public LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneId.of(properties.getZone()));
    }
}
