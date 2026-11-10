package com.mfb.lending.module.auth.persistence;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.module.auth.domain.SecurityProfile;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SecurityProfileRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public SecurityProfileRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SecurityProfile load(UUID userId) {
        MapSqlParameterSource params = new MapSqlParameterSource("userId", userId);
        List<RoleRow> roles = jdbc.query(
                """
                SELECT r.code, r.requires_mfa, r.unrestricted_branches
                FROM user_roles ur
                JOIN roles r ON r.id = ur.role_id
                WHERE ur.user_id = :userId
                ORDER BY r.code
                """,
                params,
                (rs, rowNum) -> new RoleRow(
                        rs.getString("code"),
                        rs.getBoolean("requires_mfa"),
                        rs.getBoolean("unrestricted_branches")));
        List<String> permissions = jdbc.query(
                """
                SELECT DISTINCT p.code
                FROM user_roles ur
                JOIN role_permissions rp ON rp.role_id = ur.role_id
                JOIN permissions p ON p.id = rp.permission_id
                WHERE ur.user_id = :userId
                ORDER BY p.code
                """,
                params,
                (rs, rowNum) -> rs.getString("code"));
        List<UUID> branches = jdbc.query(
                "SELECT branch_id FROM user_branches WHERE user_id = :userId ORDER BY primary_branch DESC, assigned_at",
                params,
                (rs, rowNum) -> rs.getObject("branch_id", UUID.class));
        return new SecurityProfile(
                roles.stream().map(RoleRow::code).toList(),
                permissions,
                branches,
                roles.stream().anyMatch(RoleRow::requiresMfa),
                roles.stream().anyMatch(RoleRow::unrestrictedBranches));
    }

    public List<String> permissionCodesForRoles(List<UUID> roleIds) {
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT DISTINCT p.code
                FROM role_permissions rp
                JOIN permissions p ON p.id = rp.permission_id
                WHERE rp.role_id IN (:roleIds)
                ORDER BY p.code
                """,
                new MapSqlParameterSource("roleIds", roleIds),
                (rs, rowNum) -> rs.getString("code"));
    }

    private record RoleRow(String code, boolean requiresMfa, boolean unrestrictedBranches) {
    }
}
