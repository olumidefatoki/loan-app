package com.mfb.lending.module.auth.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.auth.domain.RoleRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RoleRepository {

    private static final RowMapper<RoleRecord> MAPPER = (rs, rowNum) -> new RoleRecord(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("description"),
            rs.getBoolean("requires_mfa"),
            rs.getBoolean("unrestricted_branches"),
            rs.getBoolean("system_role"));

    private final NamedParameterJdbcTemplate jdbc;

    public RoleRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(RoleRecord role) {
        jdbc.update(
                """
                INSERT INTO roles (id, code, name, description, requires_mfa, unrestricted_branches, system_role)
                VALUES (:id, :code, :name, :description, :requiresMfa, :unrestrictedBranches, :systemRole)
                """,
                params(role));
    }

    public void update(RoleRecord role) {
        jdbc.update(
                """
                UPDATE roles
                SET name = :name,
                    description = :description,
                    requires_mfa = :requiresMfa,
                    unrestricted_branches = :unrestrictedBranches
                WHERE id = :id
                """,
                params(role));
    }

    public Optional<RoleRecord> findById(UUID id) {
        return one("SELECT * FROM roles WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public Optional<RoleRecord> findByCode(String code) {
        return one("SELECT * FROM roles WHERE code = :code", new MapSqlParameterSource("code", code));
    }

    public List<RoleRecord> findByCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return jdbc.query("SELECT * FROM roles WHERE code IN (:codes) ORDER BY code", new MapSqlParameterSource("codes", codes), MAPPER);
    }

    public List<RoleRecord> findAll() {
        return jdbc.query("SELECT * FROM roles ORDER BY code", MAPPER);
    }

    public List<String> permissionCodes(UUID roleId) {
        return jdbc.query(
                """
                SELECT p.code
                FROM role_permissions rp
                JOIN permissions p ON p.id = rp.permission_id
                WHERE rp.role_id = :roleId
                ORDER BY p.code
                """,
                new MapSqlParameterSource("roleId", roleId),
                (rs, rowNum) -> rs.getString("code"));
    }

    public void replacePermissions(UUID roleId, Collection<UUID> permissionIds) {
        jdbc.update("DELETE FROM role_permissions WHERE role_id = :roleId", new MapSqlParameterSource("roleId", roleId));
        for (UUID permissionId : permissionIds) {
            jdbc.update(
                    "INSERT INTO role_permissions (role_id, permission_id) VALUES (:roleId, :permissionId)",
                    new MapSqlParameterSource().addValue("roleId", roleId).addValue("permissionId", permissionId));
        }
    }

    public boolean assignedToAnyUser(UUID roleId) {
        List<Integer> rows = jdbc.query(
                "SELECT 1 FROM user_roles WHERE role_id = :roleId LIMIT 1",
                new MapSqlParameterSource("roleId", roleId),
                (rs, rowNum) -> 1);
        return !rows.isEmpty();
    }

    public void delete(UUID roleId) {
        jdbc.update("DELETE FROM roles WHERE id = :id", new MapSqlParameterSource("id", roleId));
    }

    public void replaceUserRoles(UUID userId, Collection<UUID> roleIds, String assignedBy) {
        jdbc.update("DELETE FROM user_roles WHERE user_id = :userId", new MapSqlParameterSource("userId", userId));
        for (UUID roleId : roleIds) {
            jdbc.update(
                    """
                    INSERT INTO user_roles (user_id, role_id, assigned_by)
                    VALUES (:userId, :roleId, :assignedBy)
                    """,
                    new MapSqlParameterSource()
                            .addValue("userId", userId)
                            .addValue("roleId", roleId)
                            .addValue("assignedBy", assignedBy));
        }
    }

    public void assignRole(UUID userId, UUID roleId, String assignedBy) {
        jdbc.update(
                """
                INSERT INTO user_roles (user_id, role_id, assigned_by)
                VALUES (:userId, :roleId, :assignedBy)
                ON CONFLICT (user_id, role_id) DO NOTHING
                """,
                new MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("roleId", roleId)
                        .addValue("assignedBy", assignedBy));
    }

    private Optional<RoleRecord> one(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, MAPPER).stream().findFirst();
    }

    private static MapSqlParameterSource params(RoleRecord role) {
        return new MapSqlParameterSource()
                .addValue("id", role.id())
                .addValue("code", role.code())
                .addValue("name", role.name())
                .addValue("description", role.description())
                .addValue("requiresMfa", role.requiresMfa())
                .addValue("unrestrictedBranches", role.unrestrictedBranches())
                .addValue("systemRole", role.systemRole());
    }
}
