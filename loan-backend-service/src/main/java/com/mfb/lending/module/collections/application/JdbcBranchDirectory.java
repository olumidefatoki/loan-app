package com.mfb.lending.module.collections.application;

import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcBranchDirectory implements BranchDirectory {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcBranchDirectory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean exists(UUID branchId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM branches WHERE id = :id AND status = 'ACTIVE'",
                new MapSqlParameterSource("id", branchId),
                Integer.class);
        return count != null && count == 1;
    }
}
