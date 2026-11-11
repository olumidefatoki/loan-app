package com.mfb.lending.module.collateral.application;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.collateral.domain.CollateralRecord;
import com.mfb.lending.module.collateral.domain.PledgePosition;
import com.mfb.lending.module.collateral.domain.ValuationDecision;
import com.mfb.lending.module.collateral.domain.ValuationKind;
import com.mfb.lending.module.collateral.domain.ValuationRecord;
import com.mfb.lending.module.collateral.domain.ValuationStatus;
import com.mfb.lending.module.collateral.dto.CreateValuationRequest;
import com.mfb.lending.module.collateral.dto.ValuationDecisionRequest;
import com.mfb.lending.module.collateral.dto.ValuationResponse;
import com.mfb.lending.module.collateral.persistence.CollateralRepository;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CollateralValuationService {

    private final CollateralRepository collaterals;
    private final CustomerRepository customers;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public CollateralValuationService(
            CollateralRepository collaterals,
            CustomerRepository customers,
            AuditService audit,
            TransactionTemplate transactions) {
        this.collaterals = collaterals;
        this.customers = customers;
        this.audit = audit;
        this.transactions = transactions;
    }

    public ValuationResponse add(UUID collateralId, CreateValuationRequest request, AuthenticatedUser actor, ClientContext client) {
        CollateralAccess.assertStaff(actor);
        LocalDate today = CollateralSupport.today();
        return transactions.execute(status -> {
            CollateralRecord collateral = collaterals.lock(collateralId)
                    .orElseThrow(() -> new ResourceNotFoundException("Collateral"));
            CollateralAccess.customer(customers, collateral.customerId(), actor);
            CollateralSupport.mutable(collateral.verificationStatus());
            if (collaterals.hasPendingValuation(collateralId)) {
                throw new ConflictException("A valuation is already awaiting approval");
            }
            boolean initialExists = collaterals.hasInitialValuation(collateralId);
            ValuationKind kind = request.kind() == null
                    ? (initialExists ? ValuationKind.REVALUATION : ValuationKind.INITIAL)
                    : request.kind();
            if (kind == ValuationKind.INITIAL && initialExists) {
                throw ValidationException.field("kind", "A revaluation is required after the initial valuation");
            }
            if (kind == ValuationKind.REVALUATION && !initialExists) {
                throw ValidationException.field("kind", "The first valuation must be an initial valuation");
            }
            CollateralValuationCalculator.EligibleValue value = CollateralValuationCalculator.value(
                    collateral.type(), request.marketValue(), request.forcedSaleValue(), request.eligibleLendingValue(), request.lendingRatio());
            LocalDate expiry = CollateralRules.expiry(request.valuationDate(), request.expiryDate(), today);
            ValuationRecord valuation = new ValuationRecord(
                    UUID.randomUUID(),
                    collateralId,
                    kind,
                    ValuationStatus.PENDING_APPROVAL,
                    value.marketValue(),
                    value.forcedSaleValue(),
                    value.lendingRatio(),
                    value.calculatedEligibleValue(),
                    value.acceptedEligibleValue(),
                    request.valuationDate(),
                    expiry,
                    request.valuerName().trim(),
                    CollateralSupport.text(request.valuerReference()),
                    actor.getId(),
                    null,
                    null,
                    null,
                    null);
            collaterals.insertValuation(valuation);
            audit.record("COLLATERAL_VALUATION_SUBMITTED", "collateral", collateralId.toString(), actor.getUsername(), client, Map.of(
                    "valuationId", valuation.id().toString(),
                    "kind", kind.name(),
                    "acceptedEligibleValue", value.acceptedEligibleValue()));
            return CollateralViews.valuation(collaterals.findValuation(collateralId, valuation.id())
                    .orElseThrow(() -> new ResourceNotFoundException("Valuation")));
        });
    }

    public ValuationResponse decide(
            UUID collateralId,
            UUID valuationId,
            ValuationDecisionRequest request,
            AuthenticatedUser actor,
            ClientContext client) {
        CollateralAccess.assertStaff(actor);
        String reason = CollateralSupport.text(request.reason());
        return transactions.execute(status -> {
            CollateralRecord collateral = collaterals.lock(collateralId)
                    .orElseThrow(() -> new ResourceNotFoundException("Collateral"));
            CollateralAccess.customer(customers, collateral.customerId(), actor);
            CollateralSupport.mutable(collateral.verificationStatus());
            ValuationRecord valuation = collaterals.lockValuation(collateralId, valuationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Valuation"));
            if (valuation.status() != ValuationStatus.PENDING_APPROVAL) {
                throw new ConflictException("This valuation has already been decided");
            }
            if (actor.getId().equals(valuation.submittedBy())) {
                throw new ForbiddenException("The officer who submitted this valuation cannot approve it");
            }
            if (request.decision() == ValuationDecision.REJECT) {
                if (reason == null) {
                    throw ValidationException.field("reason", "Reason is required");
                }
                collaterals.decideValuation(valuationId, ValuationStatus.REJECTED, actor.getId(), reason);
            } else {
                if (valuation.expiryDate().isBefore(CollateralSupport.today())) {
                    throw new ConflictException("Collateral valuation has expired");
                }
                PledgePosition position = collaterals.position(collateralId);
                if (valuation.acceptedEligibleValue().compareTo(position.allocatedAmount()) < 0) {
                    throw new ConflictException("Approved eligible value cannot fall below the amount already pledged");
                }
                collaterals.decideValuation(valuationId, ValuationStatus.APPROVED, actor.getId(), reason);
                collaterals.applyValuation(
                        collateralId,
                        valuation.marketValue(),
                        valuation.forcedSaleValue(),
                        valuation.acceptedEligibleValue(),
                        valuation.lendingRatio(),
                        valuation.valuationDate(),
                        valuation.expiryDate(),
                        valuation.valuerName(),
                        valuation.valuerReference(),
                        actor.getUsername());
            }
            audit.record("COLLATERAL_VALUATION_DECIDED", "collateral", collateralId.toString(), actor.getUsername(), client, Map.of(
                    "valuationId", valuationId.toString(),
                    "decision", request.decision().name(),
                    "acceptedEligibleValue", valuation.acceptedEligibleValue()));
            return CollateralViews.valuation(collaterals.findValuation(collateralId, valuationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Valuation")));
        });
    }
}
