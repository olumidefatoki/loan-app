package com.mfb.lending.module.collateral.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.collateral.domain.GuaranteeAcceptanceStatus;
import com.mfb.lending.module.collateral.domain.GuaranteeDecision;
import com.mfb.lending.module.collateral.domain.GuarantorDecision;
import com.mfb.lending.module.collateral.domain.GuarantorDocumentCode;
import com.mfb.lending.module.collateral.domain.GuarantorDocumentRecord;
import com.mfb.lending.module.collateral.domain.GuarantorRecord;
import com.mfb.lending.module.collateral.domain.GuarantorVerificationStatus;
import com.mfb.lending.module.collateral.domain.LoanGuarantorRecord;
import com.mfb.lending.module.collateral.dto.CreateGuarantorRequest;
import com.mfb.lending.module.collateral.dto.GuaranteeAcceptanceRequest;
import com.mfb.lending.module.collateral.dto.GuarantorResponse;
import com.mfb.lending.module.collateral.dto.GuarantorVerificationRequest;
import com.mfb.lending.module.collateral.dto.LinkGuarantorRequest;
import com.mfb.lending.module.collateral.dto.LoanGuarantorResponse;
import com.mfb.lending.module.collateral.dto.StoredDocumentResponse;
import com.mfb.lending.module.collateral.persistence.GuarantorRepository;
import com.mfb.lending.module.customer.application.IdentityNumbers;
import com.mfb.lending.module.customer.application.IdentitySeal;
import com.mfb.lending.module.customer.document.DocumentInspector;
import com.mfb.lending.module.customer.document.InspectedDocument;
import com.mfb.lending.module.customer.document.ObjectStorage;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.SealedIdentifier;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import com.mfb.lending.module.loanapplication.application.ApplicationAccess;
import com.mfb.lending.module.loanapplication.domain.ApplicationRecord;
import com.mfb.lending.module.loanapplication.persistence.LoanApplicationRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class GuarantorService {

    private final GuarantorRepository guarantors;
    private final CustomerRepository customers;
    private final LoanApplicationRepository applications;
    private final IdentitySeal identities;
    private final DocumentInspector inspector;
    private final ObjectStorage storage;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public GuarantorService(
            GuarantorRepository guarantors,
            CustomerRepository customers,
            LoanApplicationRepository applications,
            IdentitySeal identities,
            DocumentInspector inspector,
            ObjectStorage storage,
            AuditService audit,
            TransactionTemplate transactions) {
        this.guarantors = guarantors;
        this.customers = customers;
        this.applications = applications;
        this.identities = identities;
        this.inspector = inspector;
        this.storage = storage;
        this.audit = audit;
        this.transactions = transactions;
    }

    public GuarantorResponse create(CreateGuarantorRequest request, AuthenticatedUser actor, ClientContext client) {
        CollateralAccess.assertStaff(actor);
        String phone = IdentityNumbers.phone(request.phoneNumber(), "phoneNumber");
        String email = CollateralSupport.text(request.email());
        if (email != null) {
            email = IdentityNumbers.email(email, "email");
        }
        if (request.employmentStatus().requiresEmployer() && CollateralSupport.text(request.employerName()) == null) {
            throw ValidationException.field("employerName", "Employer name is required");
        }
        String bvn = IdentityNumbers.bvn(request.bvn());
        String nin = IdentityNumbers.nin(request.nin());
        SealedIdentifier sealedBvn = identities.seal(bvn);
        SealedIdentifier sealedNin = identities.seal(nin);
        if (guarantors.identityExists(sealedBvn.hash(), sealedNin.hash())) {
            throw new ConflictException("A guarantor with this identity is already registered");
        }
        UUID id = UUID.randomUUID();
        String storedEmail = email;
        return transactions.execute(status -> {
            GuarantorRecord guarantor = new GuarantorRecord(
                    id,
                    guarantors.nextReference(),
                    request.fullName().trim(),
                    phone,
                    storedEmail,
                    request.addressLine().trim(),
                    sealedBvn.last4(),
                    sealedNin.last4(),
                    request.bvnVerificationReference().trim(),
                    request.ninVerificationReference().trim(),
                    request.employmentStatus(),
                    CollateralSupport.text(request.employerName()),
                    CollateralSupport.text(request.jobTitle()),
                    CollateralSupport.money(request.monthlyIncome()),
                    CollateralSupport.money(request.monthlyObligations()),
                    GuarantorVerificationStatus.PENDING,
                    GuaranteeAcceptanceStatus.PENDING,
                    false,
                    true,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    actor.getUsername(),
                    actor.getUsername());
            guarantors.insert(guarantor, sealedBvn.ciphertext(), sealedBvn.hash(), sealedNin.ciphertext(), sealedNin.hash());
            guarantors.insertConsent(UUID.randomUUID(), id, request.consentStatement().trim(), actor.getId());
            audit.record("GUARANTOR_CREATED", "guarantor", id.toString(), actor.getUsername(), client, Map.of(
                    "reference", guarantor.reference(),
                    "consentRecorded", true,
                    "identitySealed", true,
                    "approvedSecurity", false));
            return response(id);
        });
    }

    public GuarantorResponse get(UUID id, AuthenticatedUser actor) {
        CollateralAccess.assertStaff(actor);
        return response(require(id).id());
    }

    public StoredDocumentResponse upload(
            UUID id,
            GuarantorDocumentCode code,
            MultipartFile file,
            AuthenticatedUser actor,
            ClientContext client) {
        CollateralAccess.assertStaff(actor);
        byte[] body = CollateralSupport.bytes(file);
        InspectedDocument inspected = inspector.inspect(file.getOriginalFilename(), file.getContentType(), body);
        UUID documentId = UUID.randomUUID();
        String key = "guarantors/" + id + "/" + documentId;
        storage.put(key, body);
        try {
            return transactions.execute(status -> {
                requireLocked(id);
                GuarantorDocumentRecord document = new GuarantorDocumentRecord(
                        documentId, id, code, inspected.fileName(), inspected.contentType(), inspected.sizeBytes(),
                        inspected.checksumSha256(), key, null);
                guarantors.insertDocument(document, actor.getUsername());
                audit.record("GUARANTOR_DOCUMENT_STORED", "guarantor", id.toString(), actor.getUsername(), client, Map.of(
                        "documentId", documentId.toString(),
                        "documentCode", code.name(),
                        "checksumSha256", inspected.checksumSha256()));
                return CollateralViews.document(guarantors.findDocument(id, documentId)
                        .orElseThrow(() -> new ResourceNotFoundException("Document")));
            });
        } catch (RuntimeException ex) {
            storage.delete(key);
            throw ex;
        }
    }

    public GuarantorDocumentRecord download(UUID guarantorId, UUID documentId, AuthenticatedUser actor) {
        CollateralAccess.assertStaff(actor);
        require(guarantorId);
        return guarantors.findDocument(guarantorId, documentId).orElseThrow(() -> new ResourceNotFoundException("Document"));
    }

    public byte[] content(GuarantorDocumentRecord document) {
        return storage.get(document.storageKey());
    }

    public GuarantorResponse verify(UUID id, GuarantorVerificationRequest request, AuthenticatedUser actor, ClientContext client) {
        CollateralAccess.assertStaff(actor);
        String notes = CollateralSupport.text(request.notes());
        return transactions.execute(status -> {
            GuarantorRecord current = requireLocked(id);
            if (!current.consentRecorded()) {
                throw new ConflictException("Guarantor consent must be recorded");
            }
            if (request.decision() == GuarantorDecision.REJECT) {
                if (notes == null) {
                    throw ValidationException.field("notes", "Notes are required");
                }
                guarantors.markVerificationRejected(id, actor.getId(), notes, actor.getUsername());
                guarantors.syncLinks(id, false);
            } else {
                if (current.verificationStatus() == GuarantorVerificationStatus.VERIFIED) {
                    throw new ConflictException("Guarantor is already verified");
                }
                guarantors.markVerified(id, actor.getId(), actor.getUsername());
            }
            audit.record("GUARANTOR_VERIFIED", "guarantor", id.toString(), actor.getUsername(), client, Map.of(
                    "decision", request.decision().name(),
                    "approvedSecurity", false));
            return response(id);
        });
    }

    public GuarantorResponse accept(UUID id, GuaranteeAcceptanceRequest request, AuthenticatedUser actor, ClientContext client) {
        CollateralAccess.assertStaff(actor);
        String notes = CollateralSupport.text(request.notes());
        return transactions.execute(status -> {
            GuarantorRecord current = requireLocked(id);
            if (!current.consentRecorded()) {
                throw new ConflictException("Guarantor consent must be recorded");
            }
            if (current.verificationStatus() != GuarantorVerificationStatus.VERIFIED || current.verifiedBy() == null) {
                throw new ConflictException("Guarantor verification is incomplete");
            }
            if (actor.getId().equals(current.verifiedBy())) {
                throw new ForbiddenException("The officer who verified this guarantor cannot accept the guarantee");
            }
            if (request.decision() == GuaranteeDecision.REJECT) {
                if (notes == null) {
                    throw ValidationException.field("notes", "Notes are required");
                }
                guarantors.markAcceptanceRejected(id, actor.getId(), notes, actor.getUsername());
                guarantors.syncLinks(id, false);
            } else {
                if (!guarantors.hasAgreement(id)) {
                    throw new ConflictException("A guarantee agreement must be on record");
                }
                guarantors.markAccepted(id, actor.getId(), actor.getUsername());
                guarantors.syncLinks(id, true);
            }
            GuarantorRecord updated = guarantors.find(id).orElseThrow(() -> new ResourceNotFoundException("Guarantor"));
            audit.record("GUARANTOR_ACCEPTANCE_DECIDED", "guarantor", id.toString(), actor.getUsername(), client, Map.of(
                    "decision", request.decision().name(),
                    "approvedSecurity", updated.approvedSecurity()));
            return response(id);
        });
    }

    public LoanGuarantorResponse link(
            UUID applicationId,
            LinkGuarantorRequest request,
            AuthenticatedUser actor,
            ClientContext client) {
        CollateralAccess.assertStaff(actor);
        ApplicationRecord application = requireApplication(applicationId, actor);
        if (!CollateralRules.pledgeable(application.status())) {
            throw new ConflictException("A guarantor cannot be linked to this application");
        }
        return transactions.execute(status -> {
            GuarantorRecord guarantor = requireLocked(request.guarantorId());
            boolean approved = guarantor.approvedSecurity() && guarantors.hasAgreement(guarantor.id());
            LoanGuarantorRecord link = new LoanGuarantorRecord(
                    UUID.randomUUID(),
                    applicationId,
                    guarantor.id(),
                    request.relationship().trim(),
                    approved,
                    null,
                    actor.getId());
            try {
                guarantors.insertLink(link);
            } catch (DuplicateKeyException ex) {
                throw new ConflictException("Guarantor is already linked to this application");
            }
            audit.record("GUARANTOR_LINKED", "guarantor", guarantor.id().toString(), actor.getUsername(), client, Map.of(
                    "applicationId", applicationId.toString(),
                    "approvedSecurity", approved));
            LoanGuarantorRecord stored = guarantors.listLinks(applicationId).stream()
                    .filter(item -> item.id().equals(link.id()))
                    .findFirst()
                    .orElseThrow(() -> new ResourceNotFoundException("Guarantor"));
            return CollateralViews.loanGuarantor(stored, guarantors.find(guarantor.id())
                    .orElseThrow(() -> new ResourceNotFoundException("Guarantor")));
        });
    }

    public List<LoanGuarantorResponse> listLinks(UUID applicationId, AuthenticatedUser actor) {
        requireApplication(applicationId, actor);
        return guarantors.listLinks(applicationId).stream()
                .map(link -> CollateralViews.loanGuarantor(link, guarantors.find(link.guarantorId())
                        .orElseThrow(() -> new ResourceNotFoundException("Guarantor"))))
                .toList();
    }

    private GuarantorResponse response(UUID id) {
        GuarantorRecord guarantor = guarantors.find(id).orElseThrow(() -> new ResourceNotFoundException("Guarantor"));
        return CollateralViews.guarantor(guarantor, guarantors.listDocuments(id));
    }

    private GuarantorRecord require(UUID id) {
        return guarantors.find(id).orElseThrow(() -> new ResourceNotFoundException("Guarantor"));
    }

    private GuarantorRecord requireLocked(UUID id) {
        return guarantors.lock(id).orElseThrow(() -> new ResourceNotFoundException("Guarantor"));
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
