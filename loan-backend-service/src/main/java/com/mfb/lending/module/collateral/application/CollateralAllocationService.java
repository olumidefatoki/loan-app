package com.mfb.lending.module.collateral.application;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.collateral.domain.AllocationRecord;
import com.mfb.lending.module.collateral.domain.CollateralRecord;
import com.mfb.lending.module.collateral.domain.EncumbranceRecord;
import com.mfb.lending.module.collateral.domain.EncumbranceSource;
import com.mfb.lending.module.collateral.domain.PledgePosition;
import com.mfb.lending.module.collateral.domain.PledgeStatus;
import com.mfb.lending.module.collateral.dto.AllocateCollateralRequest;
import com.mfb.lending.module.collateral.dto.LoanCollateralListResponse;
import com.mfb.lending.module.collateral.dto.LoanCollateralResponse;
import com.mfb.lending.module.collateral.persistence.CollateralRepository;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import com.mfb.lending.module.loanapplication.application.ApplicationAccess;
import com.mfb.lending.module.loanapplication.domain.ApplicationRecord;
import com.mfb.lending.module.loanapplication.persistence.LoanApplicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CollateralAllocationService {

    private final CollateralRepository collaterals;
    private final CustomerRepository customers;
    private final LoanApplicationRepository applications;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public CollateralAllocationService(
            CollateralRepository collaterals,
            CustomerRepository customers,
            LoanApplicationRepository applications,
            AuditService audit,
            TransactionTemplate transactions) {
        this.collaterals = collaterals;
        this.customers = customers;
        this.applications = applications;
        this.audit = audit;
        this.transactions = transactions;
    }

    public LoanCollateralResponse allocate(
            UUID applicationId,
            AllocateCollateralRequest request,
            AuthenticatedUser actor,
            ClientContext client) {
        CollateralAccess.assertStaff(actor);
        ApplicationRecord application = requireApplication(applicationId, actor);
        BigDecimal amount = CollateralSupport.money(request.allocatedAmount());
        return transactions.execute(status -> {
            CollateralRecord collateral = collaterals.lock(request.collateralId())
                    .orElseThrow(() -> new ResourceNotFoundException("Collateral"));
            CollateralAccess.customer(customers, collateral.customerId(), actor);
            if (!collateral.customerId().equals(application.customerId())) {
                throw new ConflictException("Collateral belongs to a different customer");
            }
            if (collaterals.hasActivePair(applicationId, collateral.id())) {
                throw new ConflictException("Collateral is already pledged to this application");
            }
            PledgePosition position = collaterals.position(collateral.id());
            CollateralRules.assertCanAllocate(new CollateralRules.AllocationCheck(
                    collateral.verificationStatus(),
                    collateral.valuationExpiresOn(),
                    collateral.eligibleLendingValue(),
                    position.allocatedAmount(),
                    position.exclusivePledges(),
                    position.sharedPledges(),
                    request.sharedSecurity(),
                    amount,
                    application.status(),
                    CollateralSupport.today()));
            UUID allocationId = UUID.randomUUID();
            AllocationRecord allocation = new AllocationRecord(
                    allocationId,
                    applicationId,
                    collateral.id(),
                    amount,
                    request.sharedSecurity(),
                    PledgeStatus.ACTIVE,
                    null,
                    null,
                    actor.getId());
            collaterals.insertAllocation(allocation);
            collaterals.insertEncumbrance(new EncumbranceRecord(
                    UUID.randomUUID(),
                    collateral.id(),
                    allocationId,
                    EncumbranceSource.INTERNAL,
                    "MFB Digital Lending",
                    application.reference(),
                    amount,
                    PledgeStatus.ACTIVE,
                    null,
                    null,
                    actor.getId()));
            audit.record("COLLATERAL_ALLOCATED", "collateral", collateral.id().toString(), actor.getUsername(), client, Map.of(
                    "applicationId", applicationId.toString(),
                    "allocationId", allocationId.toString(),
                    "allocatedAmount", amount,
                    "sharedSecurity", request.sharedSecurity()));
            return response(allocationId, application);
        });
    }

    public LoanCollateralListResponse list(UUID applicationId, AuthenticatedUser actor) {
        ApplicationRecord application = requireApplication(applicationId, actor);
        BigDecimal total = collaterals.activeAllocatedForApplication(applicationId);
        BigDecimal coverage = CollateralValuationCalculator.coverage(total, application.amount());
        var items = collaterals.listForApplication(applicationId).stream()
                .map(allocation -> toResponse(allocation, application, coverage))
                .toList();
        return new LoanCollateralListResponse(application.amount(), total, coverage, items);
    }

    private LoanCollateralResponse response(UUID allocationId, ApplicationRecord application) {
        AllocationRecord allocation = collaterals.listForApplication(application.id()).stream()
                .filter(item -> item.id().equals(allocationId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Collateral allocation"));
        BigDecimal total = collaterals.activeAllocatedForApplication(application.id());
        return toResponse(allocation, application, CollateralValuationCalculator.coverage(total, application.amount()));
    }

    private LoanCollateralResponse toResponse(AllocationRecord allocation, ApplicationRecord application, BigDecimal coverage) {
        CollateralRecord collateral = collaterals.find(allocation.collateralId())
                .orElseThrow(() -> new ResourceNotFoundException("Collateral"));
        PledgePosition position = collaterals.position(collateral.id());
        boolean expired = collateral.valuationExpiresOn() != null && collateral.valuationExpiresOn().isBefore(CollateralSupport.today());
        BigDecimal available = collateral.verificationStatus() == com.mfb.lending.module.collateral.domain.VerificationStatus.APPROVED && !expired
                ? CollateralValuationCalculator.available(collateral.eligibleLendingValue(), position.allocatedAmount())
                : new BigDecimal("0.00");
        return CollateralViews.loanCollateral(allocation, collateral, available, coverage);
    }

    private ApplicationRecord requireApplication(UUID applicationId, AuthenticatedUser actor) {
        ApplicationRecord application = applications.find(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        CustomerRecord customer = customers.findById(application.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        ApplicationAccess.assertCanView(actor, application, customer);
        return application;
    }
}
