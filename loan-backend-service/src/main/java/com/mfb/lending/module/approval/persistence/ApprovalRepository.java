package com.mfb.lending.module.approval.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.module.approval.domain.ApprovalAssignment;
import com.mfb.lending.module.approval.domain.ApprovalDecision;
import com.mfb.lending.module.approval.domain.ApprovalDelegation;
import com.mfb.lending.module.approval.domain.ApprovalExceptionCase;
import com.mfb.lending.module.approval.domain.ApprovalHistoryEntry;
import com.mfb.lending.module.approval.domain.ApprovalLevel;
import com.mfb.lending.module.approval.domain.ApprovalMatrix;
import com.mfb.lending.module.approval.domain.ApprovalStage;
import com.mfb.lending.module.approval.domain.ApprovalStatus;
import com.mfb.lending.module.approval.domain.LoanApproval;
import com.mfb.lending.module.approval.domain.MatrixStatus;
import com.mfb.lending.module.approval.domain.MatrixVersion;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ApprovalRepository {

    private static final String MATRIX_COLUMNS = """
            id, code, name, description, status, current_version_id, created_at, updated_at, created_by, updated_by
            """;

    private static final String VERSION_COLUMNS = """
            id, matrix_id, version_number, status, product_id, customer_segment, branch_id, risk_rating,
            collateral_status, security_classification, exception_type, automated_allowed, amount_min, amount_max,
            activated_by, activated_at, created_at, created_by
            """;

    private static final String LEVEL_COLUMNS = """
            id, version_id, stage, sequence_no, role_code, min_amount, max_amount, quorum, expiry_hours,
            manual_required, automated, escalate_on_expiry
            """;

    private static final String APPROVAL_COLUMNS = """
            id, reference, application_id, application_revision, credit_assessment_id, credit_result_id, matrix_id,
            matrix_version_id, exception_id, amount, currency_code, stage, stage_sequence, status, decision,
            decision_reason, decided_by, decided_at, expires_at, automated, requested_by, correlation_id, version,
            created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ApprovalRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String nextReference(int year) {
        Long value = jdbc.queryForObject(
                "SELECT nextval('loan_approval_reference_seq')",
                new MapSqlParameterSource(),
                Long.class);
        if (value == null || value < 1 || value > 99_999_999L) {
            throw new IllegalStateException("Loan approval reference sequence is exhausted");
        }
        return "AP" + year + String.format("%08d", value);
    }

    public boolean transitionAllowed(ApprovalStatus from, ApprovalStatus to) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM approval_workflow_transitions
                WHERE from_status = :fromStatus AND to_status = :toStatus
                """,
                new MapSqlParameterSource().addValue("fromStatus", from.name()).addValue("toStatus", to.name()),
                Integer.class);
        return count != null && count > 0;
    }

    public void insertMatrix(ApprovalMatrix matrix) {
        jdbc.update(
                """
                INSERT INTO approval_matrices (id, code, name, description, status, current_version_id, created_by, updated_by)
                VALUES (:id, :code, :name, :description, :status, :currentVersionId, :createdBy, :updatedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", matrix.id())
                        .addValue("code", matrix.code())
                        .addValue("name", matrix.name())
                        .addValue("description", matrix.description())
                        .addValue("status", matrix.status().name())
                        .addValue("currentVersionId", matrix.currentVersionId())
                        .addValue("createdBy", matrix.createdBy())
                        .addValue("updatedBy", matrix.updatedBy()));
    }

    public void updateMatrix(UUID id, String name, String description, MatrixStatus status, UUID currentVersionId, String updatedBy) {
        jdbc.update(
                """
                UPDATE approval_matrices
                SET name = :name,
                    description = :description,
                    status = :status,
                    current_version_id = :currentVersionId,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("name", name)
                        .addValue("description", description)
                        .addValue("status", status.name())
                        .addValue("currentVersionId", currentVersionId)
                        .addValue("updatedBy", updatedBy));
    }

    public Optional<ApprovalMatrix> findMatrix(UUID id) {
        return one("SELECT " + MATRIX_COLUMNS + " FROM approval_matrices WHERE id = :id",
                new MapSqlParameterSource("id", id), MATRIX);
    }

    public Optional<ApprovalMatrix> findMatrixByCode(String code) {
        return one("SELECT " + MATRIX_COLUMNS + " FROM approval_matrices WHERE code = :code",
                new MapSqlParameterSource("code", code), MATRIX);
    }

    public List<ApprovalMatrix> listMatrices() {
        return jdbc.query("SELECT " + MATRIX_COLUMNS + " FROM approval_matrices ORDER BY code", MATRIX);
    }

    public void deleteMatrix(UUID id) {
        jdbc.update(
                """
                DELETE FROM approval_matrix_levels
                WHERE version_id IN (SELECT id FROM approval_matrix_versions WHERE matrix_id = :id)
                """,
                new MapSqlParameterSource("id", id));
        jdbc.update("UPDATE approval_matrices SET current_version_id = NULL WHERE id = :id", new MapSqlParameterSource("id", id));
        jdbc.update("DELETE FROM approval_matrix_versions WHERE matrix_id = :id", new MapSqlParameterSource("id", id));
        jdbc.update("DELETE FROM approval_matrices WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public int countApprovals(UUID matrixId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM loan_approvals WHERE matrix_id = :id",
                new MapSqlParameterSource("id", matrixId),
                Integer.class);
        return count == null ? 0 : count;
    }

    public void insertVersion(MatrixVersion version) {
        jdbc.update(
                """
                INSERT INTO approval_matrix_versions (
                    id, matrix_id, version_number, status, product_id, customer_segment, branch_id, risk_rating,
                    collateral_status, security_classification, exception_type, automated_allowed, amount_min, amount_max,
                    activated_by, activated_at, created_by)
                VALUES (
                    :id, :matrixId, :versionNumber, :status, :productId, :customerSegment, :branchId, :riskRating,
                    :collateralStatus, :securityClassification, :exceptionType, :automatedAllowed, :amountMin, :amountMax,
                    :activatedBy, :activatedAt, :createdBy)
                """,
                versionParams(version));
    }

    public void updateDraft(MatrixVersion version) {
        jdbc.update(
                """
                UPDATE approval_matrix_versions
                SET product_id = :productId,
                    customer_segment = :customerSegment,
                    branch_id = :branchId,
                    risk_rating = :riskRating,
                    collateral_status = :collateralStatus,
                    security_classification = :securityClassification,
                    exception_type = :exceptionType,
                    automated_allowed = :automatedAllowed,
                    amount_min = :amountMin,
                    amount_max = :amountMax
                WHERE id = :id AND status = 'DRAFT'
                """,
                versionParams(version));
    }

    public void activateVersion(UUID id, String activatedBy, Instant activatedAt) {
        jdbc.update(
                """
                UPDATE approval_matrix_versions
                SET status = 'ACTIVE', activated_by = :activatedBy, activated_at = :activatedAt
                WHERE id = :id AND status = 'DRAFT'
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("activatedBy", activatedBy)
                        .addValue("activatedAt", JdbcTimes.offset(activatedAt)));
    }

    public void supersedeVersion(UUID id) {
        jdbc.update(
                "UPDATE approval_matrix_versions SET status = 'SUPERSEDED' WHERE id = :id AND status = 'ACTIVE'",
                new MapSqlParameterSource("id", id));
    }

    public Optional<MatrixVersion> findVersion(UUID id) {
        return one("SELECT " + VERSION_COLUMNS + " FROM approval_matrix_versions WHERE id = :id",
                new MapSqlParameterSource("id", id), VERSION);
    }

    public List<MatrixVersion> listVersions(UUID matrixId) {
        return jdbc.query(
                "SELECT " + VERSION_COLUMNS + " FROM approval_matrix_versions WHERE matrix_id = :matrixId ORDER BY version_number",
                new MapSqlParameterSource("matrixId", matrixId),
                VERSION);
    }

    public Optional<MatrixVersion> findDraft(UUID matrixId) {
        return one(
                "SELECT " + VERSION_COLUMNS + " FROM approval_matrix_versions WHERE matrix_id = :matrixId AND status = 'DRAFT'",
                new MapSqlParameterSource("matrixId", matrixId),
                VERSION);
    }

    public List<MatrixVersion> listActiveVersions() {
        return jdbc.query(
                """
                SELECT v.id, v.matrix_id, v.version_number, v.status, v.product_id, v.customer_segment, v.branch_id,
                       v.risk_rating, v.collateral_status, v.security_classification, v.exception_type,
                       v.automated_allowed, v.amount_min, v.amount_max, v.activated_by, v.activated_at,
                       v.created_at, v.created_by
                FROM approval_matrix_versions v
                JOIN approval_matrices m ON m.current_version_id = v.id
                WHERE m.status = 'ACTIVE' AND v.status = 'ACTIVE'
                """,
                VERSION);
    }

    public void replaceLevels(UUID versionId, List<ApprovalLevel> levels) {
        jdbc.update("DELETE FROM approval_matrix_levels WHERE version_id = :versionId", new MapSqlParameterSource("versionId", versionId));
        for (ApprovalLevel level : levels) {
            jdbc.update(
                    """
                    INSERT INTO approval_matrix_levels (
                        id, version_id, stage, sequence_no, role_code, min_amount, max_amount, quorum, expiry_hours,
                        manual_required, automated, escalate_on_expiry)
                    VALUES (
                        :id, :versionId, :stage, :sequenceNo, :roleCode, :minAmount, :maxAmount, :quorum, :expiryHours,
                        :manualRequired, :automated, :escalateOnExpiry)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", level.id())
                            .addValue("versionId", level.versionId())
                            .addValue("stage", level.stage().name())
                            .addValue("sequenceNo", level.sequence())
                            .addValue("roleCode", level.roleCode())
                            .addValue("minAmount", level.minAmount())
                            .addValue("maxAmount", level.maxAmount())
                            .addValue("quorum", level.quorum())
                            .addValue("expiryHours", level.expiryHours())
                            .addValue("manualRequired", level.manualRequired())
                            .addValue("automated", level.automated())
                            .addValue("escalateOnExpiry", level.escalateOnExpiry()));
        }
    }

    public List<ApprovalLevel> listLevels(UUID versionId) {
        return jdbc.query(
                "SELECT " + LEVEL_COLUMNS + " FROM approval_matrix_levels WHERE version_id = :versionId ORDER BY sequence_no",
                new MapSqlParameterSource("versionId", versionId),
                LEVEL);
    }

    public void insertException(ApprovalExceptionCase exception) {
        jdbc.update(
                """
                INSERT INTO approval_exceptions (id, approval_id, application_id, exception_type, reason, status, requested_by)
                VALUES (:id, :approvalId, :applicationId, :exceptionType, :reason, :status, :requestedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", exception.id())
                        .addValue("approvalId", exception.approvalId())
                        .addValue("applicationId", exception.applicationId())
                        .addValue("exceptionType", exception.exceptionType())
                        .addValue("reason", exception.reason())
                        .addValue("status", exception.status())
                        .addValue("requestedBy", exception.requestedBy()));
    }

    public void attachException(UUID exceptionId, UUID approvalId) {
        jdbc.update(
                "UPDATE approval_exceptions SET approval_id = :approvalId WHERE id = :id AND status = 'PENDING'",
                new MapSqlParameterSource().addValue("id", exceptionId).addValue("approvalId", approvalId));
    }

    public void decideException(UUID exceptionId, String status, UUID decidedBy, Instant decidedAt) {
        jdbc.update(
                """
                UPDATE approval_exceptions
                SET status = :status, decided_by = :decidedBy, decided_at = :decidedAt
                WHERE id = :id AND status = 'PENDING'
                """,
                new MapSqlParameterSource()
                        .addValue("id", exceptionId)
                        .addValue("status", status)
                        .addValue("decidedBy", decidedBy)
                        .addValue("decidedAt", JdbcTimes.offset(decidedAt)));
    }

    public void insertApproval(LoanApproval approval) {
        jdbc.update(
                """
                INSERT INTO loan_approvals (
                    id, reference, application_id, application_revision, credit_assessment_id, credit_result_id, matrix_id,
                    matrix_version_id, exception_id, amount, currency_code, stage, stage_sequence, status, decision,
                    decision_reason, decided_by, decided_at, expires_at, automated, requested_by, correlation_id, version)
                VALUES (
                    :id, :reference, :applicationId, :applicationRevision, :creditAssessmentId, :creditResultId, :matrixId,
                    :matrixVersionId, :exceptionId, :amount, :currencyCode, :stage, :stageSequence, :status, :decision,
                    :decisionReason, :decidedBy, :decidedAt, :expiresAt, :automated, :requestedBy, :correlationId, :version)
                """,
                approvalParams(approval));
    }

    public Optional<LoanApproval> findApproval(UUID id) {
        return one("SELECT " + APPROVAL_COLUMNS + " FROM loan_approvals WHERE id = :id",
                new MapSqlParameterSource("id", id), APPROVAL);
    }

    public Optional<LoanApproval> lockApproval(UUID id) {
        return one("SELECT " + APPROVAL_COLUMNS + " FROM loan_approvals WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), APPROVAL);
    }

    public Optional<LoanApproval> findOpen(UUID applicationId) {
        return one(
                "SELECT " + APPROVAL_COLUMNS + " FROM loan_approvals WHERE application_id = :applicationId AND status IN ('PENDING', 'INFORMATION_REQUESTED')",
                new MapSqlParameterSource("applicationId", applicationId),
                APPROVAL);
    }

    public List<LoanApproval> lockLive(UUID applicationId) {
        return jdbc.query(
                "SELECT " + APPROVAL_COLUMNS + """
                 FROM loan_approvals
                 WHERE application_id = :applicationId
                   AND status IN ('PENDING', 'INFORMATION_REQUESTED', 'APPROVED')
                 FOR UPDATE
                """,
                new MapSqlParameterSource("applicationId", applicationId),
                APPROVAL);
    }

    public boolean save(LoanApproval approval, int expectedVersion) {
        int updated = jdbc.update(
                """
                UPDATE loan_approvals
                SET stage = :stage,
                    stage_sequence = :stageSequence,
                    status = :status,
                    decision = :decision,
                    decision_reason = :decisionReason,
                    decided_by = :decidedBy,
                    decided_at = :decidedAt,
                    expires_at = :expiresAt,
                    version = version + 1
                WHERE id = :id AND version = :expectedVersion
                """,
                approvalParams(approval).addValue("expectedVersion", expectedVersion));
        return updated == 1;
    }

    public List<LoanApproval> queue(UUID actorId, Set<String> roles, boolean unrestricted, Set<UUID> branches) {
        if (!unrestricted && (branches == null || branches.isEmpty())) {
            return List.of();
        }
        boolean matchRole = roles != null && !roles.isEmpty();
        String roleClause = matchRole
                ? """
                OR (
                    a.stage IN (:roles)
                    AND NOT EXISTS (
                        SELECT 1 FROM loan_approval_assignments s
                        WHERE s.approval_id = a.id AND s.status = 'ASSIGNED' AND s.assignee_id IS NOT NULL
                    )
                )
                """
                : "";
        String branchClause = unrestricted ? "" : " AND app.branch_id IN (:branches) ";
        MapSqlParameterSource params = new MapSqlParameterSource("actorId", actorId);
        if (matchRole) {
            params.addValue("roles", roles);
        }
        if (!unrestricted) {
            params.addValue("branches", branches);
        }
        return jdbc.query(
                """
                SELECT a.id, a.reference, a.application_id, a.application_revision, a.credit_assessment_id, a.credit_result_id,
                       a.matrix_id, a.matrix_version_id, a.exception_id, a.amount, a.currency_code, a.stage, a.stage_sequence,
                       a.status, a.decision, a.decision_reason, a.decided_by, a.decided_at, a.expires_at, a.automated,
                       a.requested_by, a.correlation_id, a.version, a.created_at, a.updated_at
                FROM loan_approvals a
                JOIN loan_applications app ON app.id = a.application_id
                WHERE a.status IN ('PENDING', 'INFORMATION_REQUESTED')
                """
                        + branchClause
                        + """
                AND (
                    EXISTS (
                        SELECT 1 FROM loan_approval_assignments s
                        WHERE s.approval_id = a.id AND s.status = 'ASSIGNED' AND s.assignee_id = :actorId
                    )
                """
                        + roleClause
                        + """
                )
                ORDER BY a.created_at
                LIMIT 200
                """,
                params,
                APPROVAL);
    }

    public void insertAssignment(ApprovalAssignment assignment) {
        jdbc.update(
                """
                INSERT INTO loan_approval_assignments (id, approval_id, stage, assignee_id, assigned_by, status, delegated_limit)
                VALUES (:id, :approvalId, :stage, :assigneeId, :assignedBy, :status, :delegatedLimit)
                """,
                new MapSqlParameterSource()
                        .addValue("id", assignment.id())
                        .addValue("approvalId", assignment.approvalId())
                        .addValue("stage", assignment.stage().name())
                        .addValue("assigneeId", assignment.assigneeId())
                        .addValue("assignedBy", assignment.assignedBy())
                        .addValue("status", assignment.status())
                        .addValue("delegatedLimit", assignment.delegatedLimit()));
    }

    public void closeAssignments(UUID approvalId, String status) {
        jdbc.update(
                """
                UPDATE loan_approval_assignments
                SET status = :status
                WHERE approval_id = :approvalId AND status = 'ASSIGNED'
                """,
                new MapSqlParameterSource().addValue("approvalId", approvalId).addValue("status", status));
    }

    public Optional<UUID> assignee(UUID approvalId) {
        List<UUID> ids = jdbc.query(
                """
                SELECT assignee_id
                FROM loan_approval_assignments
                WHERE approval_id = :approvalId AND status = 'ASSIGNED' AND assignee_id IS NOT NULL
                ORDER BY created_at DESC
                LIMIT 1
                """,
                new MapSqlParameterSource("approvalId", approvalId),
                (rs, rowNum) -> rs.getObject("assignee_id", UUID.class));
        return ids.stream().findFirst();
    }

    public void insertHistory(ApprovalHistoryEntry entry) {
        jdbc.update(
                """
                INSERT INTO loan_approval_history (
                    id, approval_id, actor_id, actor_role, stage, decision, amount, reason, correlation_id, occurred_at)
                VALUES (
                    :id, :approvalId, :actorId, :actorRole, :stage, :decision, :amount, :reason, :correlationId, :occurredAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", entry.id())
                        .addValue("approvalId", entry.approvalId())
                        .addValue("actorId", entry.actorId())
                        .addValue("actorRole", entry.actorRole())
                        .addValue("stage", entry.stage().name())
                        .addValue("decision", entry.decision().name())
                        .addValue("amount", entry.amount())
                        .addValue("reason", entry.reason())
                        .addValue("correlationId", entry.correlationId())
                        .addValue("occurredAt", JdbcTimes.offset(entry.occurredAt())));
    }

    public List<ApprovalHistoryEntry> listHistory(UUID approvalId) {
        return jdbc.query(
                """
                SELECT id, approval_id, actor_id, actor_role, stage, decision, amount, reason, correlation_id, occurred_at
                FROM loan_approval_history
                WHERE approval_id = :approvalId
                ORDER BY occurred_at, id
                """,
                new MapSqlParameterSource("approvalId", approvalId),
                (rs, rowNum) -> new ApprovalHistoryEntry(
                        rs.getObject("id", UUID.class),
                        rs.getObject("approval_id", UUID.class),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_role"),
                        ApprovalStage.valueOf(rs.getString("stage")),
                        ApprovalDecision.valueOf(rs.getString("decision")),
                        rs.getBigDecimal("amount"),
                        rs.getString("reason"),
                        rs.getString("correlation_id"),
                        JdbcTimes.instant(rs, "occurred_at")));
    }

    public boolean voted(UUID approvalId, ApprovalStage stage, UUID actorId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM loan_approval_history
                WHERE approval_id = :approvalId AND stage = :stage AND actor_id = :actorId AND decision = 'APPROVE'
                """,
                new MapSqlParameterSource()
                        .addValue("approvalId", approvalId)
                        .addValue("stage", stage.name())
                        .addValue("actorId", actorId),
                Integer.class);
        return count != null && count > 0;
    }

    public int approvalsAtStage(UUID approvalId, ApprovalStage stage) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(DISTINCT actor_id) FROM loan_approval_history
                WHERE approval_id = :approvalId AND stage = :stage AND decision = 'APPROVE'
                """,
                new MapSqlParameterSource().addValue("approvalId", approvalId).addValue("stage", stage.name()),
                Integer.class);
        return count == null ? 0 : count;
    }

    public void insertDelegation(ApprovalDelegation delegation) {
        jdbc.update(
                """
                INSERT INTO approval_delegations (
                    id, user_id, approval_id, role_code, stage, branch_id, max_amount, currency_code,
                    valid_from, valid_until, active, reason, granted_by)
                VALUES (
                    :id, :userId, :approvalId, :roleCode, :stage, :branchId, :maxAmount, :currencyCode,
                    :validFrom, :validUntil, :active, :reason, :grantedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", delegation.id())
                        .addValue("userId", delegation.userId())
                        .addValue("approvalId", delegation.approvalId())
                        .addValue("roleCode", delegation.roleCode())
                        .addValue("stage", delegation.stage().name())
                        .addValue("branchId", delegation.branchId())
                        .addValue("maxAmount", delegation.maxAmount())
                        .addValue("currencyCode", delegation.currencyCode())
                        .addValue("validFrom", JdbcTimes.offset(delegation.validFrom()))
                        .addValue("validUntil", JdbcTimes.offset(delegation.validUntil()))
                        .addValue("active", delegation.active())
                        .addValue("reason", delegation.reason())
                        .addValue("grantedBy", delegation.grantedBy()));
    }

    public Optional<ApprovalDelegation> findDelegation(UUID id) {
        return one(
                """
                SELECT id, user_id, approval_id, role_code, stage, branch_id, max_amount, currency_code,
                       valid_from, valid_until, active, reason, granted_by, created_at
                FROM approval_delegations WHERE id = :id
                """,
                new MapSqlParameterSource("id", id),
                DELEGATION);
    }

    public List<ApprovalDelegation> listDelegations(UUID userId) {
        String where = userId == null ? "" : " WHERE user_id = :userId ";
        return jdbc.query(
                """
                SELECT id, user_id, approval_id, role_code, stage, branch_id, max_amount, currency_code,
                       valid_from, valid_until, active, reason, granted_by, created_at
                FROM approval_delegations
                """
                        + where
                        + " ORDER BY created_at DESC",
                new MapSqlParameterSource("userId", userId),
                DELEGATION);
    }

    public boolean revokeDelegation(UUID id) {
        return jdbc.update(
                "UPDATE approval_delegations SET active = FALSE WHERE id = :id AND active = TRUE",
                new MapSqlParameterSource("id", id)) == 1;
    }

    public BigDecimal delegatedLimit(UUID userId, UUID approvalId, ApprovalStage stage, String role, UUID branchId, Instant now) {
        return jdbc.queryForObject(
                """
                SELECT MAX(max_amount)
                FROM approval_delegations
                WHERE user_id = :userId
                  AND active = TRUE
                  AND stage = :stage
                  AND role_code = :role
                  AND currency_code = 'NGN'
                  AND valid_from <= :now
                  AND (valid_until IS NULL OR valid_until > :now)
                  AND (branch_id IS NULL OR branch_id = :branchId)
                  AND (approval_id IS NULL OR approval_id = :approvalId)
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("approvalId", approvalId)
                        .addValue("stage", stage.name())
                        .addValue("role", role)
                        .addValue("branchId", branchId)
                        .addValue("now", JdbcTimes.offset(now)),
                BigDecimal.class);
    }

    public boolean productExists(UUID id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM loan_products WHERE id = :id",
                new MapSqlParameterSource("id", id),
                Integer.class);
        return count != null && count > 0;
    }

    public boolean branchExists(UUID id) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM branches WHERE id = :id",
                new MapSqlParameterSource("id", id),
                Integer.class);
        return count != null && count > 0;
    }

    public Optional<String> emailOf(UUID userId) {
        List<String> emails = jdbc.query(
                "SELECT email FROM system_users WHERE id = :id",
                new MapSqlParameterSource("id", userId),
                (rs, rowNum) -> rs.getString("email"));
        return emails.stream().findFirst();
    }

    public boolean roleExists(String code) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM roles WHERE code = :code",
                new MapSqlParameterSource("code", code),
                Integer.class);
        return count != null && count > 0;
    }

    public boolean userHasRole(UUID userId, String role) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = :userId AND r.code = :role
                """,
                new MapSqlParameterSource().addValue("userId", userId).addValue("role", role),
                Integer.class);
        return count != null && count > 0;
    }

    public boolean userCoversBranch(UUID userId, UUID branchId) {
        Boolean unrestricted = jdbc.queryForObject(
                """
                SELECT COALESCE(bool_or(r.unrestricted_branches), FALSE)
                FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = :userId
                """,
                new MapSqlParameterSource("userId", userId),
                Boolean.class);
        if (Boolean.TRUE.equals(unrestricted)) {
            return true;
        }
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_branches WHERE user_id = :userId AND branch_id = :branchId",
                new MapSqlParameterSource().addValue("userId", userId).addValue("branchId", branchId),
                Integer.class);
        return count != null && count > 0;
    }

    public Optional<ProductGate> productGate(UUID versionId) {
        List<ProductGate> rows = jdbc.query(
                """
                SELECT p.category, v.automated_approval_allowed
                FROM loan_product_versions v
                JOIN loan_products p ON p.id = v.product_id
                WHERE v.id = :id
                """,
                new MapSqlParameterSource("id", versionId),
                (rs, rowNum) -> new ProductGate(rs.getString("category"), rs.getBoolean("automated_approval_allowed")));
        return rows.stream().findFirst();
    }

    public List<PledgeFact> pledges(UUID applicationId) {
        return jdbc.query(
                """
                SELECT c.verification_status, lc.allocated_amount
                FROM loan_collaterals lc
                JOIN collaterals c ON c.id = lc.collateral_id
                WHERE lc.application_id = :applicationId AND lc.status = 'ACTIVE'
                """,
                new MapSqlParameterSource("applicationId", applicationId),
                (rs, rowNum) -> new PledgeFact(rs.getString("verification_status"), rs.getBigDecimal("allocated_amount")));
    }

    private MapSqlParameterSource versionParams(MatrixVersion version) {
        return new MapSqlParameterSource()
                .addValue("id", version.id())
                .addValue("matrixId", version.matrixId())
                .addValue("versionNumber", version.versionNumber())
                .addValue("status", version.status().name())
                .addValue("productId", version.productId())
                .addValue("customerSegment", version.customerSegment())
                .addValue("branchId", version.branchId())
                .addValue("riskRating", version.riskRating())
                .addValue("collateralStatus", version.collateralStatus())
                .addValue("securityClassification", version.securityClassification())
                .addValue("exceptionType", version.exceptionType())
                .addValue("automatedAllowed", version.automatedAllowed())
                .addValue("amountMin", version.amountMin())
                .addValue("amountMax", version.amountMax())
                .addValue("activatedBy", version.activatedBy())
                .addValue("activatedAt", JdbcTimes.offset(version.activatedAt()))
                .addValue("createdBy", version.createdBy());
    }

    private MapSqlParameterSource approvalParams(LoanApproval approval) {
        return new MapSqlParameterSource()
                .addValue("id", approval.id())
                .addValue("reference", approval.reference())
                .addValue("applicationId", approval.applicationId())
                .addValue("applicationRevision", approval.applicationRevision())
                .addValue("creditAssessmentId", approval.creditAssessmentId())
                .addValue("creditResultId", approval.creditResultId())
                .addValue("matrixId", approval.matrixId())
                .addValue("matrixVersionId", approval.matrixVersionId())
                .addValue("exceptionId", approval.exceptionId())
                .addValue("amount", approval.amount())
                .addValue("currencyCode", approval.currencyCode())
                .addValue("stage", approval.stage().name())
                .addValue("stageSequence", approval.stageSequence())
                .addValue("status", approval.status().name())
                .addValue("decision", approval.decision())
                .addValue("decisionReason", approval.decisionReason())
                .addValue("decidedBy", approval.decidedBy())
                .addValue("decidedAt", JdbcTimes.offset(approval.decidedAt()))
                .addValue("expiresAt", JdbcTimes.offset(approval.expiresAt()))
                .addValue("automated", approval.automated())
                .addValue("requestedBy", approval.requestedBy())
                .addValue("correlationId", approval.correlationId())
                .addValue("version", approval.version());
    }

    private <T> Optional<T> one(String sql, MapSqlParameterSource params, RowMapper<T> mapper) {
        return jdbc.query(sql, params, mapper).stream().findFirst();
    }

    private static final RowMapper<ApprovalMatrix> MATRIX = (rs, rowNum) -> new ApprovalMatrix(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            MatrixStatus.valueOf(rs.getString("status")),
            rs.getObject("current_version_id", UUID.class),
            JdbcTimes.instant(rs, "created_at"),
            JdbcTimes.instant(rs, "updated_at"),
            rs.getString("created_by"),
            rs.getString("updated_by"));

    private static final RowMapper<MatrixVersion> VERSION = (rs, rowNum) -> new MatrixVersion(
            rs.getObject("id", UUID.class),
            rs.getObject("matrix_id", UUID.class),
            rs.getInt("version_number"),
            MatrixStatus.valueOf(rs.getString("status")),
            rs.getObject("product_id", UUID.class),
            rs.getString("customer_segment"),
            rs.getObject("branch_id", UUID.class),
            rs.getString("risk_rating"),
            rs.getString("collateral_status"),
            rs.getString("security_classification"),
            rs.getString("exception_type"),
            rs.getBoolean("automated_allowed"),
            rs.getBigDecimal("amount_min"),
            rs.getBigDecimal("amount_max"),
            rs.getString("activated_by"),
            JdbcTimes.instant(rs, "activated_at"),
            JdbcTimes.instant(rs, "created_at"),
            rs.getString("created_by"));

    private static final RowMapper<ApprovalLevel> LEVEL = (rs, rowNum) -> new ApprovalLevel(
            rs.getObject("id", UUID.class),
            rs.getObject("version_id", UUID.class),
            ApprovalStage.valueOf(rs.getString("stage")),
            rs.getInt("sequence_no"),
            rs.getString("role_code"),
            rs.getBigDecimal("min_amount"),
            rs.getBigDecimal("max_amount"),
            rs.getInt("quorum"),
            rs.getInt("expiry_hours"),
            rs.getBoolean("manual_required"),
            rs.getBoolean("automated"),
            rs.getBoolean("escalate_on_expiry"));

    private static final RowMapper<LoanApproval> APPROVAL = (rs, rowNum) -> new LoanApproval(
            rs.getObject("id", UUID.class),
            rs.getString("reference"),
            rs.getObject("application_id", UUID.class),
            rs.getInt("application_revision"),
            rs.getObject("credit_assessment_id", UUID.class),
            rs.getObject("credit_result_id", UUID.class),
            rs.getObject("matrix_id", UUID.class),
            rs.getObject("matrix_version_id", UUID.class),
            rs.getObject("exception_id", UUID.class),
            rs.getBigDecimal("amount"),
            rs.getString("currency_code"),
            ApprovalStage.valueOf(rs.getString("stage")),
            rs.getInt("stage_sequence"),
            ApprovalStatus.valueOf(rs.getString("status")),
            rs.getString("decision"),
            rs.getString("decision_reason"),
            rs.getObject("decided_by", UUID.class),
            JdbcTimes.instant(rs, "decided_at"),
            JdbcTimes.instant(rs, "expires_at"),
            rs.getBoolean("automated"),
            rs.getObject("requested_by", UUID.class),
            rs.getString("correlation_id"),
            rs.getInt("version"),
            JdbcTimes.instant(rs, "created_at"),
            JdbcTimes.instant(rs, "updated_at"));

    private static final RowMapper<ApprovalDelegation> DELEGATION = (rs, rowNum) -> new ApprovalDelegation(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("approval_id", UUID.class),
            rs.getString("role_code"),
            ApprovalStage.valueOf(rs.getString("stage")),
            rs.getObject("branch_id", UUID.class),
            rs.getBigDecimal("max_amount"),
            rs.getString("currency_code"),
            JdbcTimes.instant(rs, "valid_from"),
            JdbcTimes.instant(rs, "valid_until"),
            rs.getBoolean("active"),
            rs.getString("reason"),
            rs.getObject("granted_by", UUID.class),
            JdbcTimes.instant(rs, "created_at"));

    public record ProductGate(String category, boolean automatedApprovalAllowed) {
    }

    public record PledgeFact(String verificationStatus, BigDecimal allocatedAmount) {
    }
}
