package com.mfb.lending.module.customer.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.auth.domain.CustomerType;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.customer.domain.CustomerCategory;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.CustomerStatus;
import com.mfb.lending.module.customer.domain.KycStatus;
import com.mfb.lending.module.customer.domain.RiskClassification;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerRepository {

    private static final String COLUMNS = """
            id, customer_reference, customer_type, status, category, display_name, email, phone_number,
            user_id, branch_id, kyc_status, risk_classification, blacklisted, blacklist_reason,
            compliance_hold, compliance_hold_reason, created_at, updated_at, created_by, updated_by
            """;

    private static final RowMapper<CustomerRecord> MAPPER = (rs, rowNum) -> new CustomerRecord(
            rs.getObject("id", UUID.class),
            rs.getString("customer_reference"),
            CustomerKind.valueOf(rs.getString("customer_type")),
            CustomerStatus.valueOf(rs.getString("status")),
            CustomerCategory.valueOf(rs.getString("category")),
            rs.getString("display_name"),
            rs.getString("email"),
            rs.getString("phone_number"),
            rs.getObject("user_id", UUID.class),
            rs.getObject("branch_id", UUID.class),
            KycStatus.valueOf(rs.getString("kyc_status")),
            RiskClassification.valueOf(rs.getString("risk_classification")),
            rs.getBoolean("blacklisted"),
            rs.getString("blacklist_reason"),
            rs.getBoolean("compliance_hold"),
            rs.getString("compliance_hold_reason"),
            JdbcTimes.instant(rs, "created_at"),
            JdbcTimes.instant(rs, "updated_at"),
            rs.getString("created_by"),
            rs.getString("updated_by"));

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(CustomerRecord customer) {
        jdbc.update(
                """
                INSERT INTO customers (
                    id, customer_reference, customer_type, status, category, display_name, email, phone_number,
                    user_id, branch_id, kyc_status, risk_classification, created_by, updated_by)
                VALUES (
                    :id, :reference, :customerType, :status, :category, :displayName, :email, :phoneNumber,
                    :userId, :branchId, :kycStatus, :riskClassification, :createdBy, :updatedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", customer.id())
                        .addValue("reference", customer.reference())
                        .addValue("customerType", customer.kind().name())
                        .addValue("status", customer.status().name())
                        .addValue("category", customer.category().name())
                        .addValue("displayName", customer.displayName())
                        .addValue("email", customer.email())
                        .addValue("phoneNumber", customer.phoneNumber())
                        .addValue("userId", customer.userId())
                        .addValue("branchId", customer.branchId())
                        .addValue("kycStatus", customer.kycStatus().name())
                        .addValue("riskClassification", customer.riskClassification().name())
                        .addValue("createdBy", customer.createdBy())
                        .addValue("updatedBy", customer.updatedBy()));
    }

    public String nextReference() {
        Long value = jdbc.queryForObject("SELECT nextval('customer_reference_seq')", new MapSqlParameterSource(), Long.class);
        return "CUS" + String.format("%08d", value);
    }

    public Optional<CustomerRecord> findById(UUID id) {
        return one("SELECT " + COLUMNS + " FROM customers WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    public Optional<CustomerRecord> lockById(UUID id) {
        return one("SELECT " + COLUMNS + " FROM customers WHERE id = :id FOR UPDATE", new MapSqlParameterSource("id", id));
    }

    public Optional<CustomerRecord> findByUserId(UUID userId) {
        return one("SELECT " + COLUMNS + " FROM customers WHERE user_id = :userId", new MapSqlParameterSource("userId", userId));
    }

    public Optional<LinkedUser> findUser(UUID userId) {
        List<LinkedUser> rows = jdbc.query(
                "SELECT principal_type, customer_type FROM system_users WHERE id = :id",
                new MapSqlParameterSource("id", userId),
                (rs, rowNum) -> new LinkedUser(
                        PrincipalType.valueOf(rs.getString("principal_type")),
                        CustomerType.valueOf(rs.getString("customer_type"))));
        return rows.stream().findFirst();
    }

    public boolean branchIsActive(UUID branchId) {
        List<String> statuses = jdbc.query(
                "SELECT status FROM branches WHERE id = :id",
                new MapSqlParameterSource("id", branchId),
                (rs, rowNum) -> rs.getString("status"));
        return statuses.stream().findFirst().map(status -> status.equals("ACTIVE")).orElse(false);
    }

    public void update(CustomerRecord customer, Instant blacklistedAt) {
        jdbc.update(
                """
                UPDATE customers
                SET display_name = :displayName,
                    email = :email,
                    phone_number = :phoneNumber,
                    category = :category,
                    branch_id = :branchId,
                    status = :status,
                    blacklisted = :blacklisted,
                    blacklist_reason = :blacklistReason,
                    blacklisted_at = :blacklistedAt,
                    risk_classification = :riskClassification,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", customer.id())
                        .addValue("displayName", customer.displayName())
                        .addValue("email", customer.email())
                        .addValue("phoneNumber", customer.phoneNumber())
                        .addValue("category", customer.category().name())
                        .addValue("branchId", customer.branchId())
                        .addValue("status", customer.status().name())
                        .addValue("blacklisted", customer.blacklisted())
                        .addValue("blacklistReason", customer.blacklistReason())
                        .addValue("blacklistedAt", JdbcTimes.offset(blacklistedAt))
                        .addValue("riskClassification", customer.riskClassification().name())
                        .addValue("updatedBy", customer.updatedBy()));
    }

    public void updateKycState(UUID id, KycStatus kycStatus, RiskClassification risk, boolean hold, String holdReason, String updatedBy) {
        jdbc.update(
                """
                UPDATE customers
                SET kyc_status = :kycStatus,
                    risk_classification = :riskClassification,
                    compliance_hold = :complianceHold,
                    compliance_hold_reason = :complianceHoldReason,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("kycStatus", kycStatus.name())
                        .addValue("riskClassification", risk.name())
                        .addValue("complianceHold", hold)
                        .addValue("complianceHoldReason", holdReason)
                        .addValue("updatedBy", updatedBy));
    }

    public void rename(UUID id, String displayName, String email, String phoneNumber, String updatedBy) {
        jdbc.update(
                """
                UPDATE customers
                SET display_name = :displayName, email = :email, phone_number = :phoneNumber, updated_by = :updatedBy
                WHERE id = :id
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("displayName", displayName)
                        .addValue("email", email)
                        .addValue("phoneNumber", phoneNumber)
                        .addValue("updatedBy", updatedBy));
    }

    public List<CustomerRecord> search(CustomerQuery query) {
        return jdbc.query(
                "SELECT " + SELECT_LIST + where(query) + " ORDER BY c.created_at DESC LIMIT :limit OFFSET :offset",
                params(query),
                MAPPER);
    }

    public long count(CustomerQuery query) {
        Long total = jdbc.queryForObject("SELECT COUNT(*)" + where(query), params(query), Long.class);
        return total == null ? 0 : total;
    }

    private static final String SELECT_LIST = """
            c.id, c.customer_reference, c.customer_type, c.status, c.category, c.display_name, c.email, c.phone_number,
            c.user_id, c.branch_id, c.kyc_status, c.risk_classification, c.blacklisted, c.blacklist_reason,
            c.compliance_hold, c.compliance_hold_reason, c.created_at, c.updated_at, c.created_by, c.updated_by
            """;

    private static String where(CustomerQuery query) {
        StringBuilder sql = new StringBuilder(" FROM customers c WHERE 1 = 1");
        if (query.customerType() != null) {
            sql.append(" AND c.customer_type = :customerType");
        }
        if (query.status() != null) {
            sql.append(" AND c.status = :status");
        }
        if (query.category() != null) {
            sql.append(" AND c.category = :category");
        }
        if (query.branchId() != null) {
            sql.append(" AND c.branch_id = :branchId");
        }
        if (query.reference() != null) {
            sql.append(" AND c.customer_reference = :reference");
        }
        if (query.email() != null) {
            sql.append(" AND c.email = :email");
        }
        if (query.phoneNumber() != null) {
            sql.append(" AND c.phone_number = :phoneNumber");
        }
        if (query.text() != null) {
            sql.append("""
                     AND (c.customer_reference = upper(:text) OR c.email = lower(:text) OR c.phone_number = :text
                          OR c.display_name ILIKE :textLike ESCAPE '\\')
                    """);
        }
        if (query.bvnHash() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM individual_profiles ip WHERE ip.customer_id = c.id AND ip.bvn_hash = :bvnHash)");
        }
        if (query.ninHash() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM individual_profiles ip WHERE ip.customer_id = c.id AND ip.nin_hash = :ninHash)");
        }
        if (query.cacNumber() != null) {
            sql.append(" AND EXISTS (SELECT 1 FROM sme_profiles sp WHERE sp.customer_id = c.id AND sp.cac_number = :cacNumber)");
        }
        if (!query.unrestricted()) {
            if (query.branchIds() == null || query.branchIds().isEmpty()) {
                sql.append(" AND FALSE");
            } else {
                sql.append(" AND c.branch_id IN (:branchIds)");
            }
        }
        return sql.toString();
    }

    private static MapSqlParameterSource params(CustomerQuery query) {
        return new MapSqlParameterSource()
                .addValue("customerType", query.customerType())
                .addValue("status", query.status())
                .addValue("category", query.category())
                .addValue("branchId", query.branchId())
                .addValue("reference", query.reference())
                .addValue("email", query.email())
                .addValue("phoneNumber", query.phoneNumber())
                .addValue("text", query.text())
                .addValue("textLike", query.textLike())
                .addValue("bvnHash", query.bvnHash())
                .addValue("ninHash", query.ninHash())
                .addValue("cacNumber", query.cacNumber())
                .addValue("branchIds", query.branchIds())
                .addValue("limit", query.limit())
                .addValue("offset", query.offset());
    }

    private Optional<CustomerRecord> one(String sql, MapSqlParameterSource params) {
        return jdbc.query(sql, params, MAPPER).stream().findFirst();
    }
}
