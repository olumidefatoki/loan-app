package com.mfb.lending.module.collections.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.module.collections.calculation.DelinquencyRules;
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
import com.mfb.lending.module.collections.domain.CollectionModels.OfficerPerformance;
import com.mfb.lending.module.collections.domain.CollectionModels.RecoveryTransaction;
import com.mfb.lending.module.collections.domain.CollectionModels.RestructuringApproval;
import com.mfb.lending.module.collections.domain.CollectionModels.RestructuringScheduleVersion;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCollectionsStore implements CollectionsStore {

    private static final UUID NOTIFICATION_KEY = UUID.fromString("00000000-0000-0000-0000-000000000000");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public JdbcCollectionsStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public String nextCaseReference() {
        Long value = jdbc.queryForObject("SELECT nextval('collection_case_reference_seq')", new MapSqlParameterSource(), Long.class);
        return "CC" + String.format("%012d", value == null ? 1 : value);
    }

    @Override
    public List<BucketBand> buckets() {
        return jdbc.query(
                "SELECT code, min_days, max_days FROM delinquency_buckets WHERE active = TRUE ORDER BY sort_order",
                (rs, row) -> new BucketBand(rs.getString("code"), rs.getInt("min_days"), (Integer) rs.getObject("max_days")));
    }

    @Override
    public Optional<MessageTemplate> template(String code) {
        return one("SELECT code, body, active FROM collection_message_templates WHERE code = :code",
                new MapSqlParameterSource("code", code),
                rs -> new MessageTemplate(rs.getString("code"), rs.getString("body"), rs.getBoolean("active")));
    }

    @Override
    public void saveSnapshot(DelinquencySnapshot snapshot) {
        Optional<DelinquencySnapshot> existing = one(
                "SELECT * FROM delinquency_snapshots WHERE loan_account_id = :loanAccountId AND as_of_date = :asOfDate",
                snapshotParams(snapshot), this::snapshot);
        DelinquencySnapshot saved = existing.map(current -> snapshot.withId(current.id())).orElse(snapshot);
        String sql = existing.isPresent()
                ? """
                UPDATE delinquency_snapshots SET
                    schedule_id = :scheduleId, days_past_due = :daysPastDue, bucket = :bucket, overdue_amount = :overdueAmount,
                    outstanding_principal = :outstandingPrincipal, overdue_interest = :overdueInterest,
                    outstanding_interest = :outstandingInterest, outstanding_fees = :outstandingFees,
                    outstanding_penalties = :outstandingPenalties, missed_instalments = :missedInstalments,
                    collection_exposure = :collectionExposure, approaching_due = :approachingDue, partial_payment = :partialPayment,
                    overdue = :overdue, delinquent = :delinquent, escalation_required = :escalationRequired,
                    portfolio_included = :portfolioIncluded, calculated_at = :calculatedAt
                WHERE id = :id
                """
                : """
                INSERT INTO delinquency_snapshots (
                    id, loan_account_id, as_of_date, schedule_id, days_past_due, bucket, overdue_amount, outstanding_principal,
                    overdue_interest, outstanding_interest, outstanding_fees, outstanding_penalties, missed_instalments,
                    collection_exposure, approaching_due, partial_payment, overdue, delinquent, escalation_required,
                    portfolio_included, calculated_at)
                VALUES (
                    :id, :loanAccountId, :asOfDate, :scheduleId, :daysPastDue, :bucket, :overdueAmount, :outstandingPrincipal,
                    :overdueInterest, :outstandingInterest, :outstandingFees, :outstandingPenalties, :missedInstalments,
                    :collectionExposure, :approachingDue, :partialPayment, :overdue, :delinquent, :escalationRequired,
                    :portfolioIncluded, :calculatedAt)
                """;
        try {
            jdbc.update(sql, snapshotParams(saved));
        } catch (DuplicateKeyException ex) {
            saveSnapshot(snapshot);
        }
    }

    @Override
    public List<DelinquencySnapshot> snapshotsOn(LocalDate asOf) {
        return jdbc.query("SELECT * FROM delinquency_snapshots WHERE as_of_date = :asOfDate",
                new MapSqlParameterSource("asOfDate", asOf), (rs, row) -> snapshot(rs));
    }

    @Override
    public Optional<DelinquencySnapshot> latestSnapshot(UUID loanAccountId) {
        return one("""
                SELECT * FROM delinquency_snapshots
                WHERE loan_account_id = :id
                ORDER BY as_of_date DESC
                LIMIT 1
                """, new MapSqlParameterSource("id", loanAccountId), this::snapshot);
    }

    @Override
    public Optional<CollectionCase> openCase(UUID loanAccountId) {
        return one("""
                SELECT * FROM collection_cases
                WHERE loan_account_id = :id AND status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ESCALATED')
                """, new MapSqlParameterSource("id", loanAccountId), this::collectionCase);
    }

    @Override
    public Optional<CollectionCase> findCase(UUID id) {
        return one("SELECT * FROM collection_cases WHERE id = :id", new MapSqlParameterSource("id", id), this::collectionCase);
    }

    @Override
    public void insertCase(CollectionCase collectionCase) {
        try {
            jdbc.update("""
                    INSERT INTO collection_cases (
                        id, reference, loan_account_id, customer_id, branch_id, status, bucket, days_past_due, overdue_amount,
                        collection_exposure, missed_instalments, opened_on, closed_at, closure_outcome, closure_note, version, created_at, updated_at)
                    VALUES (
                        :id, :reference, :loanAccountId, :customerId, :branchId, :status, :bucket, :daysPastDue, :overdueAmount,
                        :collectionExposure, :missedInstalments, :openedOn, :closedAt, :closureOutcome, :closureNote, :version, :createdAt, :updatedAt)
                    """, caseParams(collectionCase));
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("An open collection case already exists for this loan");
        }
    }

    @Override
    public void updateCase(CollectionCase collectionCase, int expectedVersion) {
        int updated = jdbc.update("""
                UPDATE collection_cases SET
                    status = :status, branch_id = :branchId, bucket = :bucket, days_past_due = :daysPastDue,
                    overdue_amount = :overdueAmount, collection_exposure = :collectionExposure, missed_instalments = :missedInstalments,
                    closed_at = :closedAt, closure_outcome = :closureOutcome, closure_note = :closureNote,
                    version = :version, updated_at = :updatedAt
                WHERE id = :id AND version = :expectedVersion
                """, caseParams(collectionCase).addValue("expectedVersion", expectedVersion));
        if (updated != 1) {
            throw new ConflictException("The collection case was updated by another officer");
        }
    }

    @Override
    public List<CollectionCase> listCases(Set<UUID> branchIds, String status, int limit, int offset) {
        if (branchIds != null && branchIds.isEmpty()) {
            return List.of();
        }
        Query query = caseFilter(branchIds, status);
        query.sql.append(" ORDER BY opened_on DESC, reference LIMIT :limit OFFSET :offset");
        query.params.addValue("limit", limit).addValue("offset", offset);
        return jdbc.query(query.sql.toString(), query.params, (rs, row) -> collectionCase(rs));
    }

    @Override
    public long countCases(Set<UUID> branchIds, String status) {
        if (branchIds != null && branchIds.isEmpty()) {
            return 0;
        }
        Query query = caseFilter(branchIds, status);
        query.sql.insert(0, "SELECT COUNT(*) FROM (");
        query.sql.append(") cases");
        Long total = jdbc.queryForObject(query.sql.toString(), query.params, Long.class);
        return total == null ? 0 : total;
    }

    @Override
    public List<CollectionCase> allCases() {
        return jdbc.query("SELECT * FROM collection_cases", (rs, row) -> collectionCase(rs));
    }

    @Override
    public void insertAssignment(CollectionAssignment assignment) {
        jdbc.update("""
                INSERT INTO collection_assignments (id, case_id, officer_id, branch_id, assigned_by, assigned_at, active)
                VALUES (:id, :caseId, :officerId, :branchId, :assignedBy, :assignedAt, :active)
                """, assignmentParams(assignment));
    }

    @Override
    public void deactivateAssignments(UUID caseId) {
        jdbc.update("UPDATE collection_assignments SET active = FALSE WHERE case_id = :id AND active = TRUE", new MapSqlParameterSource("id", caseId));
    }

    @Override
    public List<CollectionAssignment> assignments(UUID caseId) {
        return jdbc.query("SELECT * FROM collection_assignments WHERE case_id = :id ORDER BY assigned_at",
                new MapSqlParameterSource("id", caseId), (rs, row) -> assignment(rs));
    }

    @Override
    public List<CollectionAssignment> activeAssignments() {
        return jdbc.query("SELECT * FROM collection_assignments WHERE active = TRUE", (rs, row) -> assignment(rs));
    }

    @Override
    public void insertActivity(CollectionActivity activity) {
        jdbc.update("""
                INSERT INTO collection_activities (id, case_id, activity_type, channel, summary, contacted_at, actor_id, created_at)
                VALUES (:id, :caseId, :activityType, :channel, :summary, :contactedAt, :actorId, :createdAt)
                """, new MapSqlParameterSource()
                .addValue("id", activity.id())
                .addValue("caseId", activity.caseId())
                .addValue("activityType", activity.activityType())
                .addValue("channel", activity.channel())
                .addValue("summary", activity.summary())
                .addValue("contactedAt", activity.contactedAt())
                .addValue("actorId", activity.actorId())
                .addValue("createdAt", activity.createdAt()));
    }

    @Override
    public List<CollectionActivity> activities(UUID caseId) {
        return jdbc.query("SELECT * FROM collection_activities WHERE case_id = :id ORDER BY contacted_at",
                new MapSqlParameterSource("id", caseId), (rs, row) -> activity(rs));
    }

    @Override
    public boolean activityLogged(UUID caseId, String activityType, Instant fromInclusive, Instant toExclusive) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM collection_activities
                WHERE case_id = :caseId AND activity_type = :activityType
                  AND contacted_at >= :fromInclusive AND contacted_at < :toExclusive
                """, new MapSqlParameterSource()
                .addValue("caseId", caseId)
                .addValue("activityType", activityType)
                .addValue("fromInclusive", fromInclusive)
                .addValue("toExclusive", toExclusive), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public void insertPromise(CollectionPromise promise) {
        jdbc.update("""
                INSERT INTO collection_promises (
                    id, case_id, loan_account_id, amount, promise_date, status, note, created_by, created_at, resolved_at)
                VALUES (
                    :id, :caseId, :loanAccountId, :amount, :promiseDate, :status, :note, :createdBy, :createdAt, :resolvedAt)
                """, promiseParams(promise));
    }

    @Override
    public void updatePromise(CollectionPromise promise) {
        jdbc.update("""
                UPDATE collection_promises
                SET status = :status, resolved_at = :resolvedAt
                WHERE id = :id
                """, promiseParams(promise));
    }

    @Override
    public List<CollectionPromise> promises(UUID caseId) {
        return jdbc.query("SELECT * FROM collection_promises WHERE case_id = :id ORDER BY created_at",
                new MapSqlParameterSource("id", caseId), (rs, row) -> promise(rs));
    }

    @Override
    public List<CollectionPromise> allPromises() {
        return jdbc.query("SELECT * FROM collection_promises", (rs, row) -> promise(rs));
    }

    @Override
    public void insertEscalation(CollectionEscalation escalation) {
        try {
            jdbc.update("""
                    INSERT INTO collection_escalations (id, case_id, reason, from_bucket, level, status, requested_by, created_at)
                    VALUES (:id, :caseId, :reason, :fromBucket, :level, :status, :requestedBy, :createdAt)
                    """, escalationParams(escalation));
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("This collection case already has an open escalation");
        }
    }

    @Override
    public Optional<CollectionEscalation> openEscalation(UUID caseId) {
        return one("SELECT * FROM collection_escalations WHERE case_id = :id AND status = 'OPEN'",
                new MapSqlParameterSource("id", caseId), this::escalation);
    }

    @Override
    public List<CollectionEscalation> escalations(UUID caseId) {
        return jdbc.query("SELECT * FROM collection_escalations WHERE case_id = :id ORDER BY created_at",
                new MapSqlParameterSource("id", caseId), (rs, row) -> escalation(rs));
    }

    @Override
    public void updateEscalation(CollectionEscalation escalation) {
        jdbc.update("UPDATE collection_escalations SET status = :status WHERE id = :id", escalationParams(escalation));
    }

    @Override
    public void insertRestructuring(LoanRestructuring restructuring) {
        try {
            jdbc.update("""
                    INSERT INTO loan_restructuring (
                        id, loan_account_id, restructuring_type, status, justification, tenure, interest_rate, repayment_frequency,
                        repayment_day_of_month, holiday_rule, revised_instalment, moratorium_days, partial_settlement_amount,
                        revised_maturity, value_date, requested_by, requested_by_name, requested_at, dpd_at_request, bucket_at_request,
                        overdue_at_request, dpd_at_decision, bucket_at_decision, overdue_at_decision, previous_schedule_id, new_schedule_id,
                        outstanding_principal_before, outstanding_interest_before, outstanding_principal_after, outstanding_interest_after,
                        reconciliation_matched, reconciliation_difference, version)
                    VALUES (
                        :id, :loanAccountId, :type, :status, :justification, :tenure, :interestRate, :repaymentFrequency,
                        :repaymentDayOfMonth, :holidayRule, :revisedInstalment, :moratoriumDays, :partialSettlementAmount,
                        :revisedMaturity, :valueDate, :requestedBy, :requestedByName, :requestedAt, :daysPastDueAtRequest, :bucketAtRequest,
                        :overdueAtRequest, :daysPastDueAtDecision, :bucketAtDecision, :overdueAtDecision, :previousScheduleId, :newScheduleId,
                        :outstandingPrincipalBefore, :outstandingInterestBefore, :outstandingPrincipalAfter, :outstandingInterestAfter,
                        :reconciliationMatched, :reconciliationDifference, :version)
                    """, restructuringParams(restructuring));
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("This loan already has a restructuring waiting for approval");
        }
    }

    @Override
    public Optional<LoanRestructuring> lockRestructuring(UUID id) {
        return one("SELECT * FROM loan_restructuring WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", id), this::restructuring);
    }

    @Override
    public List<LoanRestructuring> restructuringHistory(UUID loanAccountId) {
        return jdbc.query("SELECT * FROM loan_restructuring WHERE loan_account_id = :id ORDER BY requested_at",
                new MapSqlParameterSource("id", loanAccountId), (rs, row) -> restructuring(rs));
    }

    @Override
    public Optional<LoanRestructuring> pendingRestructuring(UUID loanAccountId) {
        return one("SELECT * FROM loan_restructuring WHERE loan_account_id = :id AND status = 'REQUESTED'",
                new MapSqlParameterSource("id", loanAccountId), this::restructuring);
    }

    @Override
    public void updateRestructuring(LoanRestructuring restructuring, int expectedVersion) {
        int updated = jdbc.update("""
                UPDATE loan_restructuring SET
                    status = :status, dpd_at_decision = :daysPastDueAtDecision, bucket_at_decision = :bucketAtDecision,
                    overdue_at_decision = :overdueAtDecision, previous_schedule_id = :previousScheduleId, new_schedule_id = :newScheduleId,
                    outstanding_principal_before = :outstandingPrincipalBefore, outstanding_interest_before = :outstandingInterestBefore,
                    outstanding_principal_after = :outstandingPrincipalAfter, outstanding_interest_after = :outstandingInterestAfter,
                    reconciliation_matched = :reconciliationMatched, reconciliation_difference = :reconciliationDifference, version = :version
                WHERE id = :id AND version = :expectedVersion
                """, restructuringParams(restructuring).addValue("expectedVersion", expectedVersion));
        if (updated != 1) {
            throw new ConflictException("This restructuring was updated by another officer");
        }
    }

    @Override
    public void insertApproval(RestructuringApproval approval) {
        jdbc.update("""
                INSERT INTO restructuring_approvals (id, restructuring_id, decision, approver_id, approver_name, reason, decided_at)
                VALUES (:id, :restructuringId, :decision, :approverId, :approverName, :reason, :decidedAt)
                """, new MapSqlParameterSource()
                .addValue("id", approval.id())
                .addValue("restructuringId", approval.restructuringId())
                .addValue("decision", approval.decision())
                .addValue("approverId", approval.approverId())
                .addValue("approverName", approval.approverName())
                .addValue("reason", approval.reason())
                .addValue("decidedAt", approval.decidedAt()));
    }

    @Override
    public Optional<RestructuringApproval> approvalFor(UUID restructuringId) {
        return one("SELECT * FROM restructuring_approvals WHERE restructuring_id = :id",
                new MapSqlParameterSource("id", restructuringId), rs -> new RestructuringApproval(
                        rs.getObject("id", UUID.class), rs.getObject("restructuring_id", UUID.class), rs.getString("decision"),
                        rs.getObject("approver_id", UUID.class), rs.getString("approver_name"), rs.getString("reason"), instant(rs, "decided_at")));
    }

    @Override
    public void insertScheduleLink(RestructuringScheduleVersion link) {
        jdbc.update("""
                INSERT INTO restructuring_schedule_versions (
                    id, restructuring_id, previous_schedule_id, new_schedule_id, previous_version, new_version, preserved)
                VALUES (:id, :restructuringId, :previousScheduleId, :newScheduleId, :previousVersion, :newVersion, :preserved)
                """, new MapSqlParameterSource()
                .addValue("id", link.id())
                .addValue("restructuringId", link.restructuringId())
                .addValue("previousScheduleId", link.previousScheduleId())
                .addValue("newScheduleId", link.newScheduleId())
                .addValue("previousVersion", link.previousVersion())
                .addValue("newVersion", link.newVersion())
                .addValue("preserved", link.preserved()));
    }

    @Override
    public List<RestructuringScheduleVersion> scheduleLinks(UUID restructuringId) {
        return jdbc.query("SELECT * FROM restructuring_schedule_versions WHERE restructuring_id = :id",
                new MapSqlParameterSource("id", restructuringId), (rs, row) -> new RestructuringScheduleVersion(
                        rs.getObject("id", UUID.class), rs.getObject("restructuring_id", UUID.class),
                        rs.getObject("previous_schedule_id", UUID.class), rs.getObject("new_schedule_id", UUID.class),
                        rs.getInt("previous_version"), rs.getInt("new_version"), rs.getBoolean("preserved")));
    }

    @Override
    public void insertRecovery(RecoveryTransaction recovery) {
        try {
            jdbc.update("""
                    INSERT INTO recovery_transactions (
                        id, case_id, loan_account_id, repayment_id, amount, outcome, status, note, recorded_by, recorded_at)
                    VALUES (
                        :id, :caseId, :loanAccountId, :repaymentId, :amount, :outcome, :status, :note, :recordedBy, :recordedAt)
                    """, recoveryParams(recovery));
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("This confirmed repayment is already recorded as a recovery");
        }
    }

    @Override
    public List<RecoveryTransaction> recoveries(UUID caseId) {
        return jdbc.query("SELECT * FROM recovery_transactions WHERE case_id = :id ORDER BY recorded_at",
                new MapSqlParameterSource("id", caseId), (rs, row) -> recovery(rs));
    }

    @Override
    public List<RecoveryTransaction> allRecoveries() {
        return jdbc.query("SELECT * FROM recovery_transactions", (rs, row) -> recovery(rs));
    }

    @Override
    public boolean recoveryExists(UUID repaymentId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM recovery_transactions WHERE repayment_id = :id",
                new MapSqlParameterSource("id", repaymentId), Integer.class);
        return count != null && count > 0;
    }

    @Override
    public boolean insertNotification(CollectionNotification notification) {
        int inserted = jdbc.update("""
                INSERT INTO collection_notifications (
                    id, loan_account_id, customer_id, template_code, business_date, schedule_item_id, repayment_id,
                    restructuring_id, schedule_item_key, repayment_key, restructuring_key, payload, status, created_at, sent_at)
                VALUES (
                    :id, :loanAccountId, :customerId, :templateCode, :businessDate, :scheduleItemId, :repaymentId,
                    :restructuringId, :scheduleItemKey, :repaymentKey, :restructuringKey, CAST(:payload AS jsonb), :status, :createdAt, :sentAt)
                ON CONFLICT (template_code, business_date, loan_account_id, schedule_item_key, repayment_key, restructuring_key) DO NOTHING
                """, notificationParams(notification));
        return inserted == 1;
    }

    @Override
    public List<CollectionNotification> pendingNotifications(int limit) {
        return jdbc.query("""
                SELECT * FROM collection_notifications
                WHERE status = 'PENDING'
                ORDER BY created_at
                LIMIT :limit
                """, new MapSqlParameterSource("limit", limit), (rs, row) -> notification(rs));
    }

    @Override
    public void markNotification(UUID id, String status, Instant sentAt) {
        jdbc.update("UPDATE collection_notifications SET status = :status, sent_at = :sentAt WHERE id = :id AND status = 'PENDING'",
                new MapSqlParameterSource().addValue("id", id).addValue("status", status).addValue("sentAt", sentAt));
    }

    @Override
    public int countNotifications() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM collection_notifications", new MapSqlParameterSource(), Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public void saveReport(CollectionsReport report) {
        jdbc.update("""
                INSERT INTO collection_report_snapshots (
                    report_date, open_cases, closed_cases, escalated_cases, promises_open, promises_kept, promises_broken,
                    recovered_amount, collection_exposure, officers, calculated_at)
                VALUES (
                    :reportDate, :openCases, :closedCases, :escalatedCases, :promisesOpen, :promisesKept, :promisesBroken,
                    :recoveredAmount, :collectionExposure, CAST(:officers AS jsonb), :calculatedAt)
                ON CONFLICT (report_date) DO UPDATE SET
                    open_cases = EXCLUDED.open_cases, closed_cases = EXCLUDED.closed_cases, escalated_cases = EXCLUDED.escalated_cases,
                    promises_open = EXCLUDED.promises_open, promises_kept = EXCLUDED.promises_kept, promises_broken = EXCLUDED.promises_broken,
                    recovered_amount = EXCLUDED.recovered_amount, collection_exposure = EXCLUDED.collection_exposure,
                    officers = EXCLUDED.officers, calculated_at = EXCLUDED.calculated_at
                """, new MapSqlParameterSource()
                .addValue("reportDate", report.asOfDate())
                .addValue("openCases", report.openCases())
                .addValue("closedCases", report.closedCases())
                .addValue("escalatedCases", report.escalatedCases())
                .addValue("promisesOpen", report.promisesOpen())
                .addValue("promisesKept", report.promisesKept())
                .addValue("promisesBroken", report.promisesBroken())
                .addValue("recoveredAmount", report.recoveredAmount())
                .addValue("collectionExposure", report.collectionExposure())
                .addValue("officers", officersJson(report.officers()))
                .addValue("calculatedAt", Instant.now()));
    }

    @Override
    public Optional<CollectionsReport> report(LocalDate asOf) {
        return one("SELECT * FROM collection_report_snapshots WHERE report_date = :asOf", new MapSqlParameterSource("asOf", asOf), rs -> new CollectionsReport(
                rs.getObject("report_date", LocalDate.class), rs.getLong("open_cases"), rs.getLong("closed_cases"), rs.getLong("escalated_cases"),
                rs.getLong("promises_open"), rs.getLong("promises_kept"), rs.getLong("promises_broken"),
                rs.getBigDecimal("recovered_amount"), rs.getBigDecimal("collection_exposure"), DelinquencyRules.COLLECTION_EXPOSURE,
                readOfficers(rs.getString("officers"))));
    }

    @Override
    public void insertJobRun(String jobName, LocalDate businessDate, int processed, Instant at) {
        jdbc.update("""
                INSERT INTO collection_job_runs (id, job_name, business_date, processed, started_at)
                VALUES (:id, :jobName, :businessDate, :processed, :startedAt)
                """, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("jobName", jobName)
                .addValue("businessDate", businessDate)
                .addValue("processed", processed)
                .addValue("startedAt", at));
    }

    @Override
    public int jobRuns(String jobName, LocalDate businessDate) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM collection_job_runs
                WHERE job_name = :jobName AND business_date = :businessDate
                """, new MapSqlParameterSource().addValue("jobName", jobName).addValue("businessDate", businessDate), Integer.class);
        return count == null ? 0 : count;
    }

    private Query caseFilter(Set<UUID> branchIds, String status) {
        Query query = new Query();
        query.sql.append("SELECT * FROM collection_cases WHERE 1 = 1");
        if (branchIds != null) {
            query.sql.append(" AND branch_id IN (:branchIds)");
            query.params.addValue("branchIds", branchIds);
        }
        if (status != null && !status.isBlank()) {
            query.sql.append(" AND status = :status");
            query.params.addValue("status", status);
        }
        return query;
    }

    private MapSqlParameterSource snapshotParams(DelinquencySnapshot snapshot) {
        return new MapSqlParameterSource()
                .addValue("id", snapshot.id())
                .addValue("loanAccountId", snapshot.loanAccountId())
                .addValue("asOfDate", snapshot.asOfDate())
                .addValue("scheduleId", snapshot.scheduleId())
                .addValue("daysPastDue", snapshot.daysPastDue())
                .addValue("bucket", snapshot.bucket())
                .addValue("overdueAmount", snapshot.overdueAmount())
                .addValue("outstandingPrincipal", snapshot.outstandingPrincipal())
                .addValue("overdueInterest", snapshot.overdueInterest())
                .addValue("outstandingInterest", snapshot.outstandingInterest())
                .addValue("outstandingFees", snapshot.outstandingFees())
                .addValue("outstandingPenalties", snapshot.outstandingPenalties())
                .addValue("missedInstalments", snapshot.missedInstalments())
                .addValue("collectionExposure", snapshot.collectionExposure())
                .addValue("approachingDue", snapshot.approachingDue())
                .addValue("partialPayment", snapshot.partialPayment())
                .addValue("overdue", snapshot.overdue())
                .addValue("delinquent", snapshot.delinquent())
                .addValue("escalationRequired", snapshot.escalationRequired())
                .addValue("portfolioIncluded", snapshot.portfolioIncluded())
                .addValue("calculatedAt", snapshot.calculatedAt());
    }

    private MapSqlParameterSource caseParams(CollectionCase collectionCase) {
        return new MapSqlParameterSource()
                .addValue("id", collectionCase.id())
                .addValue("reference", collectionCase.reference())
                .addValue("loanAccountId", collectionCase.loanAccountId())
                .addValue("customerId", collectionCase.customerId())
                .addValue("branchId", collectionCase.branchId())
                .addValue("status", collectionCase.status())
                .addValue("bucket", collectionCase.bucket())
                .addValue("daysPastDue", collectionCase.daysPastDue())
                .addValue("overdueAmount", collectionCase.overdueAmount())
                .addValue("collectionExposure", collectionCase.collectionExposure())
                .addValue("missedInstalments", collectionCase.missedInstalments())
                .addValue("openedOn", collectionCase.openedOn())
                .addValue("closedAt", collectionCase.closedAt())
                .addValue("closureOutcome", collectionCase.closureOutcome())
                .addValue("closureNote", collectionCase.closureNote())
                .addValue("version", collectionCase.version())
                .addValue("createdAt", collectionCase.createdAt())
                .addValue("updatedAt", collectionCase.updatedAt());
    }

    private static MapSqlParameterSource assignmentParams(CollectionAssignment assignment) {
        return new MapSqlParameterSource()
                .addValue("id", assignment.id())
                .addValue("caseId", assignment.caseId())
                .addValue("officerId", assignment.officerId())
                .addValue("branchId", assignment.branchId())
                .addValue("assignedBy", assignment.assignedBy())
                .addValue("assignedAt", assignment.assignedAt())
                .addValue("active", assignment.active());
    }

    private static MapSqlParameterSource promiseParams(CollectionPromise promise) {
        return new MapSqlParameterSource()
                .addValue("id", promise.id())
                .addValue("caseId", promise.caseId())
                .addValue("loanAccountId", promise.loanAccountId())
                .addValue("amount", promise.amount())
                .addValue("promiseDate", promise.promiseDate())
                .addValue("status", promise.status())
                .addValue("note", promise.note())
                .addValue("createdBy", promise.createdBy())
                .addValue("createdAt", promise.createdAt())
                .addValue("resolvedAt", promise.resolvedAt());
    }

    private static MapSqlParameterSource escalationParams(CollectionEscalation escalation) {
        return new MapSqlParameterSource()
                .addValue("id", escalation.id())
                .addValue("caseId", escalation.caseId())
                .addValue("reason", escalation.reason())
                .addValue("fromBucket", escalation.fromBucket())
                .addValue("level", escalation.level())
                .addValue("status", escalation.status())
                .addValue("requestedBy", escalation.requestedBy())
                .addValue("createdAt", escalation.createdAt());
    }

    private static MapSqlParameterSource restructuringParams(LoanRestructuring restructuring) {
        return new MapSqlParameterSource()
                .addValue("id", restructuring.id())
                .addValue("loanAccountId", restructuring.loanAccountId())
                .addValue("type", restructuring.type())
                .addValue("status", restructuring.status())
                .addValue("justification", restructuring.justification())
                .addValue("tenure", restructuring.tenure())
                .addValue("interestRate", restructuring.interestRate())
                .addValue("repaymentFrequency", restructuring.repaymentFrequency())
                .addValue("repaymentDayOfMonth", restructuring.repaymentDayOfMonth())
                .addValue("holidayRule", restructuring.holidayRule())
                .addValue("revisedInstalment", restructuring.revisedInstalment())
                .addValue("moratoriumDays", restructuring.moratoriumDays())
                .addValue("partialSettlementAmount", restructuring.partialSettlementAmount())
                .addValue("revisedMaturity", restructuring.revisedMaturity())
                .addValue("valueDate", restructuring.valueDate())
                .addValue("requestedBy", restructuring.requestedBy())
                .addValue("requestedByName", restructuring.requestedByName())
                .addValue("requestedAt", restructuring.requestedAt())
                .addValue("daysPastDueAtRequest", restructuring.daysPastDueAtRequest())
                .addValue("bucketAtRequest", restructuring.bucketAtRequest())
                .addValue("overdueAtRequest", restructuring.overdueAtRequest())
                .addValue("daysPastDueAtDecision", restructuring.daysPastDueAtDecision())
                .addValue("bucketAtDecision", restructuring.bucketAtDecision())
                .addValue("overdueAtDecision", restructuring.overdueAtDecision())
                .addValue("previousScheduleId", restructuring.previousScheduleId())
                .addValue("newScheduleId", restructuring.newScheduleId())
                .addValue("outstandingPrincipalBefore", restructuring.outstandingPrincipalBefore())
                .addValue("outstandingInterestBefore", restructuring.outstandingInterestBefore())
                .addValue("outstandingPrincipalAfter", restructuring.outstandingPrincipalAfter())
                .addValue("outstandingInterestAfter", restructuring.outstandingInterestAfter())
                .addValue("reconciliationMatched", restructuring.reconciliationMatched())
                .addValue("reconciliationDifference", restructuring.reconciliationDifference())
                .addValue("version", restructuring.version());
    }

    private static MapSqlParameterSource recoveryParams(RecoveryTransaction recovery) {
        return new MapSqlParameterSource()
                .addValue("id", recovery.id())
                .addValue("caseId", recovery.caseId())
                .addValue("loanAccountId", recovery.loanAccountId())
                .addValue("repaymentId", recovery.repaymentId())
                .addValue("amount", recovery.amount())
                .addValue("outcome", recovery.outcome())
                .addValue("status", recovery.status())
                .addValue("note", recovery.note())
                .addValue("recordedBy", recovery.recordedBy())
                .addValue("recordedAt", recovery.recordedAt());
    }

    private static MapSqlParameterSource notificationParams(CollectionNotification notification) {
        return new MapSqlParameterSource()
                .addValue("id", notification.id())
                .addValue("loanAccountId", notification.loanAccountId())
                .addValue("customerId", notification.customerId())
                .addValue("templateCode", notification.templateCode())
                .addValue("businessDate", notification.businessDate())
                .addValue("scheduleItemId", notification.scheduleItemId())
                .addValue("repaymentId", notification.repaymentId())
                .addValue("restructuringId", notification.restructuringId())
                .addValue("scheduleItemKey", notification.scheduleItemId() == null ? NOTIFICATION_KEY : notification.scheduleItemId())
                .addValue("repaymentKey", notification.repaymentId() == null ? NOTIFICATION_KEY : notification.repaymentId())
                .addValue("restructuringKey", notification.restructuringId() == null ? NOTIFICATION_KEY : notification.restructuringId())
                .addValue("payload", notification.payload())
                .addValue("status", notification.status())
                .addValue("createdAt", notification.createdAt())
                .addValue("sentAt", notification.sentAt());
    }

    private DelinquencySnapshot snapshot(ResultSet rs) throws SQLException {
        return new DelinquencySnapshot(
                rs.getObject("id", UUID.class), rs.getObject("loan_account_id", UUID.class), rs.getObject("as_of_date", LocalDate.class),
                rs.getObject("schedule_id", UUID.class), rs.getInt("days_past_due"), rs.getString("bucket"), rs.getBigDecimal("overdue_amount"),
                rs.getBigDecimal("outstanding_principal"), rs.getBigDecimal("overdue_interest"), rs.getBigDecimal("outstanding_interest"),
                rs.getBigDecimal("outstanding_fees"), rs.getBigDecimal("outstanding_penalties"), rs.getInt("missed_instalments"),
                rs.getBigDecimal("collection_exposure"), rs.getBoolean("approaching_due"), rs.getBoolean("partial_payment"),
                rs.getBoolean("overdue"), rs.getBoolean("delinquent"), rs.getBoolean("escalation_required"), rs.getBoolean("portfolio_included"),
                instant(rs, "calculated_at"));
    }

    private CollectionCase collectionCase(ResultSet rs) throws SQLException {
        return new CollectionCase(
                rs.getObject("id", UUID.class), rs.getString("reference"), rs.getObject("loan_account_id", UUID.class),
                rs.getObject("customer_id", UUID.class), rs.getObject("branch_id", UUID.class), rs.getString("status"), rs.getString("bucket"),
                rs.getInt("days_past_due"), rs.getBigDecimal("overdue_amount"), rs.getBigDecimal("collection_exposure"),
                rs.getInt("missed_instalments"), rs.getObject("opened_on", LocalDate.class), instant(rs, "closed_at"),
                rs.getString("closure_outcome"), rs.getString("closure_note"), rs.getInt("version"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private CollectionAssignment assignment(ResultSet rs) throws SQLException {
        return new CollectionAssignment(
                rs.getObject("id", UUID.class), rs.getObject("case_id", UUID.class), rs.getObject("officer_id", UUID.class),
                rs.getObject("branch_id", UUID.class), rs.getObject("assigned_by", UUID.class), instant(rs, "assigned_at"), rs.getBoolean("active"));
    }

    private CollectionActivity activity(ResultSet rs) throws SQLException {
        return new CollectionActivity(
                rs.getObject("id", UUID.class), rs.getObject("case_id", UUID.class), rs.getString("activity_type"), rs.getString("channel"),
                rs.getString("summary"), instant(rs, "contacted_at"), rs.getObject("actor_id", UUID.class), instant(rs, "created_at"));
    }

    private CollectionPromise promise(ResultSet rs) throws SQLException {
        return new CollectionPromise(
                rs.getObject("id", UUID.class), rs.getObject("case_id", UUID.class), rs.getObject("loan_account_id", UUID.class),
                rs.getBigDecimal("amount"), rs.getObject("promise_date", LocalDate.class), rs.getString("status"), rs.getString("note"),
                rs.getObject("created_by", UUID.class), instant(rs, "created_at"), instant(rs, "resolved_at"));
    }

    private CollectionEscalation escalation(ResultSet rs) throws SQLException {
        return new CollectionEscalation(
                rs.getObject("id", UUID.class), rs.getObject("case_id", UUID.class), rs.getString("reason"), rs.getString("from_bucket"),
                rs.getInt("level"), rs.getString("status"), rs.getObject("requested_by", UUID.class), instant(rs, "created_at"));
    }

    private LoanRestructuring restructuring(ResultSet rs) throws SQLException {
        return new LoanRestructuring(
                rs.getObject("id", UUID.class), rs.getObject("loan_account_id", UUID.class), rs.getString("restructuring_type"), rs.getString("status"),
                rs.getString("justification"), (Integer) rs.getObject("tenure"), rs.getBigDecimal("interest_rate"), rs.getString("repayment_frequency"),
                (Integer) rs.getObject("repayment_day_of_month"), rs.getString("holiday_rule"), rs.getBigDecimal("revised_instalment"),
                (Integer) rs.getObject("moratorium_days"), rs.getBigDecimal("partial_settlement_amount"), rs.getObject("revised_maturity", LocalDate.class),
                rs.getObject("value_date", LocalDate.class), rs.getObject("requested_by", UUID.class), rs.getString("requested_by_name"),
                instant(rs, "requested_at"), rs.getInt("dpd_at_request"), rs.getString("bucket_at_request"), rs.getBigDecimal("overdue_at_request"),
                (Integer) rs.getObject("dpd_at_decision"), rs.getString("bucket_at_decision"), rs.getBigDecimal("overdue_at_decision"),
                rs.getObject("previous_schedule_id", UUID.class), rs.getObject("new_schedule_id", UUID.class),
                rs.getBigDecimal("outstanding_principal_before"), rs.getBigDecimal("outstanding_interest_before"),
                rs.getBigDecimal("outstanding_principal_after"), rs.getBigDecimal("outstanding_interest_after"),
                (Boolean) rs.getObject("reconciliation_matched"), rs.getBigDecimal("reconciliation_difference"), rs.getInt("version"));
    }

    private RecoveryTransaction recovery(ResultSet rs) throws SQLException {
        return new RecoveryTransaction(
                rs.getObject("id", UUID.class), rs.getObject("case_id", UUID.class), rs.getObject("loan_account_id", UUID.class),
                rs.getObject("repayment_id", UUID.class), rs.getBigDecimal("amount"), rs.getString("outcome"), rs.getString("status"),
                rs.getString("note"), rs.getObject("recorded_by", UUID.class), instant(rs, "recorded_at"));
    }

    private CollectionNotification notification(ResultSet rs) throws SQLException {
        return new CollectionNotification(
                rs.getObject("id", UUID.class), rs.getObject("loan_account_id", UUID.class), rs.getObject("customer_id", UUID.class),
                rs.getString("template_code"), rs.getObject("business_date", LocalDate.class), rs.getObject("schedule_item_id", UUID.class),
                rs.getObject("repayment_id", UUID.class), rs.getObject("restructuring_id", UUID.class), rs.getString("payload"),
                rs.getString("status"), instant(rs, "created_at"), instant(rs, "sent_at"));
    }

    private String officersJson(List<OfficerPerformance> officers) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < officers.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            OfficerPerformance officer = officers.get(index);
            json.append("{\"officerId\":\"")
                    .append(officer.officerId())
                    .append("\",\"activeCases\":")
                    .append(officer.activeCases())
                    .append(",\"recoveredAmount\":\"")
                    .append(officer.recoveredAmount().toPlainString())
                    .append("\"}");
        }
        return json.append(']').toString();
    }

    private List<OfficerPerformance> readOfficers(String json) {
        try {
            JsonNode node = objectMapper.readTree(json == null ? "[]" : json);
            List<OfficerPerformance> officers = new ArrayList<>();
            node.forEach(item -> officers.add(new OfficerPerformance(
                    UUID.fromString(item.path("officerId").asText()),
                    item.path("activeCases").asLong(),
                    new BigDecimal(item.path("recoveredAmount").asText("0")))));
            return officers;
        } catch (Exception ex) {
            throw new IllegalStateException("Collection report officers could not be read", ex);
        }
    }

    private <T> Optional<T> one(String sql, MapSqlParameterSource params, RowReader<T> reader) {
        List<T> rows = jdbc.query(sql, params, (rs, row) -> reader.read(rs));
        return rows.stream().findFirst();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    @FunctionalInterface
    private interface RowReader<T> {
        T read(ResultSet resultSet) throws SQLException;
    }

    private static final class Query {
        private final StringBuilder sql = new StringBuilder();
        private final MapSqlParameterSource params = new MapSqlParameterSource();
    }
}
