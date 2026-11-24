package com.mfb.lending.module.collections.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.module.collections.calculation.DelinquencyRules;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionAssignment;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionCase;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionPromise;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionsReport;
import com.mfb.lending.module.collections.domain.CollectionModels.OfficerPerformance;
import com.mfb.lending.module.collections.domain.CollectionModels.RecoveryTransaction;
import com.mfb.lending.module.collections.persistence.CollectionsStore;
import org.springframework.stereotype.Service;

@Service
public class CollectionsReportService {

    private final CollectionsStore store;
    private final DelinquencyService delinquency;

    public CollectionsReportService(CollectionsStore store, DelinquencyService delinquency) {
        this.store = store;
        this.delinquency = delinquency;
    }

    public CollectionsReport report(LocalDate asOf) {
        LocalDate date = asOf == null ? delinquency.today() : asOf;
        return store.report(date).orElseGet(() -> build(date));
    }

    public CollectionsReport publish(LocalDate asOf) {
        CollectionsReport report = build(asOf);
        store.saveReport(report);
        return report;
    }

    private CollectionsReport build(LocalDate asOf) {
        long open = 0;
        long closed = 0;
        long escalated = 0;
        BigDecimal exposure = BigDecimal.ZERO;
        for (CollectionCase collectionCase : store.allCases()) {
            if ("CLOSED".equals(collectionCase.status())) {
                closed++;
                continue;
            }
            open++;
            exposure = exposure.add(collectionCase.collectionExposure());
            if ("ESCALATED".equals(collectionCase.status())) {
                escalated++;
            }
        }
        long promisesOpen = 0;
        long promisesKept = 0;
        long promisesBroken = 0;
        for (CollectionPromise promise : store.allPromises()) {
            switch (promise.status()) {
                case "OPEN" -> promisesOpen++;
                case "KEPT" -> promisesKept++;
                case "BROKEN" -> promisesBroken++;
                default -> {
                }
            }
        }
        BigDecimal recovered = BigDecimal.ZERO;
        Map<UUID, BigDecimal> recoveredByOfficer = new HashMap<>();
        for (RecoveryTransaction recovery : store.allRecoveries()) {
            recovered = recovered.add(recovery.amount());
            recoveredByOfficer.merge(recovery.recordedBy(), recovery.amount(), BigDecimal::add);
        }
        Map<UUID, Long> casesByOfficer = new HashMap<>();
        for (CollectionAssignment assignment : store.activeAssignments()) {
            casesByOfficer.merge(assignment.officerId(), 1L, Long::sum);
        }
        Set<UUID> officers = new HashSet<>();
        officers.addAll(casesByOfficer.keySet());
        officers.addAll(recoveredByOfficer.keySet());
        List<OfficerPerformance> performance = new ArrayList<>();
        for (UUID officerId : officers) {
            performance.add(new OfficerPerformance(
                    officerId, casesByOfficer.getOrDefault(officerId, 0L), recoveredByOfficer.getOrDefault(officerId, BigDecimal.ZERO)));
        }
        return new CollectionsReport(
                asOf, open, closed, escalated, promisesOpen, promisesKept, promisesBroken, recovered, exposure,
                DelinquencyRules.COLLECTION_EXPOSURE, performance);
    }
}
