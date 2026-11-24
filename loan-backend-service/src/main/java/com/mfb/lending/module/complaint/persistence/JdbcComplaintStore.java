package com.mfb.lending.module.complaint.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.module.complaint.domain.ComplaintModels.Complaint;
import com.mfb.lending.module.complaint.domain.ComplaintModels.ComplaintActivity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcComplaintStore implements ComplaintStore {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcComplaintStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Complaint insert(Complaint complaint) {
        jdbc.update(
                """
                INSERT INTO complaints (
                    id, reference, customer_id, branch_id, category, status, subject, description, assignee_id, sla_due_at, created_at)
                VALUES (
                    :id, :reference, :customerId, :branchId, :category, :status, :subject, :description, :assigneeId, :slaDueAt, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", complaint.id())
                        .addValue("reference", complaint.reference())
                        .addValue("customerId", complaint.customerId())
                        .addValue("branchId", complaint.branchId())
                        .addValue("category", complaint.category())
                        .addValue("status", complaint.status())
                        .addValue("subject", complaint.subject())
                        .addValue("description", complaint.description())
                        .addValue("assigneeId", complaint.assigneeId())
                        .addValue("slaDueAt", offset(complaint.slaDueAt()))
                        .addValue("createdAt", offset(complaint.createdAt())));
        return complaint;
    }

    @Override
    public Optional<Complaint> find(UUID id) {
        return jdbc.query("SELECT * FROM complaints WHERE id = :id", new MapSqlParameterSource("id", id), (rs, row) -> complaint(rs))
                .stream().findFirst();
    }

    @Override
    public long count(UUID customerId, Set<UUID> branchIds, boolean unrestricted, String status) {
        if (blocked(customerId, branchIds, unrestricted)) {
            return 0;
        }
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM complaints WHERE " + filter(customerId, unrestricted),
                params(customerId, branchIds, status),
                Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public List<Complaint> list(UUID customerId, Set<UUID> branchIds, boolean unrestricted, String status, int limit, int offset) {
        if (blocked(customerId, branchIds, unrestricted)) {
            return List.of();
        }
        return jdbc.query(
                "SELECT * FROM complaints WHERE " + filter(customerId, unrestricted) + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset",
                params(customerId, branchIds, status).addValue("limit", limit).addValue("offset", offset),
                (rs, row) -> complaint(rs));
    }

    @Override
    public List<ComplaintActivity> activities(UUID complaintId) {
        return jdbc.query(
                "SELECT * FROM complaint_activities WHERE complaint_id = :id ORDER BY created_at",
                new MapSqlParameterSource("id", complaintId),
                (rs, row) -> activity(rs));
    }

    @Override
    public void insertActivity(ComplaintActivity activity) {
        jdbc.update(
                """
                INSERT INTO complaint_activities (id, complaint_id, activity_type, note, actor_id, actor_name, created_at)
                VALUES (:id, :complaintId, :activityType, :note, :actorId, :actorName, :createdAt)
                """,
                new MapSqlParameterSource()
                        .addValue("id", activity.id())
                        .addValue("complaintId", activity.complaintId())
                        .addValue("activityType", activity.activityType())
                        .addValue("note", activity.note())
                        .addValue("actorId", activity.actorId())
                        .addValue("actorName", activity.actorName())
                        .addValue("createdAt", offset(activity.createdAt())));
    }

    @Override
    public void assign(UUID id, UUID assigneeId) {
        jdbc.update(
                "UPDATE complaints SET assignee_id = :assigneeId, status = 'ASSIGNED' WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("assigneeId", assigneeId));
    }

    @Override
    public void escalate(UUID id, Instant escalatedAt) {
        jdbc.update(
                """
                UPDATE complaints SET status = 'ESCALATED', escalated_at = :escalatedAt
                WHERE id = :id AND status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS')
                """,
                new MapSqlParameterSource().addValue("id", id).addValue("escalatedAt", offset(escalatedAt)));
    }

    @Override
    public void resolve(UUID id, String resolution, Instant resolvedAt) {
        jdbc.update(
                """
                UPDATE complaints SET status = 'RESOLVED', resolution = :resolution, resolved_at = :resolvedAt
                WHERE id = :id AND status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ESCALATED')
                """,
                new MapSqlParameterSource().addValue("id", id).addValue("resolution", resolution).addValue("resolvedAt", offset(resolvedAt)));
    }

    @Override
    public List<Complaint> breached(Instant now) {
        return jdbc.query(
                """
                SELECT * FROM complaints
                WHERE status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS') AND sla_due_at < :now
                ORDER BY sla_due_at
                """,
                new MapSqlParameterSource("now", offset(now)),
                (rs, row) -> complaint(rs));
    }

    @Override
    public long breachedCount(Instant now) {
        Long count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM complaints
                WHERE status IN ('OPEN', 'ASSIGNED', 'IN_PROGRESS', 'ESCALATED') AND sla_due_at < :now
                """,
                new MapSqlParameterSource("now", offset(now)),
                Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public String nextReference() {
        Long value = jdbc.queryForObject("SELECT nextval('complaint_reference_seq')", new MapSqlParameterSource(), Long.class);
        return "CMP" + String.format("%08d", value == null ? 0 : value);
    }

    @Override
    public boolean officerCanManage(UUID userId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM system_users u
                JOIN user_roles ur ON ur.user_id = u.id
                JOIN role_permissions rp ON rp.role_id = ur.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE u.id = :id AND u.principal_type = 'STAFF' AND u.status = 'ACTIVE' AND p.code = 'COMPLAINT_MANAGE'
                """,
                new MapSqlParameterSource("id", userId),
                Integer.class);
        return count != null && count > 0;
    }

    private static boolean blocked(UUID customerId, Set<UUID> branchIds, boolean unrestricted) {
        return customerId == null && !unrestricted && (branchIds == null || branchIds.isEmpty());
    }

    private static String filter(UUID customerId, boolean unrestricted) {
        if (customerId != null) {
            return "customer_id = :customerId AND (CAST(:status AS TEXT) IS NULL OR status = CAST(:status AS TEXT))";
        }
        if (unrestricted) {
            return "(CAST(:status AS TEXT) IS NULL OR status = CAST(:status AS TEXT))";
        }
        return "branch_id IN (:branchIds) AND (CAST(:status AS TEXT) IS NULL OR status = CAST(:status AS TEXT))";
    }

    private static MapSqlParameterSource params(UUID customerId, Set<UUID> branchIds, String status) {
        return new MapSqlParameterSource()
                .addValue("customerId", customerId)
                .addValue("branchIds", branchIds == null || branchIds.isEmpty() ? List.of(UUID.fromString("00000000-0000-0000-0000-000000000000")) : branchIds)
                .addValue("status", status);
    }

    private static Complaint complaint(ResultSet rs) throws SQLException {
        return new Complaint(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                rs.getString("category"),
                rs.getString("status"),
                rs.getString("subject"),
                rs.getString("description"),
                rs.getObject("assignee_id", UUID.class),
                instant(rs, "sla_due_at"),
                instant(rs, "escalated_at"),
                instant(rs, "resolved_at"),
                rs.getString("resolution"),
                instant(rs, "created_at"));
    }

    private static ComplaintActivity activity(ResultSet rs) throws SQLException {
        return new ComplaintActivity(
                rs.getObject("id", UUID.class),
                rs.getObject("complaint_id", UUID.class),
                rs.getString("activity_type"),
                rs.getString("note"),
                rs.getObject("actor_id", UUID.class),
                rs.getString("actor_name"),
                instant(rs, "created_at"));
    }

    private static OffsetDateTime offset(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
