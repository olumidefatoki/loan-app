package com.mfb.lending.module.auth.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.auth.domain.PermissionRecord;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PermissionRepository {

    private static final RowMapper<PermissionRecord> MAPPER = (rs, rowNum) -> new PermissionRecord(
            rs.getObject("id", UUID.class),
            rs.getString("code"),
            rs.getString("description"),
            rs.getBoolean("system_permission"));

    private final NamedParameterJdbcTemplate jdbc;

    public PermissionRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(PermissionRecord permission) {
        jdbc.update(
                """
                INSERT INTO permissions (id, code, description, system_permission)
                VALUES (:id, :code, :description, :systemPermission)
                """,
                params(permission));
    }

    public void updateDescription(UUID id, String description) {
        jdbc.update(
                "UPDATE permissions SET description = :description WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("description", description));
    }

    public Optional<PermissionRecord> findById(UUID id) {
        return jdbc.query("SELECT * FROM permissions WHERE id = :id", new MapSqlParameterSource("id", id), MAPPER)
                .stream()
                .findFirst();
    }

    public Optional<PermissionRecord> findByCode(String code) {
        return jdbc.query("SELECT * FROM permissions WHERE code = :code", new MapSqlParameterSource("code", code), MAPPER)
                .stream()
                .findFirst();
    }

    public List<PermissionRecord> findByCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                "SELECT * FROM permissions WHERE code IN (:codes) ORDER BY code",
                new MapSqlParameterSource("codes", codes),
                MAPPER);
    }

    public List<PermissionRecord> findAll() {
        return jdbc.query("SELECT * FROM permissions ORDER BY code", MAPPER);
    }

    public boolean assignedToAnyRole(UUID permissionId) {
        return !jdbc.query(
                "SELECT 1 FROM role_permissions WHERE permission_id = :id LIMIT 1",
                new MapSqlParameterSource("id", permissionId),
                (rs, rowNum) -> 1).isEmpty();
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM permissions WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    private static MapSqlParameterSource params(PermissionRecord permission) {
        return new MapSqlParameterSource()
                .addValue("id", permission.id())
                .addValue("code", permission.code())
                .addValue("description", permission.description())
                .addValue("systemPermission", permission.systemPermission());
    }
}
