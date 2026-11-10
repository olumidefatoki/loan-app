package com.mfb.lending.module.loanapplication.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.module.customer.domain.ClientChannel;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.loanapplication.domain.ApplicationDocumentRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationEventRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;
import com.mfb.lending.module.loanapplication.domain.ApplicationSummary;
import com.mfb.lending.module.loanapplication.domain.CollateralRecord;
import com.mfb.lending.module.loanapplication.domain.EmploymentStatus;
import com.mfb.lending.module.loanapplication.domain.FinancialProfile;
import com.mfb.lending.module.loanapplication.domain.GuarantorRecord;
import com.mfb.lending.module.loanapplication.domain.IndividualFinancials;
import com.mfb.lending.module.loanapplication.domain.Obligation;
import com.mfb.lending.module.loanapplication.domain.SmeFinancials;
import com.mfb.lending.module.loanapplication.domain.StatusCount;
import com.mfb.lending.module.loanapplication.domain.StatusHistoryRecord;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class LoanApplicationRepository {

    private static final TypeReference<List<Obligation>> OBLIGATIONS = new TypeReference<>() {
    };

    private static final String COLUMNS = """
            id, reference, customer_id, product_id, product_version_id, branch_id, customer_segment,
            status, revision_number, correction_open, amount, currency_code, purpose, tenure, tenure_unit,
            repayment_frequency, disbursement_account_id, channel, submitted_at, withdrawn_at, decision_reason,
            created_at, updated_at, created_by, updated_by
            """;

    private static final RowMapper<ApplicationRecord> APPLICATION = (rs, rowNum) -> mapApplication(rs);

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public LoanApplicationRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public String nextReference(int year) {
        Long value = jdbc.queryForObject(
                "SELECT nextval('loan_application_reference_seq')",
                new MapSqlParameterSource(),
                Long.class);
        if (value == null || value < 1 || value > 99_999_999L) {
            throw new IllegalStateException("Loan application reference sequence is exhausted");
        }
        return "LA" + year + String.format("%08d", value);
    }

    public boolean lockCustomer(UUID customerId) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM customers WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", customerId),
                (rs, rowNum) -> rs.getObject("id", UUID.class));
        return !ids.isEmpty();
    }

    public int countActive(UUID customerId, UUID productId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM loan_applications
                WHERE customer_id = :customerId
                  AND product_id = :productId
                  AND status NOT IN ('REJECTED', 'WITHDRAWN', 'CANCELLED', 'EXPIRED', 'DISBURSED')
                """,
                new MapSqlParameterSource().addValue("customerId", customerId).addValue("productId", productId),
                Integer.class);
        return count == null ? 0 : count;
    }

    public void insert(ApplicationRecord application) {
        jdbc.update(
                """
                INSERT INTO loan_applications (
                    id, reference, customer_id, product_id, product_version_id, branch_id, customer_segment,
                    status, revision_number, correction_open, amount, currency_code, purpose, tenure, tenure_unit,
                    repayment_frequency, disbursement_account_id, channel, submitted_at, withdrawn_at, decision_reason,
                    created_by, updated_by)
                VALUES (
                    :id, :reference, :customerId, :productId, :productVersionId, :branchId, :segment,
                    :status, :revisionNumber, :correctionOpen, :amount, :currencyCode, :purpose, :tenure, :tenureUnit,
                    :repaymentFrequency, :disbursementAccountId, :channel, :submittedAt, :withdrawnAt, :decisionReason,
                    :createdBy, :updatedBy)
                """,
                applicationParams(application));
    }

    public void update(ApplicationRecord application) {
        jdbc.update(
                """
                UPDATE loan_applications
                SET status = :status,
                    revision_number = :revisionNumber,
                    correction_open = :correctionOpen,
                    amount = :amount,
                    purpose = :purpose,
                    tenure = :tenure,
                    repayment_frequency = :repaymentFrequency,
                    disbursement_account_id = :disbursementAccountId,
                    submitted_at = :submittedAt,
                    withdrawn_at = :withdrawnAt,
                    decision_reason = :decisionReason,
                    updated_by = :updatedBy
                WHERE id = :id
                """,
                applicationParams(application));
    }

    public Optional<ApplicationRecord> lock(UUID id) {
        return one("SELECT " + COLUMNS + " FROM loan_applications WHERE id = :id FOR UPDATE",
                new MapSqlParameterSource("id", id), APPLICATION);
    }

    public Optional<ApplicationRecord> find(UUID id) {
        return one("SELECT " + COLUMNS + " FROM loan_applications WHERE id = :id",
                new MapSqlParameterSource("id", id), APPLICATION);
    }

    public List<ApplicationSummary> search(ApplicationSearch search) {
        Filter filter = filter(search);
        if (filter.empty()) {
            return List.of();
        }
        return jdbc.query(
                """
                SELECT a.id, a.reference, a.customer_id, c.display_name AS customer_name, a.customer_segment,
                       a.product_id, p.product_code, v.product_name, a.status, a.amount, a.currency_code,
                       a.tenure, a.tenure_unit, a.repayment_frequency, a.revision_number, a.correction_open,
                       a.channel, a.submitted_at, a.updated_at
                FROM loan_applications a
                JOIN customers c ON c.id = a.customer_id
                JOIN loan_products p ON p.id = a.product_id
                JOIN loan_product_versions v ON v.id = a.product_version_id
                """ + filter.where() + " ORDER BY " + orderBy(search) + " LIMIT :limit OFFSET :offset",
                filter.params().addValue("limit", search.limit()).addValue("offset", search.offset()),
                (rs, rowNum) -> new ApplicationSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("reference"),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("customer_name"),
                        CustomerKind.valueOf(rs.getString("customer_segment")),
                        rs.getObject("product_id", UUID.class),
                        rs.getString("product_code"),
                        rs.getString("product_name"),
                        ApplicationStatus.valueOf(rs.getString("status")),
                        rs.getBigDecimal("amount"),
                        rs.getString("currency_code"),
                        integer(rs, "tenure"),
                        TenureUnit.valueOf(rs.getString("tenure_unit")),
                        enumOrNull(RepaymentFrequency.class, rs.getString("repayment_frequency")),
                        rs.getInt("revision_number"),
                        rs.getBoolean("correction_open"),
                        ClientChannel.valueOf(rs.getString("channel")),
                        JdbcTimes.instant(rs, "submitted_at"),
                        JdbcTimes.instant(rs, "updated_at")));
    }

    public long count(ApplicationSearch search) {
        Filter filter = filter(search);
        if (filter.empty()) {
            return 0;
        }
        Long total = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM loan_applications a
                JOIN customers c ON c.id = a.customer_id
                """ + filter.where(),
                filter.params(),
                Long.class);
        return total == null ? 0 : total;
    }

    public List<StatusCount> totals(UUID customerId) {
        return jdbc.query(
                """
                SELECT status, COUNT(*) AS total, COALESCE(SUM(amount), 0) AS requested
                FROM loan_applications
                WHERE customer_id = :customerId
                GROUP BY status
                """,
                new MapSqlParameterSource("customerId", customerId),
                (rs, rowNum) -> new StatusCount(
                        ApplicationStatus.valueOf(rs.getString("status")),
                        rs.getLong("total"),
                        rs.getBigDecimal("requested")));
    }

    public void upsertFinancials(UUID applicationId, FinancialProfile profile) {
        IndividualFinancials individual = profile.individual();
        SmeFinancials sme = profile.sme();
        jdbc.update(
                """
                INSERT INTO loan_application_financials (
                    application_id, monthly_income, other_income, monthly_expenses, employment_status,
                    employer_name, job_title, employment_start_date, annual_turnover, monthly_turnover,
                    operating_expenses, monthly_cash_flow, business_liabilities, authorised_to_borrow,
                    authorised_borrower_name, authorised_borrowing_limit, obligations)
                VALUES (
                    :applicationId, :monthlyIncome, :otherIncome, :monthlyExpenses, :employmentStatus,
                    :employerName, :jobTitle, :employmentStartDate, :annualTurnover, :monthlyTurnover,
                    :operatingExpenses, :monthlyCashFlow, :businessLiabilities, :authorisedToBorrow,
                    :authorisedBorrowerName, :authorisedBorrowingLimit, CAST(:obligations AS jsonb))
                ON CONFLICT (application_id) DO UPDATE SET
                    monthly_income = EXCLUDED.monthly_income,
                    other_income = EXCLUDED.other_income,
                    monthly_expenses = EXCLUDED.monthly_expenses,
                    employment_status = EXCLUDED.employment_status,
                    employer_name = EXCLUDED.employer_name,
                    job_title = EXCLUDED.job_title,
                    employment_start_date = EXCLUDED.employment_start_date,
                    annual_turnover = EXCLUDED.annual_turnover,
                    monthly_turnover = EXCLUDED.monthly_turnover,
                    operating_expenses = EXCLUDED.operating_expenses,
                    monthly_cash_flow = EXCLUDED.monthly_cash_flow,
                    business_liabilities = EXCLUDED.business_liabilities,
                    authorised_to_borrow = EXCLUDED.authorised_to_borrow,
                    authorised_borrower_name = EXCLUDED.authorised_borrower_name,
                    authorised_borrowing_limit = EXCLUDED.authorised_borrowing_limit,
                    obligations = EXCLUDED.obligations
                """,
                new MapSqlParameterSource()
                        .addValue("applicationId", applicationId)
                        .addValue("monthlyIncome", individual == null ? null : individual.monthlyIncome())
                        .addValue("otherIncome", individual == null ? null : individual.otherIncome())
                        .addValue("monthlyExpenses", individual == null ? null : individual.monthlyExpenses())
                        .addValue("employmentStatus", individual == null || individual.employmentStatus() == null
                                ? null : individual.employmentStatus().name())
                        .addValue("employerName", individual == null ? null : individual.employerName())
                        .addValue("jobTitle", individual == null ? null : individual.jobTitle())
                        .addValue("employmentStartDate", individual == null ? null : individual.employmentStartDate())
                        .addValue("annualTurnover", sme == null ? null : sme.annualTurnover())
                        .addValue("monthlyTurnover", sme == null ? null : sme.monthlyTurnover())
                        .addValue("operatingExpenses", sme == null ? null : sme.operatingExpenses())
                        .addValue("monthlyCashFlow", sme == null ? null : sme.monthlyCashFlow())
                        .addValue("businessLiabilities", sme == null ? null : sme.businessLiabilities())
                        .addValue("authorisedToBorrow", sme == null ? null : sme.authorisedToBorrow())
                        .addValue("authorisedBorrowerName", sme == null ? null : sme.authorisedBorrowerName())
                        .addValue("authorisedBorrowingLimit", sme == null ? null : sme.authorisedBorrowingLimit())
                        .addValue("obligations", json(profile.obligations())));
    }

    public FinancialProfile findFinancials(UUID applicationId) {
        List<FinancialProfile> rows = jdbc.query(
                """
                SELECT monthly_income, other_income, monthly_expenses, employment_status, employer_name, job_title,
                       employment_start_date, annual_turnover, monthly_turnover, operating_expenses, monthly_cash_flow,
                       business_liabilities, authorised_to_borrow, authorised_borrower_name, authorised_borrowing_limit,
                       obligations::text AS obligations_json
                FROM loan_application_financials
                WHERE application_id = :applicationId
                """,
                new MapSqlParameterSource("applicationId", applicationId),
                (rs, rowNum) -> new FinancialProfile(individual(rs), sme(rs), obligations(rs.getString("obligations_json"))));
        return rows.stream().findFirst().orElseGet(FinancialProfile::empty);
    }

    public void replaceGuarantors(UUID applicationId, List<GuarantorRecord> guarantors) {
        jdbc.update(
                "DELETE FROM loan_application_guarantors WHERE application_id = :applicationId",
                new MapSqlParameterSource("applicationId", applicationId));
        for (GuarantorRecord guarantor : guarantors) {
            jdbc.update(
                    """
                    INSERT INTO loan_application_guarantors (
                        id, application_id, full_name, phone_number, email, relationship, occupation, address_line)
                    VALUES (
                        :id, :applicationId, :fullName, :phoneNumber, :email, :relationship, :occupation, :addressLine)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", guarantor.id())
                            .addValue("applicationId", applicationId)
                            .addValue("fullName", guarantor.fullName())
                            .addValue("phoneNumber", guarantor.phoneNumber())
                            .addValue("email", guarantor.email())
                            .addValue("relationship", guarantor.relationship())
                            .addValue("occupation", guarantor.occupation())
                            .addValue("addressLine", guarantor.addressLine()));
        }
    }

    public List<GuarantorRecord> listGuarantors(UUID applicationId) {
        return jdbc.query(
                """
                SELECT id, application_id, full_name, phone_number, email, relationship, occupation, address_line
                FROM loan_application_guarantors
                WHERE application_id = :applicationId
                ORDER BY created_at, full_name
                """,
                new MapSqlParameterSource("applicationId", applicationId),
                (rs, rowNum) -> new GuarantorRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("application_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getString("phone_number"),
                        rs.getString("email"),
                        rs.getString("relationship"),
                        rs.getString("occupation"),
                        rs.getString("address_line")));
    }

    public void replaceCollaterals(UUID applicationId, List<CollateralRecord> collaterals) {
        jdbc.update(
                "DELETE FROM loan_application_collaterals WHERE application_id = :applicationId",
                new MapSqlParameterSource("applicationId", applicationId));
        for (CollateralRecord collateral : collaterals) {
            jdbc.update(
                    """
                    INSERT INTO loan_application_collaterals (
                        id, application_id, collateral_type, description, estimated_value, owner_name, location)
                    VALUES (
                        :id, :applicationId, :collateralType, :description, :estimatedValue, :ownerName, :location)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", collateral.id())
                            .addValue("applicationId", applicationId)
                            .addValue("collateralType", collateral.collateralType())
                            .addValue("description", collateral.description())
                            .addValue("estimatedValue", collateral.estimatedValue())
                            .addValue("ownerName", collateral.ownerName())
                            .addValue("location", collateral.location()));
        }
    }

    public List<CollateralRecord> listCollaterals(UUID applicationId) {
        return jdbc.query(
                """
                SELECT id, application_id, collateral_type, description, estimated_value, owner_name, location
                FROM loan_application_collaterals
                WHERE application_id = :applicationId
                ORDER BY created_at, collateral_type
                """,
                new MapSqlParameterSource("applicationId", applicationId),
                (rs, rowNum) -> new CollateralRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("application_id", UUID.class),
                        rs.getString("collateral_type"),
                        rs.getString("description"),
                        rs.getBigDecimal("estimated_value"),
                        rs.getString("owner_name"),
                        rs.getString("location")));
    }

    public void insertDocument(ApplicationDocumentRecord document, String uploadedBy) {
        jdbc.update(
                """
                INSERT INTO loan_application_documents (
                    id, application_id, document_code, document_name, file_name, content_type,
                    size_bytes, checksum_sha256, storage_key, uploaded_by)
                VALUES (
                    :id, :applicationId, :documentCode, :documentName, :fileName, :contentType,
                    :sizeBytes, :checksumSha256, :storageKey, :uploadedBy)
                """,
                new MapSqlParameterSource()
                        .addValue("id", document.id())
                        .addValue("applicationId", document.applicationId())
                        .addValue("documentCode", document.documentCode())
                        .addValue("documentName", document.documentName())
                        .addValue("fileName", document.fileName())
                        .addValue("contentType", document.contentType())
                        .addValue("sizeBytes", document.sizeBytes())
                        .addValue("checksumSha256", document.checksumSha256())
                        .addValue("storageKey", document.storageKey())
                        .addValue("uploadedBy", uploadedBy));
    }

    public Optional<String> supersedeDocument(UUID applicationId, String documentCode) {
        List<String> keys = jdbc.query(
                """
                UPDATE loan_application_documents
                SET superseded_at = clock_timestamp()
                WHERE application_id = :applicationId
                  AND document_code = :documentCode
                  AND superseded_at IS NULL
                RETURNING storage_key
                """,
                new MapSqlParameterSource().addValue("applicationId", applicationId).addValue("documentCode", documentCode),
                (rs, rowNum) -> rs.getString("storage_key"));
        return keys.stream().findFirst();
    }

    public List<ApplicationDocumentRecord> listDocuments(UUID applicationId) {
        return jdbc.query(
                """
                SELECT id, application_id, document_code, document_name, file_name, content_type,
                       size_bytes, checksum_sha256, storage_key, uploaded_at
                FROM loan_application_documents
                WHERE application_id = :applicationId AND superseded_at IS NULL
                ORDER BY document_code
                """,
                new MapSqlParameterSource("applicationId", applicationId),
                (rs, rowNum) -> new ApplicationDocumentRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("application_id", UUID.class),
                        rs.getString("document_code"),
                        rs.getString("document_name"),
                        rs.getString("file_name"),
                        rs.getString("content_type"),
                        rs.getLong("size_bytes"),
                        rs.getString("checksum_sha256"),
                        rs.getString("storage_key"),
                        JdbcTimes.instant(rs, "uploaded_at")));
    }

    public void insertHistory(UUID id, UUID applicationId, ApplicationStatus from, ApplicationStatus to, String reason, UUID actorId) {
        jdbc.update(
                """
                INSERT INTO loan_application_status_history (id, application_id, from_status, to_status, reason, actor_id)
                VALUES (:id, :applicationId, :fromStatus, :toStatus, :reason, :actorId)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("applicationId", applicationId)
                        .addValue("fromStatus", from == null ? null : from.name())
                        .addValue("toStatus", to.name())
                        .addValue("reason", reason)
                        .addValue("actorId", actorId));
    }

    public List<StatusHistoryRecord> listHistory(UUID applicationId) {
        return jdbc.query(
                """
                SELECT id, application_id, from_status, to_status, reason, actor_id, occurred_at
                FROM loan_application_status_history
                WHERE application_id = :applicationId
                ORDER BY occurred_at, id
                """,
                new MapSqlParameterSource("applicationId", applicationId),
                (rs, rowNum) -> new StatusHistoryRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("application_id", UUID.class),
                        enumOrNull(ApplicationStatus.class, rs.getString("from_status")),
                        ApplicationStatus.valueOf(rs.getString("to_status")),
                        rs.getString("reason"),
                        rs.getObject("actor_id", UUID.class),
                        JdbcTimes.instant(rs, "occurred_at")));
    }

    public void insertEvent(UUID id, UUID applicationId, String eventType, String payload, UUID actorId) {
        jdbc.update(
                """
                INSERT INTO loan_application_events (id, application_id, event_type, payload, actor_id)
                VALUES (:id, :applicationId, :eventType, CAST(:payload AS jsonb), :actorId)
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("applicationId", applicationId)
                        .addValue("eventType", eventType)
                        .addValue("payload", payload == null ? "{}" : payload)
                        .addValue("actorId", actorId));
    }

    public List<ApplicationEventRecord> listEvents(UUID applicationId) {
        return jdbc.query(
                """
                SELECT id, application_id, event_type, payload::text AS payload_json, actor_id, occurred_at
                FROM loan_application_events
                WHERE application_id = :applicationId
                ORDER BY occurred_at, id
                """,
                new MapSqlParameterSource("applicationId", applicationId),
                (rs, rowNum) -> new ApplicationEventRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("application_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getString("payload_json"),
                        rs.getObject("actor_id", UUID.class),
                        JdbcTimes.instant(rs, "occurred_at")));
    }

    private Filter filter(ApplicationSearch search) {
        if (search.restrictBranches() && (search.branchIds() == null || search.branchIds().isEmpty())) {
            return Filter.none();
        }
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (search.customerId() != null) {
            where.append(" AND a.customer_id = :customerId");
            params.addValue("customerId", search.customerId());
        }
        if (search.restrictBranches()) {
            where.append(" AND a.branch_id IN (:branchIds)");
            params.addValue("branchIds", search.branchIds());
        }
        if (search.status() != null) {
            where.append(" AND a.status = :status");
            params.addValue("status", search.status().name());
        }
        if (search.productId() != null) {
            where.append(" AND a.product_id = :productId");
            params.addValue("productId", search.productId());
        }
        if (search.segment() != null) {
            where.append(" AND a.customer_segment = :segment");
            params.addValue("segment", search.segment().name());
        }
        if (search.excludedStatuses() != null && !search.excludedStatuses().isEmpty()) {
            where.append(" AND a.status NOT IN (:excludedStatuses)");
            params.addValue("excludedStatuses", search.excludedStatuses().stream().map(Enum::name).toList());
        }
        if (search.query() != null && !search.query().isBlank()) {
            where.append(" AND (a.reference ILIKE :query OR c.display_name ILIKE :query)");
            params.addValue("query", "%" + search.query().replace("\\", "").replace("%", "").replace("_", "") + "%");
        }
        return new Filter(where.toString(), params, false);
    }

    private static String orderBy(ApplicationSearch search) {
        String column = switch (search.sortColumn()) {
            case "a.amount", "a.reference", "a.updated_at", "a.submitted_at" -> search.sortColumn();
            default -> "a.updated_at";
        };
        return column + (search.ascending() ? " ASC" : " DESC") + " NULLS LAST, a.id ASC";
    }

    private MapSqlParameterSource applicationParams(ApplicationRecord application) {
        return new MapSqlParameterSource()
                .addValue("id", application.id())
                .addValue("reference", application.reference())
                .addValue("customerId", application.customerId())
                .addValue("productId", application.productId())
                .addValue("productVersionId", application.productVersionId())
                .addValue("branchId", application.branchId())
                .addValue("segment", application.segment().name())
                .addValue("status", application.status().name())
                .addValue("revisionNumber", application.revisionNumber())
                .addValue("correctionOpen", application.correctionOpen())
                .addValue("amount", application.amount())
                .addValue("currencyCode", application.currencyCode())
                .addValue("purpose", application.purpose())
                .addValue("tenure", application.tenure())
                .addValue("tenureUnit", application.tenureUnit().name())
                .addValue("repaymentFrequency", application.repaymentFrequency() == null ? null : application.repaymentFrequency().name())
                .addValue("disbursementAccountId", application.disbursementAccountId())
                .addValue("channel", application.channel().name())
                .addValue("submittedAt", JdbcTimes.offset(application.submittedAt()))
                .addValue("withdrawnAt", JdbcTimes.offset(application.withdrawnAt()))
                .addValue("decisionReason", application.decisionReason())
                .addValue("createdBy", application.createdBy())
                .addValue("updatedBy", application.updatedBy());
    }

    private static ApplicationRecord mapApplication(ResultSet rs) throws SQLException {
        return new ApplicationRecord(
                rs.getObject("id", UUID.class),
                rs.getString("reference"),
                rs.getObject("customer_id", UUID.class),
                rs.getObject("product_id", UUID.class),
                rs.getObject("product_version_id", UUID.class),
                rs.getObject("branch_id", UUID.class),
                CustomerKind.valueOf(rs.getString("customer_segment")),
                ApplicationStatus.valueOf(rs.getString("status")),
                rs.getInt("revision_number"),
                rs.getBoolean("correction_open"),
                rs.getBigDecimal("amount"),
                rs.getString("currency_code"),
                rs.getString("purpose"),
                integer(rs, "tenure"),
                TenureUnit.valueOf(rs.getString("tenure_unit")),
                enumOrNull(RepaymentFrequency.class, rs.getString("repayment_frequency")),
                rs.getObject("disbursement_account_id", UUID.class),
                ClientChannel.valueOf(rs.getString("channel")),
                JdbcTimes.instant(rs, "submitted_at"),
                JdbcTimes.instant(rs, "withdrawn_at"),
                rs.getString("decision_reason"),
                JdbcTimes.instant(rs, "created_at"),
                JdbcTimes.instant(rs, "updated_at"),
                rs.getString("created_by"),
                rs.getString("updated_by"));
    }

    private static IndividualFinancials individual(ResultSet rs) throws SQLException {
        IndividualFinancials financials = new IndividualFinancials(
                rs.getBigDecimal("monthly_income"),
                rs.getBigDecimal("other_income"),
                rs.getBigDecimal("monthly_expenses"),
                enumOrNull(EmploymentStatus.class, rs.getString("employment_status")),
                rs.getString("employer_name"),
                rs.getString("job_title"),
                rs.getObject("employment_start_date", LocalDate.class));
        return financials.captured() ? financials : null;
    }

    private static SmeFinancials sme(ResultSet rs) throws SQLException {
        SmeFinancials financials = new SmeFinancials(
                rs.getBigDecimal("annual_turnover"),
                rs.getBigDecimal("monthly_turnover"),
                rs.getBigDecimal("operating_expenses"),
                rs.getBigDecimal("monthly_cash_flow"),
                rs.getBigDecimal("business_liabilities"),
                rs.getObject("authorised_to_borrow") == null ? null : rs.getBoolean("authorised_to_borrow"),
                rs.getString("authorised_borrower_name"),
                rs.getBigDecimal("authorised_borrowing_limit"));
        return financials.captured() ? financials : null;
    }

    private List<Obligation> obligations(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Obligation> values = objectMapper.readValue(json, OBLIGATIONS);
            return values == null ? List.of() : values;
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Application obligations could not be read", ex);
        }
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? List.of() : value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Application obligations could not be written", ex);
        }
    }

    private <T> Optional<T> one(String sql, MapSqlParameterSource params, RowMapper<T> mapper) {
        return jdbc.query(sql, params, mapper).stream().findFirst();
    }

    private static Integer integer(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static <E extends Enum<E>> E enumOrNull(Class<E> type, String value) {
        return value == null ? null : Enum.valueOf(type, value);
    }

    private record Filter(String where, MapSqlParameterSource params, boolean empty) {

        static Filter none() {
            return new Filter("", new MapSqlParameterSource(), true);
        }
    }
}
