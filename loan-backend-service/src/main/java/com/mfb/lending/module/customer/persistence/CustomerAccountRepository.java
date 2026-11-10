package com.mfb.lending.module.customer.persistence;

import java.util.List;
import java.util.UUID;

import com.mfb.lending.module.customer.application.IdentityNumbers;
import com.mfb.lending.module.customer.domain.AccountRecord;
import com.mfb.lending.module.customer.domain.AccountVerificationStatus;
import com.mfb.lending.module.customer.domain.SealedIdentifier;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CustomerAccountRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public CustomerAccountRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(AccountRecord account, SealedIdentifier number) {
        jdbc.update(
                """
                INSERT INTO customer_accounts (
                    id, customer_id, bank_code, bank_name, account_number_encrypted, account_number_hash,
                    account_number_last4, account_name, verification_status, verified_at)
                VALUES (
                    :id, :customerId, :bankCode, :bankName, :encrypted, :hash, :last4, :accountName, :status, :verifiedAt)
                ON CONFLICT (customer_id, account_number_hash) DO UPDATE SET
                    bank_code = EXCLUDED.bank_code,
                    bank_name = EXCLUDED.bank_name,
                    account_number_encrypted = EXCLUDED.account_number_encrypted,
                    account_number_last4 = EXCLUDED.account_number_last4,
                    account_name = EXCLUDED.account_name,
                    verification_status = EXCLUDED.verification_status,
                    verified_at = EXCLUDED.verified_at
                """,
                new MapSqlParameterSource()
                        .addValue("id", account.id())
                        .addValue("customerId", account.customerId())
                        .addValue("bankCode", account.bankCode())
                        .addValue("bankName", account.bankName())
                        .addValue("encrypted", number.ciphertext())
                        .addValue("hash", number.hash())
                        .addValue("last4", number.last4())
                        .addValue("accountName", account.accountName())
                        .addValue("status", account.verificationStatus().name())
                        .addValue("verifiedAt", JdbcTimes.offset(account.verifiedAt())));
    }

    public List<AccountRecord> list(UUID customerId) {
        return jdbc.query(
                """
                SELECT id, customer_id, bank_code, bank_name, account_number_last4, account_name, verification_status, verified_at
                FROM customer_accounts WHERE customer_id = :customerId ORDER BY created_at
                """,
                new MapSqlParameterSource("customerId", customerId),
                (rs, rowNum) -> new AccountRecord(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getString("bank_code"),
                        rs.getString("bank_name"),
                        IdentityNumbers.maskedLast4(rs.getString("account_number_last4")),
                        rs.getString("account_name"),
                        AccountVerificationStatus.valueOf(rs.getString("verification_status")),
                        JdbcTimes.instant(rs, "verified_at")));
    }

    public int verifiedCount(UUID customerId) {
        Integer count = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM customer_accounts
                WHERE customer_id = :customerId AND verification_status = 'VERIFIED'
                """,
                new MapSqlParameterSource("customerId", customerId),
                Integer.class);
        return count == null ? 0 : count;
    }
}
