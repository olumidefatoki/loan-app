package com.mfb.lending.module.customer.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.customer.domain.DocumentRecord;
import com.mfb.lending.module.customer.domain.DocumentType;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerDocumentRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerDocumentRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(DocumentRecord document, String uploadedBy) {
        jdbc.update(
                """
                INSERT INTO customer_documents (
                    id, customer_id, document_type, file_name, content_type, size_bytes, checksum_sha256, storage_key, uploaded_by)
                VALUES (
                    :id, :customerId, :documentType, :fileName, :contentType, :sizeBytes, :checksum, :storageKey, :uploadedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", document.id())
                        .addValue("customerId", document.customerId())
                        .addValue("documentType", document.type().name())
                        .addValue("fileName", document.fileName())
                        .addValue("contentType", document.contentType())
                        .addValue("sizeBytes", document.sizeBytes())
                        .addValue("checksum", document.checksumSha256())
                        .addValue("storageKey", document.storageKey())
                        .addValue("uploadedBy", uploadedBy));
    }

    public List<DocumentRecord> list(UUID customerId) {
        return jdbc.query(select() + " WHERE customer_id = :customerId ORDER BY created_at DESC",
                new MapSqlParameterSource("customerId", customerId),
                this::map);
    }

    public Optional<DocumentRecord> find(UUID customerId, UUID documentId) {
        return jdbc.query(
                select() + " WHERE customer_id = :customerId AND id = :id",
                new MapSqlParameterSource().addValue("customerId", customerId).addValue("id", documentId),
                this::map).stream().findFirst();
    }

    public List<DocumentType> types(UUID customerId) {
        return jdbc.query(
                "SELECT DISTINCT document_type FROM customer_documents WHERE customer_id = :customerId",
                new MapSqlParameterSource("customerId", customerId),
                (rs, rowNum) -> DocumentType.valueOf(rs.getString("document_type")));
    }

    private static String select() {
        return """
                SELECT id, customer_id, document_type, file_name, content_type, size_bytes, checksum_sha256, storage_key, created_at
                FROM customer_documents
                """;
    }

    private DocumentRecord map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new DocumentRecord(
                rs.getObject("id", UUID.class),
                rs.getObject("customer_id", UUID.class),
                DocumentType.valueOf(rs.getString("document_type")),
                rs.getString("file_name"),
                rs.getString("content_type"),
                rs.getLong("size_bytes"),
                rs.getString("checksum_sha256"),
                rs.getString("storage_key"),
                JdbcTimes.instant(rs, "created_at"));
    }
}
