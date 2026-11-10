package com.mfb.lending.module.auth.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class BranchAssignmentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public BranchAssignmentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<UUID> findActiveBranchIds(Collection<UUID> branchIds) {
        if (branchIds.isEmpty()) {
            return List.of();
        }
        return jdbc.query(
                "SELECT id FROM branches WHERE id IN (:ids) AND status = 'ACTIVE'",
                new MapSqlParameterSource("ids", branchIds),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
    }

    public void replace(UUID userId, Collection<UUID> branchIds, UUID primaryBranchId) {
        jdbc.update("DELETE FROM user_branches WHERE user_id = :userId", new MapSqlParameterSource("userId", userId));
        for (UUID branchId : branchIds) {
            jdbc.update(
                    """
                    INSERT INTO user_branches (user_id, branch_id, primary_branch)
                    VALUES (:userId, :branchId, :primaryBranch)
                    """,
                    new MapSqlParameterSource()
                            .addValue("userId", userId)
                            .addValue("branchId", branchId)
                            .addValue("primaryBranch", branchId.equals(primaryBranchId)));
        }
    }
}
