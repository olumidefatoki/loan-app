package com.mfb.lending.module.notification.persistence;

import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.notification.application.ContactDirectory;
import com.mfb.lending.module.notification.domain.NotificationModels.Contact;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcContactDirectory implements ContactDirectory {

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcContactDirectory(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Contact> find(UUID customerId) {
        if (customerId == null) {
            return Optional.empty();
        }
        return jdbc.query(
                "SELECT email, phone_number FROM customers WHERE id = :id",
                new MapSqlParameterSource("id", customerId),
                (rs, row) -> new Contact(rs.getString("email"), rs.getString("phone_number"), null))
                .stream()
                .findFirst();
    }
}
