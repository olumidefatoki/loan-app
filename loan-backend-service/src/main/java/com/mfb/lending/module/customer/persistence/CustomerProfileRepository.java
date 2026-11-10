package com.mfb.lending.module.customer.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.customer.application.IdentityNumbers;
import com.mfb.lending.module.customer.domain.AddressRecord;
import com.mfb.lending.module.customer.domain.AddressType;
import com.mfb.lending.module.customer.domain.BusinessType;
import com.mfb.lending.module.customer.domain.ConsentType;
import com.mfb.lending.module.customer.domain.EmploymentStatus;
import com.mfb.lending.module.customer.domain.IndividualProfile;
import com.mfb.lending.module.customer.domain.PartyRecord;
import com.mfb.lending.module.customer.domain.SealedIdentifier;
import com.mfb.lending.module.customer.domain.SmeProfile;
import com.mfb.lending.module.customer.domain.StoredIdentity;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerProfileRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerProfileRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsertIndividual(
            IndividualProfile profile,
            SealedIdentifier bvn,
            SealedIdentifier nin) {
        jdbc.update(
                """
                INSERT INTO individual_profiles (
                    customer_id, full_name, date_of_birth, phone_number, email,
                    bvn_encrypted, bvn_hash, bvn_last4, nin_encrypted, nin_hash, nin_last4,
                    employment_status, employer_name, occupation, monthly_income, monthly_expenses,
                    nok_full_name, nok_relationship, nok_phone_number, nok_address)
                VALUES (
                    :customerId, :fullName, :dateOfBirth, :phoneNumber, :email,
                    :bvnEncrypted, :bvnHash, :bvnLast4, :ninEncrypted, :ninHash, :ninLast4,
                    :employmentStatus, :employerName, :occupation, :monthlyIncome, :monthlyExpenses,
                    :nokFullName, :nokRelationship, :nokPhoneNumber, :nokAddress)
                ON CONFLICT (customer_id) DO UPDATE SET
                    full_name = EXCLUDED.full_name,
                    date_of_birth = EXCLUDED.date_of_birth,
                    phone_number = EXCLUDED.phone_number,
                    email = EXCLUDED.email,
                    bvn_encrypted = EXCLUDED.bvn_encrypted,
                    bvn_hash = EXCLUDED.bvn_hash,
                    bvn_last4 = EXCLUDED.bvn_last4,
                    nin_encrypted = EXCLUDED.nin_encrypted,
                    nin_hash = EXCLUDED.nin_hash,
                    nin_last4 = EXCLUDED.nin_last4,
                    employment_status = EXCLUDED.employment_status,
                    employer_name = EXCLUDED.employer_name,
                    occupation = EXCLUDED.occupation,
                    monthly_income = EXCLUDED.monthly_income,
                    monthly_expenses = EXCLUDED.monthly_expenses,
                    nok_full_name = EXCLUDED.nok_full_name,
                    nok_relationship = EXCLUDED.nok_relationship,
                    nok_phone_number = EXCLUDED.nok_phone_number,
                    nok_address = EXCLUDED.nok_address
                """,
                new MapSqlParameterSource()
                        .addValue("customerId", profile.customerId())
                        .addValue("fullName", profile.fullName())
                        .addValue("dateOfBirth", profile.dateOfBirth())
                        .addValue("phoneNumber", profile.phoneNumber())
                        .addValue("email", profile.email())
                        .addValue("bvnEncrypted", bvn.ciphertext())
                        .addValue("bvnHash", bvn.hash())
                        .addValue("bvnLast4", bvn.last4())
                        .addValue("ninEncrypted", nin.ciphertext())
                        .addValue("ninHash", nin.hash())
                        .addValue("ninLast4", nin.last4())
                        .addValue("employmentStatus", profile.employmentStatus().name())
                        .addValue("employerName", profile.employerName())
                        .addValue("occupation", profile.occupation())
                        .addValue("monthlyIncome", profile.monthlyIncome())
                        .addValue("monthlyExpenses", profile.monthlyExpenses())
                        .addValue("nokFullName", profile.nextOfKinName())
                        .addValue("nokRelationship", profile.nextOfKinRelationship())
                        .addValue("nokPhoneNumber", profile.nextOfKinPhone())
                        .addValue("nokAddress", profile.nextOfKinAddress()));
    }

    public Optional<IndividualProfile> findIndividual(UUID customerId) {
        return jdbc.query(
                """
                SELECT customer_id, full_name, date_of_birth, phone_number, email,
                       bvn_encrypted, bvn_last4, nin_encrypted, nin_last4,
                       employment_status, employer_name, occupation, monthly_income, monthly_expenses,
                       nok_full_name, nok_relationship, nok_phone_number, nok_address
                FROM individual_profiles WHERE customer_id = :customerId
                """,
                new MapSqlParameterSource("customerId", customerId),
                (rs, rowNum) -> new IndividualProfile(
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getObject("date_of_birth", LocalDate.class),
                        rs.getString("phone_number"),
                        rs.getString("email"),
                        identity(rs, "bvn_encrypted", "bvn_last4"),
                        identity(rs, "nin_encrypted", "nin_last4"),
                        EmploymentStatus.valueOf(rs.getString("employment_status")),
                        rs.getString("employer_name"),
                        rs.getString("occupation"),
                        rs.getBigDecimal("monthly_income"),
                        rs.getBigDecimal("monthly_expenses"),
                        rs.getString("nok_full_name"),
                        rs.getString("nok_relationship"),
                        rs.getString("nok_phone_number"),
                        rs.getString("nok_address")))
                .stream().findFirst();
    }

    public Optional<UUID> customerIdByBvnHash(String hash) {
        return id("SELECT customer_id FROM individual_profiles WHERE bvn_hash = :hash", hash);
    }

    public Optional<UUID> customerIdByNinHash(String hash) {
        return id("SELECT customer_id FROM individual_profiles WHERE nin_hash = :hash", hash);
    }

    public void upsertSme(SmeProfile profile, SealedIdentifier tin) {
        jdbc.update(
                """
                INSERT INTO sme_profiles (
                    customer_id, registered_name, trading_name, cac_number, business_type, business_sector,
                    incorporation_date, annual_turnover, monthly_revenue, operating_expenses,
                    tin_encrypted, tin_hash, tin_last4)
                VALUES (
                    :customerId, :registeredName, :tradingName, :cacNumber, :businessType, :businessSector,
                    :incorporationDate, :annualTurnover, :monthlyRevenue, :operatingExpenses,
                    :tinEncrypted, :tinHash, :tinLast4)
                ON CONFLICT (customer_id) DO UPDATE SET
                    registered_name = EXCLUDED.registered_name,
                    trading_name = EXCLUDED.trading_name,
                    cac_number = EXCLUDED.cac_number,
                    business_type = EXCLUDED.business_type,
                    business_sector = EXCLUDED.business_sector,
                    incorporation_date = EXCLUDED.incorporation_date,
                    annual_turnover = EXCLUDED.annual_turnover,
                    monthly_revenue = EXCLUDED.monthly_revenue,
                    operating_expenses = EXCLUDED.operating_expenses,
                    tin_encrypted = EXCLUDED.tin_encrypted,
                    tin_hash = EXCLUDED.tin_hash,
                    tin_last4 = EXCLUDED.tin_last4
                """,
                new MapSqlParameterSource()
                        .addValue("customerId", profile.customerId())
                        .addValue("registeredName", profile.registeredName())
                        .addValue("tradingName", profile.tradingName())
                        .addValue("cacNumber", profile.cacNumber())
                        .addValue("businessType", profile.businessType().name())
                        .addValue("businessSector", profile.businessSector())
                        .addValue("incorporationDate", profile.incorporationDate())
                        .addValue("annualTurnover", profile.annualTurnover())
                        .addValue("monthlyRevenue", profile.monthlyRevenue())
                        .addValue("operatingExpenses", profile.operatingExpenses())
                        .addValue("tinEncrypted", tin.ciphertext())
                        .addValue("tinHash", tin.hash())
                        .addValue("tinLast4", tin.last4()));
    }

    public Optional<SmeProfile> findSme(UUID customerId) {
        return jdbc.query(
                """
                SELECT customer_id, registered_name, trading_name, cac_number, business_type, business_sector,
                       incorporation_date, annual_turnover, monthly_revenue, operating_expenses, tin_encrypted, tin_last4
                FROM sme_profiles WHERE customer_id = :customerId
                """,
                new MapSqlParameterSource("customerId", customerId),
                (rs, rowNum) -> new SmeProfile(
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("registered_name"),
                        rs.getString("trading_name"),
                        rs.getString("cac_number"),
                        BusinessType.valueOf(rs.getString("business_type")),
                        rs.getString("business_sector"),
                        rs.getObject("incorporation_date", LocalDate.class),
                        rs.getBigDecimal("annual_turnover"),
                        rs.getBigDecimal("monthly_revenue"),
                        rs.getBigDecimal("operating_expenses"),
                        identity(rs, "tin_encrypted", "tin_last4")))
                .stream().findFirst();
    }

    public Optional<UUID> customerIdByCac(String cacNumber) {
        List<UUID> ids = jdbc.query(
                "SELECT customer_id FROM sme_profiles WHERE cac_number = :cacNumber",
                new MapSqlParameterSource("cacNumber", cacNumber),
                (rs, rowNum) -> rs.getObject("customer_id", UUID.class));
        return ids.stream().findFirst();
    }

    public Optional<UUID> customerIdByTinHash(String hash) {
        return id("SELECT customer_id FROM sme_profiles WHERE tin_hash = :hash", hash);
    }

    public void insertDirector(PartyRecord party, SealedIdentifier bvn, SealedIdentifier nin) {
        insertParty("customer_directors", party, bvn, nin, false);
    }

    public void insertOwner(PartyRecord party, SealedIdentifier bvn, SealedIdentifier nin) {
        insertParty("customer_beneficial_owners", party, bvn, nin, true);
    }

    public List<PartyRecord> directors(UUID customerId) {
        return parties("customer_directors", customerId, false);
    }

    public List<PartyRecord> owners(UUID customerId) {
        return parties("customer_beneficial_owners", customerId, true);
    }

    public void replaceRepresentatives(UUID customerId, List<PartyRecord> representatives) {
        jdbc.update(
                "DELETE FROM customer_authorised_representatives WHERE customer_id = :customerId",
                new MapSqlParameterSource("customerId", customerId));
        for (PartyRecord party : representatives) {
            jdbc.update(
                    """
                    INSERT INTO customer_authorised_representatives (id, customer_id, full_name, phone_number, email, position)
                    VALUES (:id, :customerId, :fullName, :phoneNumber, :email, :position)
                    """,
                    new MapSqlParameterSource()
                            .addValue("id", party.id())
                            .addValue("customerId", customerId)
                            .addValue("fullName", party.fullName())
                            .addValue("phoneNumber", party.phoneNumber())
                            .addValue("email", party.email())
                            .addValue("position", party.position()));
        }
    }

    public List<PartyRecord> representatives(UUID customerId) {
        return jdbc.query(
                """
                SELECT id, customer_id, full_name, phone_number, email, position
                FROM customer_authorised_representatives WHERE customer_id = :customerId ORDER BY created_at
                """,
                new MapSqlParameterSource("customerId", customerId),
                (rs, rowNum) -> new PartyRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("full_name"),
                        null,
                        rs.getString("phone_number"),
                        rs.getString("email"),
                        rs.getString("position"),
                        null,
                        null,
                        null));
    }

    public void upsertAddress(AddressRecord address) {
        jdbc.update(
                """
                INSERT INTO customer_addresses (id, customer_id, address_type, line1, line2, city, state, country_code, postal_code)
                VALUES (:id, :customerId, :addressType, :line1, :line2, :city, :state, :countryCode, :postalCode)
                ON CONFLICT (customer_id, address_type) DO UPDATE SET
                    line1 = EXCLUDED.line1,
                    line2 = EXCLUDED.line2,
                    city = EXCLUDED.city,
                    state = EXCLUDED.state,
                    country_code = EXCLUDED.country_code,
                    postal_code = EXCLUDED.postal_code
                """,
                new MapSqlParameterSource()
                        .addValue("id", address.id())
                        .addValue("customerId", address.customerId())
                        .addValue("addressType", address.type().name())
                        .addValue("line1", address.line1())
                        .addValue("line2", address.line2())
                        .addValue("city", address.city())
                        .addValue("state", address.state())
                        .addValue("countryCode", address.countryCode())
                        .addValue("postalCode", address.postalCode()));
    }

    public List<AddressRecord> addresses(UUID customerId) {
        return jdbc.query(
                """
                SELECT id, customer_id, address_type, line1, line2, city, state, country_code, postal_code
                FROM customer_addresses WHERE customer_id = :customerId ORDER BY address_type
                """,
                new MapSqlParameterSource("customerId", customerId),
                ADDRESS_MAPPER);
    }

    public void upsertConsent(UUID id, UUID customerId, ConsentType type, boolean granted, String version, String channel, Instant grantedAt) {
        jdbc.update(
                """
                INSERT INTO customer_consents (id, customer_id, consent_type, granted, version, channel, granted_at)
                VALUES (:id, :customerId, :consentType, :granted, :version, :channel, :grantedAt)
                ON CONFLICT (customer_id, consent_type) DO UPDATE SET
                    granted = EXCLUDED.granted,
                    version = EXCLUDED.version,
                    channel = EXCLUDED.channel,
                    granted_at = EXCLUDED.granted_at
                """,
                new MapSqlParameterSource()
                        .addValue("id", id)
                        .addValue("customerId", customerId)
                        .addValue("consentType", type.name())
                        .addValue("granted", granted)
                        .addValue("version", version)
                        .addValue("channel", channel)
                        .addValue("grantedAt", JdbcTimes.offset(grantedAt)));
    }

    public List<ConsentType> grantedConsents(UUID customerId) {
        return jdbc.query(
                "SELECT consent_type FROM customer_consents WHERE customer_id = :customerId AND granted = TRUE",
                new MapSqlParameterSource("customerId", customerId),
                (rs, rowNum) -> ConsentType.valueOf(rs.getString("consent_type")));
    }

    private void insertParty(String table, PartyRecord party, SealedIdentifier bvn, SealedIdentifier nin, boolean owner) {
        String ownership = owner ? ", ownership_percentage" : "";
        String ownershipValue = owner ? ", :ownershipPercentage" : "";
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", party.id())
                .addValue("customerId", party.customerId())
                .addValue("fullName", party.fullName())
                .addValue("dateOfBirth", party.dateOfBirth())
                .addValue("phoneNumber", party.phoneNumber())
                .addValue("email", party.email())
                .addValue("bvnEncrypted", bvn.ciphertext())
                .addValue("bvnHash", bvn.hash())
                .addValue("bvnLast4", bvn.last4())
                .addValue("ninEncrypted", nin == null ? null : nin.ciphertext())
                .addValue("ninHash", nin == null ? null : nin.hash())
                .addValue("ninLast4", nin == null ? null : nin.last4());
        if (owner) {
            params.addValue("ownershipPercentage", party.ownershipPercentage());
        } else {
            params.addValue("position", party.position());
        }
        String position = owner ? "" : ", position";
        String positionValue = owner ? "" : ", :position";
        jdbc.update(
                "INSERT INTO " + table + " (id, customer_id, full_name, date_of_birth, phone_number, email"
                        + position + ownership + ", bvn_encrypted, bvn_hash, bvn_last4, nin_encrypted, nin_hash, nin_last4) VALUES ("
                        + ":id, :customerId, :fullName, :dateOfBirth, :phoneNumber, :email"
                        + positionValue + ownershipValue + ", :bvnEncrypted, :bvnHash, :bvnLast4, :ninEncrypted, :ninHash, :ninLast4)",
                params);
    }

    private List<PartyRecord> parties(String table, UUID customerId, boolean owner) {
        String ownership = owner ? ", ownership_percentage" : ", position";
        return jdbc.query(
                "SELECT id, customer_id, full_name, date_of_birth, phone_number, email" + ownership
                        + ", bvn_encrypted, bvn_last4, nin_encrypted, nin_last4 FROM " + table
                        + " WHERE customer_id = :customerId ORDER BY created_at",
                new MapSqlParameterSource("customerId", customerId),
                (rs, rowNum) -> new PartyRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("full_name"),
                        rs.getObject("date_of_birth", LocalDate.class),
                        rs.getString("phone_number"),
                        rs.getString("email"),
                        owner ? null : rs.getString("position"),
                        owner ? rs.getBigDecimal("ownership_percentage") : null,
                        identity(rs, "bvn_encrypted", "bvn_last4"),
                        identity(rs, "nin_encrypted", "nin_last4")));
    }

    private Optional<UUID> id(String sql, String hash) {
        List<UUID> ids = jdbc.query(sql, new MapSqlParameterSource("hash", hash), (rs, rowNum) -> rs.getObject("customer_id", UUID.class));
        return ids.stream().findFirst();
    }

    private static StoredIdentity identity(ResultSet rs, String encryptedColumn, String last4Column) throws SQLException {
        String ciphertext = rs.getString(encryptedColumn);
        String last4 = rs.getString(last4Column);
        if (ciphertext == null || last4 == null) {
            return null;
        }
        return new StoredIdentity(ciphertext, IdentityNumbers.maskedLast4(last4));
    }

    private static final RowMapper<AddressRecord> ADDRESS_MAPPER = (rs, rowNum) -> new AddressRecord(
            rs.getObject("id", UUID.class),
            rs.getObject("customer_id", UUID.class),
            AddressType.valueOf(rs.getString("address_type")),
            rs.getString("line1"),
            rs.getString("line2"),
            rs.getString("city"),
            rs.getString("state"),
            rs.getString("country_code").trim(),
            rs.getString("postal_code"));
}
