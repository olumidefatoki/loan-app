package com.mfb.lending.module.servicing.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.integration.corebanking.CoreBankingStore;
import com.mfb.lending.module.integration.corebanking.CoreTransaction;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.FeeInput;
import com.mfb.lending.module.loanapplication.domain.ApplicationRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;
import com.mfb.lending.module.loanapplication.persistence.LoanApplicationRepository;
import com.mfb.lending.module.loanproduct.domain.DayCountConvention;
import com.mfb.lending.module.loanproduct.domain.EarlyRepaymentRule;
import com.mfb.lending.module.loanproduct.domain.FeeCalculation;
import com.mfb.lending.module.loanproduct.domain.InterestMethod;
import com.mfb.lending.module.loanproduct.domain.InterestPeriod;
import com.mfb.lending.module.loanproduct.domain.PenaltyBasis;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.domain.TenureUnit;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcLoanBookingSource implements LoanBookingSource {

    private final NamedParameterJdbcTemplate jdbc;
    private final LoanApplicationRepository applications;
    private final CoreBankingStore core;

    public JdbcLoanBookingSource(NamedParameterJdbcTemplate jdbc, LoanApplicationRepository applications, CoreBankingStore core) {
        this.jdbc = jdbc;
        this.applications = applications;
        this.core = core;
    }

    @Override
    public LoanBooking load(UUID applicationId, String coreReference) {
        CoreTransaction disbursement = core.findOpenDisbursement(applicationId)
                .orElseThrow(() -> new ConflictException("The loan account cannot be activated before core banking confirms disbursement"));
        if (!disbursement.state().countsAsPosted() || coreReference == null || !coreReference.equals(disbursement.reference())) {
            throw new ConflictException("The loan account cannot be activated before core banking confirms disbursement");
        }
        ApplicationRecord application = applications.lock(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        if (application.status() != ApplicationStatus.DISBURSED) {
            throw new ConflictException("The loan account cannot be activated before disbursement is confirmed");
        }
        List<LoanBooking> rows = jdbc.query(
                """
                SELECT o.id AS offer_id, t.principal, t.interest_rate, t.interest_method, t.interest_period, t.tenure,
                       t.tenure_unit, t.repayment_frequency, t.day_count_convention, t.rounding_precision, t.rounding_method,
                       t.grace_period_days, t.tax_rate, t.product_version_id, v.early_repayment_rule, v.early_repayment_fee_rate,
                       v.penalty_basis, v.penalty_rate, v.penalty_amount, v.penalty_grace_days
                FROM loan_offers o
                JOIN loan_offer_terms t ON t.offer_version_id = o.current_version_id
                JOIN loan_product_versions v ON v.id = t.product_version_id
                WHERE o.application_id = :applicationId AND o.status = 'ACCEPTED'
                """,
                new MapSqlParameterSource("applicationId", applicationId),
                (rs, row) -> new LoanBooking(
                        application.id(),
                        application.customerId(),
                        application.branchId(),
                        application.productId(),
                        rs.getObject("product_version_id", UUID.class),
                        rs.getObject("offer_id", UUID.class),
                        rs.getBigDecimal("principal"),
                        rs.getBigDecimal("interest_rate"),
                        InterestMethod.valueOf(rs.getString("interest_method")),
                        InterestPeriod.valueOf(rs.getString("interest_period")),
                        rs.getInt("tenure"),
                        TenureUnit.valueOf(rs.getString("tenure_unit")),
                        RepaymentFrequency.valueOf(rs.getString("repayment_frequency")),
                        DayCountConvention.valueOf(rs.getString("day_count_convention")),
                        rs.getInt("rounding_precision"),
                        RoundingMode.valueOf(rs.getString("rounding_method")),
                        rs.getInt("grace_period_days"),
                        rs.getBigDecimal("tax_rate"),
                        List.of(),
                        EarlyRepaymentRule.valueOf(rs.getString("early_repayment_rule")),
                        rs.getBigDecimal("early_repayment_fee_rate"),
                        PenaltyBasis.valueOf(rs.getString("penalty_basis")),
                        rs.getBigDecimal("penalty_rate"),
                        rs.getBigDecimal("penalty_amount"),
                        rs.getInt("penalty_grace_days"),
                        true,
                        coreReference));
        if (rows.isEmpty()) {
            throw new ConflictException("An accepted offer is required before the loan account can be opened");
        }
        LoanBooking booking = rows.get(0);
        List<FeeInput> fees = jdbc.query(
                """
                SELECT fee_type, name, calculation, amount, rate
                FROM loan_product_fees
                WHERE version_id = :versionId
                """,
                new MapSqlParameterSource("versionId", booking.productVersionId()),
                (rs, row) -> new FeeInput(
                        rs.getString("fee_type"),
                        rs.getString("name"),
                        FeeCalculation.valueOf(rs.getString("calculation")),
                        rs.getBigDecimal("amount"),
                        rs.getBigDecimal("rate")));
        return new LoanBooking(
                booking.applicationId(), booking.customerId(), booking.branchId(), booking.productId(), booking.productVersionId(),
                booking.offerId(), booking.principal(), booking.interestRate(), booking.interestMethod(), booking.interestPeriod(),
                booking.tenure(), booking.tenureUnit(), booking.frequency(), booking.dayCount(), booking.scale(), booking.rounding(),
                booking.gracePeriodDays(), booking.taxRate(), fees, booking.earlyRule(), booking.earlyFeeRate(), booking.penaltyBasis(),
                booking.penaltyRate(), booking.penaltyAmount(), booking.penaltyGraceDays(), true, coreReference);
    }
}
