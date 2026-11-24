package com.mfb.lending.module.audit.api;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.api.ApiResponse;
import com.mfb.lending.common.api.PageBounds;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/audit-logs")
@Tag(name = "Audit", description = "Append-only, hash-chained audit trail. Passwords, tokens, and raw identity numbers are not stored.")
@SecurityRequirement(name = "bearerAuth")
public class AuditLogController {

    private final AuditQueryService audits;

    public AuditLogController(AuditQueryService audits) {
        this.audits = audits;
    }

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "List audit records and report whether the hash chain is intact")
    public ApiResponse<AuditPage> list(
            @AuthenticationPrincipal AuthenticatedUser actor,
            @RequestParam(required = false) String module,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String entityType,
            @RequestParam(required = false) String entityId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(audits.list(actor, module, action, entityType, entityId, page, size));
    }

    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    @Operation(summary = "Get one audit record")
    public ApiResponse<AuditLogResponse> get(@AuthenticationPrincipal AuthenticatedUser actor, @PathVariable UUID id) {
        return ApiResponse.success(audits.get(actor, id));
    }
}

@Service
class AuditQueryService {

    private final NamedParameterJdbcTemplate jdbc;

    AuditQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    AuditPage list(AuthenticatedUser actor, String module, String action, String entityType, String entityId, int page, int size) {
        require(actor);
        PageBounds bounds = PageBounds.of(page, size);
        MapSqlParameterSource params = params(module, action, entityType, entityId).addValue("limit", bounds.size()).addValue("offset", bounds.offset());
        List<AuditLogResponse> items = jdbc.query(
                "SELECT * FROM audit_logs WHERE " + filter() + " ORDER BY chain_seq DESC LIMIT :limit OFFSET :offset",
                params,
                (rs, row) -> response(rs));
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE " + filter(), params, Long.class);
        Boolean intact = jdbc.queryForObject("SELECT audit_chain_intact()", new MapSqlParameterSource(), Boolean.class);
        return new AuditPage(items, bounds.page(), bounds.size(), total == null ? 0 : total, Boolean.TRUE.equals(intact));
    }

    AuditLogResponse get(AuthenticatedUser actor, UUID id) {
        require(actor);
        List<AuditLogResponse> rows = jdbc.query(
                "SELECT * FROM audit_logs WHERE id = :id",
                new MapSqlParameterSource("id", id),
                (rs, row) -> response(rs));
        if (rows.isEmpty()) {
            throw new ResourceNotFoundException("Audit log");
        }
        return rows.get(0);
    }

    private static void require(AuthenticatedUser actor) {
        if (actor.getPrincipalType() != PrincipalType.STAFF || !actor.permissions().contains(PermissionCodes.AUDIT_VIEW)) {
            throw new ForbiddenException("You do not have permission to view audit logs");
        }
    }

    private static String filter() {
        return """
                (CAST(:module AS TEXT) IS NULL OR module = CAST(:module AS TEXT))
                AND (CAST(:action AS TEXT) IS NULL OR action = CAST(:action AS TEXT))
                AND (CAST(:entityType AS TEXT) IS NULL OR entity_type = CAST(:entityType AS TEXT))
                AND (CAST(:entityId AS TEXT) IS NULL OR entity_id = CAST(:entityId AS TEXT))
                """;
    }

    private static MapSqlParameterSource params(String module, String action, String entityType, String entityId) {
        return new MapSqlParameterSource()
                .addValue("module", blank(module))
                .addValue("action", blank(action))
                .addValue("entityType", blank(entityType))
                .addValue("entityId", blank(entityId));
    }

    private static AuditLogResponse response(ResultSet rs) throws SQLException {
        return new AuditLogResponse(
                rs.getObject("id", UUID.class),
                rs.getString("correlation_id"),
                rs.getObject("actor_user_id", UUID.class),
                rs.getString("actor"),
                rs.getString("actor_role"),
                rs.getString("action"),
                rs.getString("module"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("outcome"),
                rs.getString("ip_address"),
                rs.getString("user_agent"),
                rs.getLong("chain_seq"),
                trim(rs.getString("previous_hash")),
                trim(rs.getString("record_hash")),
                instant(rs, "occurred_at"));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        OffsetDateTime value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}

record AuditLogResponse(
        UUID id,
        String correlationId,
        UUID actorUserId,
        String actor,
        String actorRole,
        String action,
        String module,
        String entityType,
        String entityId,
        String outcome,
        String ipAddress,
        String userAgent,
        long chainSeq,
        String previousHash,
        String recordHash,
        Instant occurredAt) {
}

record AuditPage(List<AuditLogResponse> items, int page, int size, long total, boolean chainIntact) {
}
