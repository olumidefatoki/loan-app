package com.mfb.lending.module.collateral.application;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.collateral.domain.CheckOutcome;
import com.mfb.lending.module.collateral.domain.CheckStatus;
import com.mfb.lending.module.collateral.domain.CollateralDocumentCode;
import com.mfb.lending.module.collateral.domain.CollateralDocumentRecord;
import com.mfb.lending.module.collateral.domain.CollateralRecord;
import com.mfb.lending.module.collateral.domain.CollateralType;
import com.mfb.lending.module.collateral.domain.EncumbranceRecord;
import com.mfb.lending.module.collateral.domain.EncumbranceSource;
import com.mfb.lending.module.collateral.domain.EncumbranceStatus;
import com.mfb.lending.module.collateral.domain.InsuranceCheckStatus;
import com.mfb.lending.module.collateral.domain.InsuranceRecord;
import com.mfb.lending.module.collateral.domain.PledgePosition;
import com.mfb.lending.module.collateral.domain.PledgeStatus;
import com.mfb.lending.module.collateral.domain.ReleaseRecord;
import com.mfb.lending.module.collateral.domain.ValuationKind;
import com.mfb.lending.module.collateral.domain.ValuationRecord;
import com.mfb.lending.module.collateral.domain.ValuationStatus;
import com.mfb.lending.module.collateral.domain.VerificationStatus;
import com.mfb.lending.module.collateral.dto.CollateralDetailResponse;
import com.mfb.lending.module.collateral.dto.CollateralResponse;
import com.mfb.lending.module.collateral.dto.CreateCollateralRequest;
import com.mfb.lending.module.collateral.dto.InsuranceRequest;
import com.mfb.lending.module.collateral.dto.ReleaseCollateralRequest;
import com.mfb.lending.module.collateral.dto.StoredDocumentResponse;
import com.mfb.lending.module.collateral.dto.UpdateCollateralRequest;
import com.mfb.lending.module.collateral.dto.VerifyCollateralRequest;
import com.mfb.lending.module.collateral.persistence.CollateralRepository;
import com.mfb.lending.module.customer.document.DocumentInspector;
import com.mfb.lending.module.customer.document.InspectedDocument;
import com.mfb.lending.module.customer.document.ObjectStorage;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CollateralService {

    private final CollateralRepository collaterals;
    private final CustomerRepository customers;
    private final DocumentInspector inspector;
    private final ObjectStorage storage;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public CollateralService(
            CollateralRepository collaterals,
            CustomerRepository customers,
            DocumentInspector inspector,
            ObjectStorage storage,
            AuditService audit,
            TransactionTemplate transactions) {
        this.collaterals = collaterals;
        this.customers = customers;
        this.inspector = inspector;
        this.storage = storage;
        this.audit = audit;
        this.transactions = transactions;
    }

    public CollateralDetailResponse create(CreateCollateralRequest request, AuthenticatedUser actor, ClientContext client) {
        CollateralAccess.assertStaff(actor);
        CustomerRecord customer = CollateralAccess.customer(customers, request.customerId(), actor);
        LocalDate today = CollateralSupport.today();
        CollateralValuationCalculator.EligibleValue value = CollateralValuationCalculator.value(
                request.collateralType(),
                request.marketValue(),
                request.forcedSaleValue(),
                request.eligibleLendingValue(),
                request.lendingRatio());
        LocalDate expiry = CollateralRules.expiry(request.valuationDate(), request.expiryDate(), today);
        if (request.insurance() != null) {
            CollateralSupport.insuranceDates(request.insurance());
        }
        UUID id = UUID.randomUUID();
        CollateralDetailResponse response = transactions.execute(status -> {
            CollateralRecord collateral = new CollateralRecord(
                    id,
                    collaterals.nextReference(),
                    customer.id(),
                    request.collateralType(),
                    request.description().trim(),
                    request.ownerName().trim(),
                    CollateralSupport.text(request.ownerIdentifier()),
                    CollateralSupport.text(request.identificationNumber()),
                    CollateralSupport.text(request.location()),
                    value.marketValue(),
                    value.forcedSaleValue(),
                    null,
                    request.valuationDate(),
                    expiry,
                    request.valuerName().trim(),
                    CollateralSupport.text(request.valuerReference()),
                    value.lendingRatio(),
                    VerificationStatus.PENDING,
                    CheckStatus.PENDING,
                    CheckStatus.PENDING,
                    CheckStatus.PENDING,
                    InsuranceCheckStatus.PENDING,
                    EncumbranceStatus.UNKNOWN,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    actor.getUsername(),
                    actor.getUsername());
            collaterals.insert(collateral);
            collaterals.insertValuation(pendingValuation(id, ValuationKind.INITIAL, value, request.valuationDate(), expiry,
                    collateral.valuerName(), collateral.valuerReference(), actor.getId()));
            if (request.insurance() != null) {
                collaterals.insertInsurance(insurance(id, request.insurance(), actor.getUsername()));
            }
            audit.record("COLLATERAL_CREATED", "collateral", id.toString(), actor.getUsername(), client, Map.of(
                    "reference", collateral.reference(),
                    "customerId", customer.id().toString(),
                    "collateralType", request.collateralType().name(),
                    "calculatedEligibleValue", value.calculatedEligibleValue(),
                    "acceptedEligibleValue", value.acceptedEligibleValue()));
            return detail(collaterals.find(id).orElseThrow(() -> new ResourceNotFoundException("Collateral")));
        });
        return response;
    }

    public CollateralDetailResponse update(UUID id, UpdateCollateralRequest request, AuthenticatedUser actor, ClientContext client) {
        CollateralAccess.assertStaff(actor);
        if (request.description() == null && request.ownerName() == null && request.ownerIdentifier() == null
                && request.identificationNumber() == null && request.location() == null && request.insurance() == null) {
            throw ValidationException.field("description", "At least one field is required");
        }
        if (request.insurance() != null) {
            CollateralSupport.insuranceDates(request.insurance());
        }
        return transactions.execute(status -> {
            CollateralRecord current = locked(id);
            CollateralAccess.customer(customers, current.customerId(), actor);
            CollateralSupport.mutable(current.verificationStatus());
            String description = request.description() == null ? current.description() : required(request.description(), "description");
            String ownerName = request.ownerName() == null ? current.ownerName() : required(request.ownerName(), "ownerName");
            String ownerIdentifier = request.ownerIdentifier() == null
                    ? current.ownerIdentifier()
                    : CollateralSupport.text(request.ownerIdentifier());
            String identification = request.identificationNumber() == null
                    ? current.identificationNumber()
                    : CollateralSupport.text(request.identificationNumber());
            String location = request.location() == null ? current.location() : CollateralSupport.text(request.location());
            boolean identityChanged = !Objects.equals(identification, current.identificationNumber());
            PledgePosition position = collaterals.position(id);
            boolean pledged = position.exclusivePledges() + position.sharedPledges() > 0;
            if (identityChanged && pledged) {
                throw new ConflictException("Collateral identity cannot change while it is pledged");
            }
            if (request.insurance() != null && current.verificationStatus() == VerificationStatus.APPROVED && pledged) {
                throw new ConflictException("Insurance cannot be replaced while collateral is pledged");
            }
            collaterals.updateDetails(id, description, ownerName, ownerIdentifier, identification, location, actor.getUsername());
            CheckStatus ownership = current.ownershipStatus();
            InsuranceCheckStatus insurance = current.insuranceStatus();
            VerificationStatus verification = current.verificationStatus();
            boolean clearApproval = false;
            if (identityChanged && ownership == CheckStatus.VERIFIED) {
                ownership = CheckStatus.PENDING;
                clearApproval = verification == VerificationStatus.APPROVED;
            }
            if (request.insurance() != null) {
                collaterals.insertInsurance(insurance(id, request.insurance(), actor.getUsername()));
                insurance = InsuranceCheckStatus.PENDING;
                clearApproval = clearApproval || verification == VerificationStatus.APPROVED;
            }
            if (clearApproval) {
                verification = VerificationStatus.IN_REVIEW;
            }
            if (identityChanged || request.insurance() != null) {
                collaterals.updateVerification(
                        id,
                        verification,
                        ownership,
                        current.documentStatus(),
                        current.legalStatus(),
                        insurance,
                        current.encumbranceStatus(),
                        clearApproval ? null : current.approvedBy(),
                        !clearApproval && current.verificationStatus() == VerificationStatus.APPROVED,
                        null,
                        null,
                        false,
                        actor.getUsername());
            }
            audit.record("COLLATERAL_UPDATED", "collateral", id.toString(), actor.getUsername(), client, Map.of(
                    "identityChanged", identityChanged,
                    "insuranceReplaced", request.insurance() != null));
            return detail(collaterals.find(id).orElseThrow(() -> new ResourceNotFoundException("Collateral")));
        });
    }

    public CollateralDetailResponse get(UUID id, AuthenticatedUser actor) {
        return detail(CollateralAccess.visible(collaterals, customers, id, actor));
    }

    public PageResponse<CollateralResponse> list(
            AuthenticatedUser actor,
            UUID customerId,
            String type,
            String status,
            String query,
            int page,
            int size) {
        int offset = offset(page, size);
        CollateralType collateralType = type == null || type.isBlank() ? null : parseType(type);
        VerificationStatus verificationStatus = status == null || status.isBlank() ? null : parseStatus(status);
        UUID scopedCustomer = customerId;
        Collection<UUID> branches = List.of();
        boolean unrestricted = true;
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            CustomerRecord self = customers.findByUserId(actor.getId()).orElse(null);
            if (self == null || (customerId != null && !customerId.equals(self.id()))) {
                return new PageResponse<>(List.of(), page, size, 0);
            }
            scopedCustomer = self.id();
        } else if (!actor.unrestrictedBranches()) {
            unrestricted = false;
            branches = actor.branchIds();
            if (branches.isEmpty()) {
                return new PageResponse<>(List.of(), page, size, 0);
            }
        }
        LocalDate today = CollateralSupport.today();
        List<CollateralResponse> items = collaterals.search(
                        scopedCustomer, collateralType, verificationStatus, query, branches, unrestricted, offset, size)
                .stream()
                .map(record -> CollateralViews.collateral(record, collaterals.position(record.id()).allocatedAmount(), today))
                .toList();
        long total = collaterals.count(scopedCustomer, collateralType, verificationStatus, query, branches, unrestricted);
        return new PageResponse<>(items, page, size, total);
    }

    public StoredDocumentResponse upload(
            UUID id,
            CollateralDocumentCode code,
            MultipartFile file,
            AuthenticatedUser actor,
            ClientContext client) {
        CollateralAccess.assertStaff(actor);
        byte[] body = CollateralSupport.bytes(file);
        InspectedDocument inspected = inspector.inspect(file.getOriginalFilename(), file.getContentType(), body);
        UUID documentId = UUID.randomUUID();
        String key = "collaterals/" + id + "/" + documentId;
        storage.put(key, body);
        try {
            return transactions.execute(status -> {
                CollateralRecord collateral = locked(id);
                CollateralAccess.customer(customers, collateral.customerId(), actor);
                CollateralSupport.mutable(collateral.verificationStatus());
                CollateralDocumentRecord document = new CollateralDocumentRecord(
                        documentId, id, code, inspected.fileName(), inspected.contentType(), inspected.sizeBytes(),
                        inspected.checksumSha256(), key, null);
                collaterals.insertDocument(document, actor.getUsername());
                audit.record("COLLATERAL_DOCUMENT_STORED", "collateral", id.toString(), actor.getUsername(), client, Map.of(
                        "documentId", documentId.toString(),
                        "documentCode", code.name(),
                        "checksumSha256", inspected.checksumSha256()));
                return CollateralViews.document(collaterals.findDocument(id, documentId)
                        .orElseThrow(() -> new ResourceNotFoundException("Document")));
            });
        } catch (RuntimeException ex) {
            storage.delete(key);
            throw ex;
        }
    }

    public CollateralDocumentRecord download(UUID collateralId, UUID documentId, AuthenticatedUser actor) {
        CollateralAccess.visible(collaterals, customers, collateralId, actor);
        return collaterals.findDocument(collateralId, documentId)
                .orElseThrow(() -> new ResourceNotFoundException("Document"));
    }

    public byte[] content(CollateralDocumentRecord document) {
        return storage.get(document.storageKey());
    }

    public CollateralDetailResponse verify(UUID id, VerifyCollateralRequest request, AuthenticatedUser actor, ClientContext client) {
        CollateralAccess.assertStaff(actor);
        String notes = CollateralSupport.text(request.notes());
        return transactions.execute(status -> {
            CollateralRecord current = locked(id);
            CollateralAccess.customer(customers, current.customerId(), actor);
            if (current.verificationStatus() == VerificationStatus.RELEASED
                    || current.verificationStatus() == VerificationStatus.REJECTED) {
                throw new ConflictException("Collateral can no longer be verified");
            }
            if (request.check() != com.mfb.lending.module.collateral.domain.CollateralCheck.REJECT
                    && current.verificationStatus() == VerificationStatus.APPROVED) {
                throw new ConflictException("Approved collateral cannot be rechecked");
            }
            switch (request.check()) {
                case OWNERSHIP -> ownership(current, request.outcome(), notes, actor);
                case DOCUMENTS -> documents(current, request.outcome(), notes, actor);
                case LEGAL -> legal(current, request.outcome(), notes, actor);
                case INSURANCE -> insurance(current, request.outcome(), notes, actor);
                case ENCUMBRANCE -> encumbrance(current, request, notes, actor);
                case APPROVE -> approve(current, actor);
                case REJECT -> reject(current, notes, actor);
            }
            audit.record("COLLATERAL_VERIFIED", "collateral", id.toString(), actor.getUsername(), client, Map.of(
                    "check", request.check().name(),
                    "outcome", request.outcome() == null ? request.check().name() : request.outcome().name(),
                    "notes", notes == null ? "" : notes));
            return detail(collaterals.find(id).orElseThrow(() -> new ResourceNotFoundException("Collateral")));
        });
    }

    public CollateralDetailResponse release(UUID id, ReleaseCollateralRequest request, AuthenticatedUser actor, ClientContext client) {
        CollateralAccess.assertStaff(actor);
        return transactions.execute(status -> {
            CollateralRecord current = locked(id);
            CollateralAccess.customer(customers, current.customerId(), actor);
            CollateralRules.assertCanRelease(new CollateralRules.ReleaseCheck(
                    current.verificationStatus(),
                    current.approvedBy(),
                    actor.getId(),
                    collaterals.activeApplicationStatuses(id)));
            collaterals.insertRelease(new ReleaseRecord(
                    UUID.randomUUID(),
                    id,
                    request.settlementReference().trim(),
                    request.dischargeReference().trim(),
                    request.reason().trim(),
                    actor.getId(),
                    null));
            collaterals.releaseAllocations(id);
            collaterals.releaseEncumbrances(id);
            collaterals.markReleased(id, actor.getUsername());
            audit.record("COLLATERAL_RELEASED", "collateral", id.toString(), actor.getUsername(), client, Map.of(
                    "settlementReference", request.settlementReference().trim(),
                    "dischargeReference", request.dischargeReference().trim()));
            return detail(collaterals.find(id).orElseThrow(() -> new ResourceNotFoundException("Collateral")));
        });
    }

    private void ownership(CollateralRecord current, CheckOutcome outcome, String notes, AuthenticatedUser actor) {
        requireOutcome(outcome, CheckOutcome.VERIFIED, CheckOutcome.FAILED);
        if (outcome == CheckOutcome.VERIFIED && CollateralSupport.text(current.identificationNumber()) == null) {
            throw new ConflictException("Ownership verification needs an identification or registration number");
        }
        if (outcome == CheckOutcome.FAILED && notes == null) {
            throw ValidationException.field("notes", "Notes are required");
        }
        saveCheck(current, outcome == CheckOutcome.VERIFIED ? CheckStatus.VERIFIED : CheckStatus.FAILED,
                current.documentStatus(), current.legalStatus(), current.insuranceStatus(), current.encumbranceStatus(), actor);
    }

    private void documents(CollateralRecord current, CheckOutcome outcome, String notes, AuthenticatedUser actor) {
        requireOutcome(outcome, CheckOutcome.VERIFIED, CheckOutcome.FAILED);
        if (outcome == CheckOutcome.VERIFIED && !collaterals.hasDocument(current.id())) {
            throw new ConflictException("Collateral documents must be uploaded before they can be verified");
        }
        if (outcome == CheckOutcome.FAILED && notes == null) {
            throw ValidationException.field("notes", "Notes are required");
        }
        saveCheck(current, current.ownershipStatus(),
                outcome == CheckOutcome.VERIFIED ? CheckStatus.VERIFIED : CheckStatus.FAILED,
                current.legalStatus(), current.insuranceStatus(), current.encumbranceStatus(), actor);
    }

    private void legal(CollateralRecord current, CheckOutcome outcome, String notes, AuthenticatedUser actor) {
        requireOutcome(outcome, CheckOutcome.VERIFIED, CheckOutcome.FAILED);
        boolean evidence = collaterals.hasDocumentCode(current.id(), CollateralDocumentCode.LEGAL_OPINION, CollateralDocumentCode.SEARCH_REPORT);
        if (outcome == CheckOutcome.VERIFIED && !evidence) {
            throw new ConflictException("Legal verification needs a legal opinion or search report");
        }
        if (outcome == CheckOutcome.FAILED && notes == null) {
            throw ValidationException.field("notes", "Notes are required");
        }
        saveCheck(current, current.ownershipStatus(), current.documentStatus(),
                outcome == CheckOutcome.VERIFIED ? CheckStatus.VERIFIED : CheckStatus.FAILED,
                current.insuranceStatus(), current.encumbranceStatus(), actor);
    }

    private void insurance(CollateralRecord current, CheckOutcome outcome, String notes, AuthenticatedUser actor) {
        requireOutcome(outcome, CheckOutcome.VERIFIED, CheckOutcome.FAILED, CheckOutcome.NOT_REQUIRED);
        InsuranceCheckStatus next;
        if (outcome == CheckOutcome.NOT_REQUIRED) {
            if (!current.type().insuranceOptional()) {
                throw new ConflictException("Insurance must be verified");
            }
            next = InsuranceCheckStatus.NOT_REQUIRED;
        } else if (outcome == CheckOutcome.FAILED) {
            if (notes == null) {
                throw ValidationException.field("notes", "Notes are required");
            }
            next = InsuranceCheckStatus.FAILED;
        } else {
            InsuranceRecord policy = collaterals.latestInsurance(current.id()).orElse(null);
            boolean document = collaterals.hasDocumentCode(current.id(), CollateralDocumentCode.INSURANCE_POLICY);
            if (policy == null || !document || policy.coverAmount().signum() <= 0 || policy.expiryDate().isBefore(CollateralSupport.today())) {
                throw new ConflictException("Insurance must be verified");
            }
            next = InsuranceCheckStatus.VERIFIED;
        }
        saveCheck(current, current.ownershipStatus(), current.documentStatus(), current.legalStatus(), next, current.encumbranceStatus(), actor);
    }

    private void encumbrance(CollateralRecord current, VerifyCollateralRequest request, String notes, AuthenticatedUser actor) {
        requireOutcome(request.outcome(), CheckOutcome.CLEAR, CheckOutcome.ENCUMBERED);
        if (request.outcome() == CheckOutcome.ENCUMBERED) {
            String holder = CollateralSupport.text(request.encumbranceHolder());
            String reference = CollateralSupport.text(request.encumbranceReference());
            if (holder == null || reference == null || request.encumbranceAmount() == null) {
                throw ValidationException.field("encumbranceHolder", "Encumbrance holder, reference, and amount are required");
            }
            collaterals.insertEncumbrance(new EncumbranceRecord(
                    UUID.randomUUID(),
                    current.id(),
                    null,
                    EncumbranceSource.EXTERNAL,
                    holder,
                    reference,
                    CollateralSupport.money(request.encumbranceAmount()),
                    PledgeStatus.ACTIVE,
                    null,
                    null,
                    actor.getId()));
            saveCheck(current, current.ownershipStatus(), current.documentStatus(), current.legalStatus(),
                    current.insuranceStatus(), EncumbranceStatus.ENCUMBERED, actor);
            return;
        }
        collaterals.releaseExternalEncumbrances(current.id());
        saveCheck(current, current.ownershipStatus(), current.documentStatus(), current.legalStatus(),
                current.insuranceStatus(), EncumbranceStatus.CLEAR, actor);
        if (notes == null) {
            return;
        }
    }

    private void approve(CollateralRecord current, AuthenticatedUser actor) {
        if (current.verificationStatus() == VerificationStatus.APPROVED) {
            throw new ConflictException("Collateral is already approved");
        }
        ValuationRecord valuation = collaterals.latestApproved(current.id()).orElse(null);
        InsuranceRecord policy = collaterals.latestInsurance(current.id()).orElse(null);
        CollateralRules.assertApprovable(new CollateralRules.ApprovalCheck(
                current.type(),
                current.ownershipStatus(),
                current.documentStatus(),
                current.legalStatus(),
                current.insuranceStatus(),
                current.encumbranceStatus(),
                collaterals.hasDocument(current.id()),
                collaterals.hasDocumentCode(current.id(), CollateralDocumentCode.LEGAL_OPINION, CollateralDocumentCode.SEARCH_REPORT),
                collaterals.hasDocumentCode(current.id(), CollateralDocumentCode.INSURANCE_POLICY),
                policy == null ? null : policy.coverAmount(),
                policy == null ? null : policy.expiryDate(),
                collaterals.hasActiveExternalEncumbrance(current.id()),
                valuation == null ? null : valuation.status(),
                valuation == null ? null : valuation.expiryDate(),
                valuation == null ? null : valuation.acceptedEligibleValue(),
                CollateralSupport.today()));
        collaterals.updateVerification(
                current.id(),
                VerificationStatus.APPROVED,
                current.ownershipStatus(),
                current.documentStatus(),
                current.legalStatus(),
                current.insuranceStatus(),
                current.encumbranceStatus(),
                actor.getId(),
                true,
                null,
                null,
                false,
                actor.getUsername());
    }

    private void reject(CollateralRecord current, String notes, AuthenticatedUser actor) {
        if (notes == null) {
            throw ValidationException.field("notes", "Notes are required");
        }
        PledgePosition position = collaterals.position(current.id());
        if (position.exclusivePledges() + position.sharedPledges() > 0) {
            throw new ConflictException("Pledged collateral cannot be rejected");
        }
        collaterals.updateVerification(
                current.id(),
                VerificationStatus.REJECTED,
                current.ownershipStatus(),
                current.documentStatus(),
                current.legalStatus(),
                current.insuranceStatus(),
                current.encumbranceStatus(),
                null,
                false,
                actor.getId(),
                notes,
                true,
                actor.getUsername());
    }

    private void saveCheck(
            CollateralRecord current,
            CheckStatus ownership,
            CheckStatus documents,
            CheckStatus legal,
            InsuranceCheckStatus insurance,
            EncumbranceStatus encumbrance,
            AuthenticatedUser actor) {
        VerificationStatus verification = current.verificationStatus() == VerificationStatus.PENDING
                ? VerificationStatus.IN_REVIEW
                : current.verificationStatus();
        collaterals.updateVerification(
                current.id(),
                verification,
                ownership,
                documents,
                legal,
                insurance,
                encumbrance,
                null,
                false,
                null,
                null,
                false,
                actor.getUsername());
    }

    private static void requireOutcome(CheckOutcome outcome, CheckOutcome... allowed) {
        if (outcome == null) {
            throw ValidationException.field("outcome", "Outcome is required");
        }
        for (CheckOutcome candidate : allowed) {
            if (candidate == outcome) {
                return;
            }
        }
        throw ValidationException.field("outcome", "Outcome is not valid for this check");
    }

    private CollateralRecord locked(UUID id) {
        return collaterals.lock(id).orElseThrow(() -> new ResourceNotFoundException("Collateral"));
    }

    private CollateralDetailResponse detail(CollateralRecord collateral) {
        return CollateralViews.detail(
                collateral,
                collaterals.position(collateral.id()).allocatedAmount(),
                collaterals.listValuations(collateral.id()),
                collaterals.listDocuments(collateral.id()),
                collaterals.latestInsurance(collateral.id()).orElse(null),
                collaterals.listEncumbrances(collateral.id()),
                collaterals.listAllocations(collateral.id()),
                collaterals.listReleases(collateral.id()),
                CollateralSupport.today());
    }

    private static ValuationRecord pendingValuation(
            UUID collateralId,
            ValuationKind kind,
            CollateralValuationCalculator.EligibleValue value,
            LocalDate valuationDate,
            LocalDate expiry,
            String valuerName,
            String valuerReference,
            UUID submittedBy) {
        return new ValuationRecord(
                UUID.randomUUID(),
                collateralId,
                kind,
                ValuationStatus.PENDING_APPROVAL,
                value.marketValue(),
                value.forcedSaleValue(),
                value.lendingRatio(),
                value.calculatedEligibleValue(),
                value.acceptedEligibleValue(),
                valuationDate,
                expiry,
                valuerName,
                valuerReference,
                submittedBy,
                null,
                null,
                null,
                null);
    }

    private static InsuranceRecord insurance(UUID collateralId, InsuranceRequest request, String createdBy) {
        return new InsuranceRecord(
                UUID.randomUUID(),
                collateralId,
                request.insurerName().trim(),
                request.policyNumber().trim(),
                CollateralSupport.money(request.coverAmount()),
                request.startDate(),
                request.expiryDate(),
                null,
                createdBy);
    }

    private static String required(String value, String field) {
        String text = CollateralSupport.text(value);
        if (text == null) {
            throw ValidationException.field(field, field + " is required");
        }
        return text;
    }

    private static CollateralType parseType(String value) {
        try {
            return CollateralType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("type", "Unknown collateral type");
        }
    }

    private static VerificationStatus parseStatus(String value) {
        try {
            return VerificationStatus.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("status", "Unknown collateral status");
        }
    }

    private static int offset(int page, int size) {
        if (page < 0 || page > 10_000) {
            throw ValidationException.field("page", "page is out of range");
        }
        if (size < 1 || size > 100) {
            throw ValidationException.field("size", "size must be between 1 and 100");
        }
        long offset = (long) page * size;
        if (offset > Integer.MAX_VALUE) {
            throw ValidationException.field("page", "page is out of range");
        }
        return (int) offset;
    }

}
