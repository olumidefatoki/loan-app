package com.mfb.lending.module.collections.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.module.collections.domain.CollectionModels.BucketBand;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionActivity;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionAssignment;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionCase;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionEscalation;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionNotification;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionPromise;
import com.mfb.lending.module.collections.domain.CollectionModels.CollectionsReport;
import com.mfb.lending.module.collections.domain.CollectionModels.DelinquencySnapshot;
import com.mfb.lending.module.collections.domain.CollectionModels.LoanRestructuring;
import com.mfb.lending.module.collections.domain.CollectionModels.MessageTemplate;
import com.mfb.lending.module.collections.domain.CollectionModels.RecoveryTransaction;
import com.mfb.lending.module.collections.domain.CollectionModels.RestructuringApproval;
import com.mfb.lending.module.collections.domain.CollectionModels.RestructuringScheduleVersion;

public interface CollectionsStore {

    String nextCaseReference();

    List<BucketBand> buckets();

    Optional<MessageTemplate> template(String code);

    void saveSnapshot(DelinquencySnapshot snapshot);

    List<DelinquencySnapshot> snapshotsOn(LocalDate asOf);

    Optional<DelinquencySnapshot> latestSnapshot(UUID loanAccountId);

    Optional<CollectionCase> openCase(UUID loanAccountId);

    Optional<CollectionCase> findCase(UUID id);

    void insertCase(CollectionCase collectionCase);

    void updateCase(CollectionCase collectionCase, int expectedVersion);

    List<CollectionCase> listCases(Set<UUID> branchIds, String status, int limit, int offset);

    long countCases(Set<UUID> branchIds, String status);

    List<CollectionCase> allCases();

    void insertAssignment(CollectionAssignment assignment);

    void deactivateAssignments(UUID caseId);

    List<CollectionAssignment> assignments(UUID caseId);

    List<CollectionAssignment> activeAssignments();

    void insertActivity(CollectionActivity activity);

    List<CollectionActivity> activities(UUID caseId);

    boolean activityLogged(UUID caseId, String activityType, Instant fromInclusive, Instant toExclusive);

    void insertPromise(CollectionPromise promise);

    void updatePromise(CollectionPromise promise);

    List<CollectionPromise> promises(UUID caseId);

    List<CollectionPromise> allPromises();

    void insertEscalation(CollectionEscalation escalation);

    Optional<CollectionEscalation> openEscalation(UUID caseId);

    List<CollectionEscalation> escalations(UUID caseId);

    void updateEscalation(CollectionEscalation escalation);

    void insertRestructuring(LoanRestructuring restructuring);

    Optional<LoanRestructuring> lockRestructuring(UUID id);

    List<LoanRestructuring> restructuringHistory(UUID loanAccountId);

    Optional<LoanRestructuring> pendingRestructuring(UUID loanAccountId);

    void updateRestructuring(LoanRestructuring restructuring, int expectedVersion);

    void insertApproval(RestructuringApproval approval);

    Optional<RestructuringApproval> approvalFor(UUID restructuringId);

    void insertScheduleLink(RestructuringScheduleVersion link);

    List<RestructuringScheduleVersion> scheduleLinks(UUID restructuringId);

    void insertRecovery(RecoveryTransaction recovery);

    List<RecoveryTransaction> recoveries(UUID caseId);

    List<RecoveryTransaction> allRecoveries();

    boolean recoveryExists(UUID repaymentId);

    boolean insertNotification(CollectionNotification notification);

    List<CollectionNotification> pendingNotifications(int limit);

    void markNotification(UUID id, String status, Instant sentAt);

    int countNotifications();

    void saveReport(CollectionsReport report);

    Optional<CollectionsReport> report(LocalDate asOf);

    void insertJobRun(String jobName, LocalDate businessDate, int processed, Instant at);

    int jobRuns(String jobName, LocalDate businessDate);
}
