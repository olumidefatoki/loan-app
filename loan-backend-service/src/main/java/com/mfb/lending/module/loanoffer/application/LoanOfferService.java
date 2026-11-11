package com.mfb.lending.module.loanoffer.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.config.OfferProperties;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.collateral.domain.AllocationRecord;
import com.mfb.lending.module.collateral.domain.PledgeStatus;
import com.mfb.lending.module.collateral.persistence.CollateralRepository;
import com.mfb.lending.module.collateral.persistence.GuarantorRepository;
import com.mfb.lending.module.customer.application.CustomerAccess;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.CustomerStatus;
import com.mfb.lending.module.customer.domain.KycStatus;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.CalculatedFee;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.CalculatedInstalment;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.FeeInput;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.LoanQuote;
import com.mfb.lending.module.loancalculation.LoanCalculationEngine.LoanQuoteRequest;
import com.mfb.lending.module.loanapplication.application.ApplicationWorkflow;
import com.mfb.lending.module.loanapplication.domain.ApplicationRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;
import com.mfb.lending.module.loanapplication.persistence.LoanApplicationRepository;
import com.mfb.lending.module.loanoffer.domain.AgreementAcceptance;
import com.mfb.lending.module.loanoffer.domain.AgreementKind;
import com.mfb.lending.module.loanoffer.domain.AgreementTemplate;
import com.mfb.lending.module.loanoffer.domain.LoanOffer;
import com.mfb.lending.module.loanoffer.domain.OfferStatus;
import com.mfb.lending.module.loanoffer.domain.OfferTerms;
import com.mfb.lending.module.loanoffer.domain.OfferVersion;
import com.mfb.lending.module.loanoffer.domain.ScheduleItem;
import com.mfb.lending.module.loanoffer.domain.StoredAgreement;
import com.mfb.lending.module.loanoffer.dto.AcceptOfferRequest;
import com.mfb.lending.module.loanoffer.dto.AgreementResponse;
import com.mfb.lending.module.loanoffer.dto.GenerateOfferRequest;
import com.mfb.lending.module.loanoffer.dto.LoanOfferResponse;
import com.mfb.lending.module.loanoffer.dto.LoanOfferSummaryResponse;
import com.mfb.lending.module.loanoffer.dto.OfferReasonRequest;
import com.mfb.lending.module.loanoffer.dto.ScheduleItemResponse;
import com.mfb.lending.module.loanoffer.persistence.LoanOfferRepository;
import com.mfb.lending.module.loanproduct.domain.LoanProductRecord;
import com.mfb.lending.module.loanproduct.domain.LoanProductVersionRecord;
import com.mfb.lending.module.loanproduct.domain.ProductComponents;
import com.mfb.lending.module.loanproduct.domain.RoundingMethod;
import com.mfb.lending.module.loanproduct.persistence.LoanProductComponentRepository;
import com.mfb.lending.module.loanproduct.persistence.LoanProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LoanOfferService {

    private final LoanOfferRepository offers;
    private final LoanApplicationRepository applications;
    private final CustomerRepository customers;
    private final LoanProductRepository products;
    private final LoanProductComponentRepository components;
    private final CollateralRepository collaterals;
    private final GuarantorRepository guarantors;
    private final AgreementFileStore files;
    private final ElectronicSignaturePort signatures;
    private final OfferViews views;
    private final OfferProperties properties;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactions;
    private final TransactionTemplate committed;
    private final java.time.Clock clock;

    public LoanOfferService(
            LoanOfferRepository offers,
            LoanApplicationRepository applications,
            CustomerRepository customers,
            LoanProductRepository products,
            LoanProductComponentRepository components,
            CollateralRepository collaterals,
            GuarantorRepository guarantors,
            AgreementFileStore files,
            ElectronicSignaturePort signatures,
            OfferViews views,
            OfferProperties properties,
            AuditService audit,
            ObjectMapper objectMapper,
            TransactionTemplate transactions,
            java.time.Clock clock) {
        this.offers = offers;
        this.applications = applications;
        this.customers = customers;
        this.products = products;
        this.components = components;
        this.collaterals = collaterals;
        this.guarantors = guarantors;
        this.files = files;
        this.signatures = signatures;
        this.views = views;
        this.properties = properties;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.transactions = transactions;
        this.committed = new TransactionTemplate(transactions.getTransactionManager());
        this.committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    public LoanOfferResponse generate(UUID applicationId, GenerateOfferRequest request, AuthenticatedUser actor, ClientContext client) {
        persistExpiryForApplication(applicationId, actor);
        List<String> written = new ArrayList<>();
        try {
            LoanOfferResponse response = transactions.execute(status -> generateLocked(applicationId, request, actor, written));
            audit.record("LOAN_OFFER_GENERATED", "loan_offer", response.id().toString(), actor.getUsername(), client, Map.of(
                    "reference", response.reference(),
                    "status", response.status(),
                    "offerVersion", response.offerVersion(),
                    "fingerprint", response.calculationFingerprint()));
            return response;
        } catch (RuntimeException ex) {
            written.forEach(files::delete);
            throw ex;
        }
    }

    public LoanOfferResponse get(UUID id, AuthenticatedUser actor) {
        return transactions.execute(status -> {
            Loaded loaded = load(id, actor, true);
            return present(loaded.offer(), loaded.version());
        });
    }

    public PageResponse<LoanOfferSummaryResponse> mine(AuthenticatedUser actor, int page, int size) {
        CustomerRecord customer = customers.findByUserId(actor.getId()).orElseThrow(() -> new ResourceNotFoundException("Customer"));
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        return transactions.execute(status -> {
            List<LoanOffer> rows = offers.listForCustomer(customer.id(), safeSize, safePage * safeSize);
            List<LoanOfferSummaryResponse> items = new ArrayList<>();
            for (LoanOffer row : rows) {
                ApplicationRecord application = applications.lock(row.applicationId()).orElseThrow(() -> new ResourceNotFoundException("Loan application"));
                LoanOffer current = applyExpiry(offers.lock(row.id()).orElseThrow(), application, actor, Instant.now(clock));
                OfferTerms terms = offers.findTerms(current.currentVersionId()).orElseThrow(() -> new ResourceNotFoundException("Loan offer"));
                items.add(views.summary(current, terms));
            }
            return new PageResponse<>(items, safePage, safeSize, offers.countForCustomer(customer.id()));
        });
    }

    public LoanOfferResponse accept(UUID id, AcceptOfferRequest request, AuthenticatedUser actor, ClientContext client) {
        persistExpiry(id, actor);
        LoanOfferResponse response = transactions.execute(status -> acceptLocked(id, request, actor, client));
        audit.record("LOAN_OFFER_ACCEPTED", "loan_offer", id.toString(), actor.getUsername(), client, Map.of(
                "status", response.status(),
                "signatureAssurance", "RECORDED_ACCEPTANCE"));
        return response;
    }

    public LoanOfferResponse decline(UUID id, OfferReasonRequest request, AuthenticatedUser actor, ClientContext client) {
        persistExpiry(id, actor);
        LoanOfferResponse response = transactions.execute(status -> closeLocked(id, request, actor, OfferStatus.DECLINED));
        audit.record("LOAN_OFFER_DECLINED", "loan_offer", id.toString(), actor.getUsername(), client, Map.of("status", response.status()));
        return response;
    }

    public LoanOfferResponse cancel(UUID id, OfferReasonRequest request, AuthenticatedUser actor, ClientContext client) {
        persistExpiry(id, actor);
        LoanOfferResponse response = transactions.execute(status -> closeLocked(id, request, actor, OfferStatus.CANCELLED));
        audit.record("LOAN_OFFER_CANCELLED", "loan_offer", id.toString(), actor.getUsername(), client, Map.of("status", response.status()));
        return response;
    }

    public List<ScheduleItemResponse> schedule(UUID id, Integer version, AuthenticatedUser actor) {
        return transactions.execute(status -> {
            Loaded loaded = load(id, actor, version == null);
            OfferVersion selected = selectVersion(loaded.offer(), version);
            return views.schedule(offers.listSchedule(selected.id()));
        });
    }

    public AgreementResponse agreement(UUID id, Integer version, String kind, AuthenticatedUser actor) {
        return transactions.execute(status -> {
            Loaded loaded = load(id, actor, version == null);
            OfferVersion selected = selectVersion(loaded.offer(), version);
            StoredAgreement agreement = agreement(selected, kind(kind));
            return views.agreement(
                    loaded.offer(),
                    selected,
                    agreement,
                    offers.findAcceptance(agreement.id()).orElse(null),
                    offers.listAgreements(selected.id()));
        });
    }

    public DocumentFile document(UUID id, Integer version, String kind, AuthenticatedUser actor) {
        return transactions.execute(status -> {
            Loaded loaded = load(id, actor, version == null);
            OfferVersion selected = selectVersion(loaded.offer(), version);
            StoredAgreement agreement = agreement(selected, kind(kind));
            byte[] body = files.readVerified(agreement.storageKey(), agreement.checksumSha256());
            return new DocumentFile(agreement.fileName(), agreement.checksumSha256(), body);
        });
    }

    private LoanOfferResponse generateLocked(UUID applicationId, GenerateOfferRequest request, AuthenticatedUser actor, List<String> written) {
        Instant now = Instant.now(clock);
        ApplicationRecord application = applications.lock(applicationId).orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        CustomerRecord customer = customers.findById(application.customerId()).orElseThrow(() -> new ResourceNotFoundException("Customer"));
        assertStaff(actor, application);
        if (application.status() != ApplicationStatus.APPROVED && application.status() != ApplicationStatus.OFFER_ISSUED) {
            throw new ConflictException("Only a finally approved application can receive an offer");
        }
        LoanOffer existing = offers.lockByApplication(application.id()).orElse(null);
        if (existing != null) {
            existing = applyExpiry(existing, application, actor, now);
            application = applications.find(application.id()).orElseThrow();
            if (existing.status() == OfferStatus.EXPIRED && application.status() != ApplicationStatus.APPROVED) {
                throw new ConflictException("This offer has expired");
            }
        }
        if (application.status() != ApplicationStatus.APPROVED && application.status() != ApplicationStatus.OFFER_ISSUED) {
            throw new ConflictException("Only a finally approved application can receive an offer");
        }
        Instant expiresAt = expiry(request, now);
        if ((request == null || request.expiresOn() == null)
                && existing != null
                && existing.expiresAt().isAfter(now)) {
            expiresAt = existing.expiresAt();
        }
        Prepared prepared = prepare(application, customer, request, now, expiresAt);
        if (existing != null && existing.status() == OfferStatus.ACCEPTED) {
            OfferVersion current = offers.findVersion(existing.currentVersionId()).orElseThrow();
            if (current.fingerprint().equals(prepared.quote().fingerprint())) {
                return present(existing, current);
            }
            throw new ConflictException("Accepted terms cannot be replaced. A material change needs a new offer before acceptance");
        }
        if (existing != null && existing.status() == OfferStatus.DECLINED) {
            throw new ConflictException("A declined offer cannot be reissued");
        }
        boolean issue = request == null || request.issue() == null || request.issue();
        if (existing != null && (existing.status() == OfferStatus.DRAFT || existing.status() == OfferStatus.ISSUED)) {
            OfferVersion current = offers.findVersion(existing.currentVersionId()).orElseThrow();
            if (current.fingerprint().equals(prepared.quote().fingerprint())) {
                return refreshSameTerms(existing, current, application, prepared.expiresAt(), issue, actor, now);
            }
        }
        if (existing != null && existing.status() != OfferStatus.DRAFT && existing.status() != OfferStatus.ISSUED
                && application.status() != ApplicationStatus.APPROVED) {
            throw new ConflictException("This offer can no longer be changed");
        }
        return publish(existing, application, customer, prepared, issue, actor, now, written);
    }

    private LoanOfferResponse refreshSameTerms(
            LoanOffer existing,
            OfferVersion current,
            ApplicationRecord application,
            Instant expiresAt,
            boolean issue,
            AuthenticatedUser actor,
            Instant now) {
        boolean promote = existing.status() == OfferStatus.DRAFT && issue;
        boolean expiryChanged = !existing.expiresAt().equals(expiresAt);
        if (!promote && !expiryChanged) {
            return present(existing, current);
        }
        OfferStatus status = promote ? OfferStatus.ISSUED : existing.status();
        LoanOffer next = copy(existing, status, existing.currentVersionId(), expiresAt, null, null, null, null, existing.version() + 1, actor.getUsername());
        if (!offers.save(next, existing.version())) {
            throw new ConflictException("This offer was updated by another request");
        }
        if (promote) {
            move(application, ApplicationStatus.OFFER_ISSUED, "Offer issued", actor, now);
            offers.insertHistory(UUID.randomUUID(), existing.id(), existing.status(), OfferStatus.ISSUED, current.versionNumber(), "Offer issued", actor.getId(), now);
        }
        return present(offers.find(existing.id()).orElseThrow(), current);
    }

    private LoanOfferResponse publish(
            LoanOffer existing,
            ApplicationRecord application,
            CustomerRecord customer,
            Prepared prepared,
            boolean issue,
            AuthenticatedUser actor,
            Instant now,
            List<String> written) {
        OfferStatus status = issue || (existing != null && existing.status() == OfferStatus.ISSUED)
                ? OfferStatus.ISSUED
                : OfferStatus.DRAFT;
        if (existing != null && (existing.status() == OfferStatus.EXPIRED || existing.status() == OfferStatus.CANCELLED)) {
            status = OfferStatus.ISSUED;
        }
        UUID offerId = existing == null ? UUID.randomUUID() : existing.id();
        String reference = existing == null ? offers.nextReference() : existing.reference();
        int versionNumber = existing == null ? 1 : offers.findVersion(existing.currentVersionId()).orElseThrow().versionNumber() + 1;
        if (existing == null) {
            offers.insertOffer(new LoanOffer(
                    offerId, reference, application.id(), customer.id(), OfferStatus.DRAFT, null, prepared.expiresAt(),
                    null, null, null, null, 0, null, null, actor.getUsername(), actor.getUsername()));
        } else if (existing.currentVersionId() != null) {
            offers.supersedeVersion(existing.currentVersionId(), now);
        }
        UUID versionId = UUID.randomUUID();
        offers.insertVersion(new OfferVersion(versionId, offerId, versionNumber, prepared.quote().fingerprint(), prepared.quote().engineVersion(), null, null, actor.getUsername()));
        OfferTerms terms = terms(versionId, application, customer, prepared);
        offers.insertTerms(terms);
        offers.insertSchedule(schedule(versionId, prepared.quote()));
        String snapshot = snapshot(prepared);
        List<Rendered> documents = render(prepared, terms, prepared.expiresAt());
        for (Rendered document : documents) {
            String key = "offers/" + offerId + "/" + versionId + "/" + document.kind().name().toLowerCase(java.util.Locale.ROOT) + ".pdf";
            files.put(key, document.pdf());
            written.add(key);
            offers.insertAgreement(new StoredAgreement(
                    UUID.randomUUID(),
                    versionId,
                    document.kind(),
                    document.template().id(),
                    document.template().versionNumber(),
                    versionNumber,
                    document.title(),
                    reference + "-" + document.kind().name().toLowerCase(java.util.Locale.ROOT) + "-v" + versionNumber + ".pdf",
                    "application/pdf",
                    key,
                    LoanCalculationEngine.sha256(document.pdf()),
                    document.pdf().length,
                    document.text(),
                    null,
                    now,
                    actor.getUsername()), prepared.product().id(), prepared.version().id(), snapshot);
        }
        if (existing != null) {
            offers.supersedeAgreements(offerId, versionId, now);
        }
        int expected = existing == null ? 0 : existing.version();
        LoanOffer next = new LoanOffer(
                offerId,
                reference,
                application.id(),
                customer.id(),
                status,
                versionId,
                prepared.expiresAt(),
                null,
                null,
                null,
                null,
                expected + 1,
                null,
                null,
                actor.getUsername(),
                actor.getUsername());
        if (!offers.save(next, expected)) {
            throw new ConflictException("This offer was updated by another request");
        }
        OfferStatus from = existing == null ? null : existing.status();
        offers.insertHistory(UUID.randomUUID(), offerId, from, status, versionNumber,
                from == null ? "Offer generated" : "Material terms changed and need a fresh acceptance", actor.getId(), now);
        if (status == OfferStatus.ISSUED && application.status() == ApplicationStatus.APPROVED) {
            move(application, ApplicationStatus.OFFER_ISSUED, "Offer issued", actor, now);
        }
        return present(offers.find(offerId).orElseThrow(), offers.findVersion(versionId).orElseThrow());
    }

    private LoanOfferResponse acceptLocked(UUID id, AcceptOfferRequest request, AuthenticatedUser actor, ClientContext client) {
        Instant now = Instant.now(clock);
        Loaded loaded = load(id, actor, false);
        LoanOffer offer = loaded.offer();
        CustomerRecord customer = loaded.customer();
        if (!CustomerAccess.isOwner(actor, customer)) {
            throw new ResourceNotFoundException("Loan offer");
        }
        boolean due = offer.status().open() && !offer.expiresAt().isAfter(now);
        offer = applyExpiry(offer, loaded.application(), actor, now);
        if (due || offer.status() == OfferStatus.EXPIRED) {
            throw new ConflictException("This offer has expired");
        }
        if (offer.version() != request.version()) {
            throw new ConflictException("This offer was updated by another request");
        }
        if (offer.status() != OfferStatus.ISSUED) {
            throw new ConflictException("Only an issued offer can be accepted");
        }
        if (!Boolean.TRUE.equals(request.agreementRead())) {
            throw ValidationException.field("agreementRead", "The agreement must be read before it can be accepted");
        }
        String identity = request.identityVerificationReference().trim();
        if (customer.kycStatus() != KycStatus.VERIFIED) {
            throw ValidationException.field("identityVerificationReference", "Customer identity is not verified");
        }
        if (customer.complianceHold() || customer.blacklisted() || customer.status() == CustomerStatus.BLACKLISTED) {
            throw new ConflictException("This customer cannot accept an offer while a compliance hold or blacklist is in force");
        }
        StoredAgreement agreement = offers.findAgreement(offer.currentVersionId(), AgreementKind.LOAN)
                .orElseThrow(() -> new ConflictException("The loan agreement is not available"));
        if (request.expectedChecksum() != null && !request.expectedChecksum().isBlank()
                && !request.expectedChecksum().trim().equalsIgnoreCase(agreement.checksumSha256())) {
            throw ValidationException.field("expectedChecksum", "The agreement checksum does not match the document you reviewed");
        }
        files.readVerified(agreement.storageKey(), agreement.checksumSha256());
        ElectronicSignaturePort.SignatureEvidence evidence = signatures.accept(new ElectronicSignaturePort.SignatureCommand(
                offer.id(), agreement.id(), agreement.checksumSha256(), customer.id(), identity, now));
        AgreementAcceptance acceptance = new AgreementAcceptance(
                UUID.randomUUID(),
                agreement.id(),
                offer.id(),
                customer.id(),
                evidence.signedAt(),
                identity,
                customer.kycStatus().name(),
                evidence.provider(),
                evidence.assurance(),
                evidence.reference(),
                evidence.disclaimer(),
                loaded.application().channel().name(),
                client == null ? null : client.ipAddress(),
                client == null ? null : client.userAgent(),
                actor.getId());
        offers.insertAcceptance(acceptance);
        LoanOffer accepted = copy(offer, OfferStatus.ACCEPTED, offer.currentVersionId(), offer.expiresAt(), now, null, null, null, offer.version() + 1, actor.getUsername());
        if (!offers.save(accepted, offer.version())) {
            throw new ConflictException("This offer was updated by another request");
        }
        offers.insertHistory(UUID.randomUUID(), offer.id(), OfferStatus.ISSUED, OfferStatus.ACCEPTED, loaded.version().versionNumber(),
                "Customer accepted the agreement", actor.getId(), now);
        if (loaded.application().status() == ApplicationStatus.OFFER_ISSUED) {
            move(loaded.application(), ApplicationStatus.OFFER_ACCEPTED, "Offer accepted", actor, now);
        }
        return present(offers.find(offer.id()).orElseThrow(), loaded.version());
    }

    private LoanOfferResponse closeLocked(UUID id, OfferReasonRequest request, AuthenticatedUser actor, OfferStatus target) {
        Instant now = Instant.now(clock);
        Loaded loaded = load(id, actor, false);
        LoanOffer offer = loaded.offer();
        if (target == OfferStatus.DECLINED && !CustomerAccess.isOwner(actor, loaded.customer())) {
            throw new ResourceNotFoundException("Loan offer");
        }
        if (target == OfferStatus.CANCELLED) {
            assertStaff(actor, loaded.application());
        }
        boolean due = offer.status().open() && !offer.expiresAt().isAfter(now);
        offer = applyExpiry(offer, loaded.application(), actor, now);
        if (due || offer.status() == OfferStatus.EXPIRED) {
            throw new ConflictException("This offer has expired");
        }
        if (offer.version() != request.version()) {
            throw new ConflictException("This offer was updated by another request");
        }
        if (offer.status() != OfferStatus.ISSUED && !(target == OfferStatus.CANCELLED && offer.status() == OfferStatus.DRAFT)) {
            throw new ConflictException("This offer cannot be " + target.name().toLowerCase());
        }
        String reason = request.reason().trim();
        LoanOffer closed = copy(
                offer,
                target,
                offer.currentVersionId(),
                offer.expiresAt(),
                null,
                target == OfferStatus.DECLINED ? now : null,
                target == OfferStatus.CANCELLED ? now : null,
                reason,
                offer.version() + 1,
                actor.getUsername());
        if (!offers.save(closed, offer.version())) {
            throw new ConflictException("This offer was updated by another request");
        }
        offers.insertHistory(UUID.randomUUID(), offer.id(), offer.status(), target, loaded.version().versionNumber(), reason, actor.getId(), now);
        if (target == OfferStatus.DECLINED && loaded.application().status() == ApplicationStatus.OFFER_ISSUED) {
            move(loaded.application(), ApplicationStatus.WITHDRAWN, reason, actor, now);
        }
        if (target == OfferStatus.CANCELLED && loaded.application().status() == ApplicationStatus.OFFER_ISSUED) {
            move(loaded.application(), ApplicationStatus.CANCELLED, reason, actor, now);
        }
        return present(offers.find(offer.id()).orElseThrow(), loaded.version());
    }

    private void persistExpiry(UUID offerId, AuthenticatedUser actor) {
        committed.executeWithoutResult(status -> {
            LoanOffer peek = offers.find(offerId).orElseThrow(() -> new ResourceNotFoundException("Loan offer"));
            ApplicationRecord application = applications.lock(peek.applicationId())
                    .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
            LoanOffer offer = offers.lock(offerId).orElseThrow(() -> new ResourceNotFoundException("Loan offer"));
            applyExpiry(offer, application, actor, Instant.now(clock));
        });
    }

    private void persistExpiryForApplication(UUID applicationId, AuthenticatedUser actor) {
        committed.executeWithoutResult(status -> {
            ApplicationRecord application = applications.lock(applicationId).orElse(null);
            if (application == null) {
                return;
            }
            offers.lockByApplication(applicationId).ifPresent(offer -> applyExpiry(offer, application, actor, Instant.now(clock)));
        });
    }

    private LoanOffer applyExpiry(LoanOffer offer, ApplicationRecord application, AuthenticatedUser actor, Instant now) {
        if (!offer.status().open() || offer.expiresAt().isAfter(now)) {
            return offer;
        }
        int offerVersion = offers.findVersion(offer.currentVersionId()).map(OfferVersion::versionNumber).orElse(1);
        LoanOffer expired = copy(offer, OfferStatus.EXPIRED, offer.currentVersionId(), offer.expiresAt(), null, null, null, offer.decisionReason(), offer.version() + 1, actor.getUsername());
        if (!offers.save(expired, offer.version())) {
            throw new ConflictException("This offer was updated by another request");
        }
        offers.insertHistory(UUID.randomUUID(), offer.id(), offer.status(), OfferStatus.EXPIRED, offerVersion, "Offer period elapsed", actor.getId(), now);
        if (application.status() == ApplicationStatus.OFFER_ISSUED) {
            move(application, ApplicationStatus.EXPIRED, "Offer period elapsed", actor, now);
        }
        return offers.find(offer.id()).orElseThrow();
    }

    private Prepared prepare(
            ApplicationRecord application,
            CustomerRecord customer,
            GenerateOfferRequest request,
            Instant now,
            Instant expiresAt) {
        if (application.tenure() == null || application.repaymentFrequency() == null) {
            throw new ConflictException("The approved application does not have a tenure and repayment frequency");
        }
        LoanProductVersionRecord version = products.findVersion(application.productVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan product version"));
        LoanProductRecord product = products.findProduct(application.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan product"));
        BigDecimal approved = offers.approvedAmount(application.id(), application.revisionNumber())
                .map(LoanOfferRepository.BigDecimalApproval::amount)
                .orElseThrow(() -> new ConflictException("A final approval is required before an offer can be issued"));
        RoundingMode rounding = version.roundingMethod() == RoundingMethod.HALF_EVEN ? RoundingMode.HALF_EVEN : RoundingMode.HALF_UP;
        BigDecimal principal = OfferRules.principal(
                request == null ? null : request.principal(),
                application.amount(),
                approved,
                version.roundingPrecision(),
                rounding);
        LocalDate disbursement = request != null && request.disbursementDate() != null
                ? request.disbursementDate()
                : LocalDate.ofInstant(now, ZoneOffset.UTC);
        ProductComponents parts = components.load(version.id());
        List<AllocationRecord> pledges = collaterals.listForApplication(application.id()).stream()
                .filter(item -> item.status() == PledgeStatus.ACTIVE)
                .toList();
        if (parts.collateralRules().stream().anyMatch(rule -> rule.mandatory()) && pledges.isEmpty()) {
            throw ValidationException.field("collateral", "Required collateral has not been pledged");
        }
        var links = guarantors.listLinks(application.id());
        long approvedGuarantors = links.stream().filter(link -> link.approvedSecurity()).count();
        if (version.guarantorRequired() && approvedGuarantors < version.minimumGuarantors()) {
            throw ValidationException.field("guarantor", "This product requires " + version.minimumGuarantors() + " approved guarantor(s) before an offer can be issued");
        }
        LoanQuote quote = LoanCalculationEngine.quote(new LoanQuoteRequest(
                principal,
                version.interestRate(),
                version.interestMethod(),
                version.interestPeriod(),
                application.tenure(),
                version.tenureUnit(),
                application.repaymentFrequency(),
                version.dayCountConvention(),
                version.roundingPrecision(),
                rounding,
                version.gracePeriodDays(),
                disbursement,
                properties.getFeeTaxRate(),
                parts.fees().stream().map(fee -> new FeeInput(fee.feeType().name(), fee.name(), fee.calculation(), fee.amount(), fee.rate())).toList()));
        String collateralDetails = collateralText(pledges);
        String guarantorDetails = guarantorText(links);
        String conditions = conditions(version, parts, application);
        String defaults = defaults(version);
        return new Prepared(product, version, quote, expiresAt, conditions, collateralDetails, guarantorDetails, defaults, pledges.size(), links.size());
    }

    private LoanOfferResponse present(LoanOffer offer, OfferVersion version) {
        OfferTerms terms = offers.findTerms(version.id()).orElseThrow(() -> new ResourceNotFoundException("Loan offer"));
        StoredAgreement agreement = offers.findAgreement(version.id(), AgreementKind.LOAN).orElse(null);
        return views.offer(offer, version, terms, agreement);
    }

    private Loaded load(UUID id, AuthenticatedUser actor, boolean applyExpiry) {
        LoanOffer offer = offers.lock(id).orElseThrow(() -> new ResourceNotFoundException("Loan offer"));
        ApplicationRecord application = applications.lock(offer.applicationId()).orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        CustomerRecord customer = customers.findById(offer.customerId()).orElseThrow(() -> new ResourceNotFoundException("Customer"));
        assertCanView(actor, application, customer);
        Instant now = Instant.now(clock);
        if (applyExpiry) {
            offer = applyExpiry(offer, application, actor, now);
            application = applications.find(application.id()).orElseThrow();
        }
        OfferVersion version = offers.findVersion(offer.currentVersionId()).orElseThrow(() -> new ResourceNotFoundException("Loan offer"));
        return new Loaded(offer, version, application, customer);
    }

    private OfferVersion selectVersion(LoanOffer offer, Integer version) {
        if (version == null) {
            return offers.findVersion(offer.currentVersionId()).orElseThrow(() -> new ResourceNotFoundException("Loan offer"));
        }
        return offers.findVersion(offer.id(), version).orElseThrow(() -> new ResourceNotFoundException("Loan offer version"));
    }

    private StoredAgreement agreement(OfferVersion version, AgreementKind kind) {
        return offers.findAgreement(version.id(), kind).orElseThrow(() -> new ResourceNotFoundException("Loan agreement"));
    }

    private void move(ApplicationRecord current, ApplicationStatus target, String reason, AuthenticatedUser actor, Instant now) {
        ApplicationWorkflow.assertTransition(current.status(), target);
        applications.update(new ApplicationRecord(
                current.id(), current.reference(), current.customerId(), current.productId(), current.productVersionId(),
                current.branchId(), current.segment(), target, current.revisionNumber(), false,
                current.amount(), current.currencyCode(), current.purpose(), current.tenure(), current.tenureUnit(),
                current.repaymentFrequency(), current.disbursementAccountId(), current.channel(), current.submittedAt(),
                target == ApplicationStatus.WITHDRAWN ? now : null,
                ApplicationWorkflow.reasonRequired(target) ? trim(reason) : current.decisionReason(),
                current.createdAt(), current.updatedAt(), current.createdBy(), actor.getUsername()));
        applications.insertHistory(UUID.randomUUID(), current.id(), current.status(), target, trim(reason), actor.getId());
        applications.insertEvent(UUID.randomUUID(), current.id(), "STATUS_CHANGED", json(Map.of(
                "from", current.status().name(),
                "to", target.name())), actor.getId());
    }

    private OfferTerms terms(UUID versionId, ApplicationRecord application, CustomerRecord customer, Prepared prepared) {
        LoanQuote quote = prepared.quote();
        return new OfferTerms(
                UUID.randomUUID(),
                versionId,
                customer.id(),
                customer.displayName(),
                customer.reference(),
                application.reference(),
                prepared.product().id(),
                prepared.product().productCode(),
                prepared.version().productName(),
                prepared.version().id(),
                quote.principal(),
                application.currencyCode(),
                quote.interestRatePercent(),
                quote.interestMethod().name(),
                quote.interestPeriod().name(),
                quote.tenure(),
                quote.tenureUnit().name(),
                quote.frequency().name(),
                quote.dayCount().name(),
                quote.scale(),
                quote.rounding().name(),
                quote.gracePeriodDays(),
                quote.instalmentAmount(),
                quote.processingFees(),
                quote.otherFees(),
                quote.taxes(),
                quote.taxRatePercent(),
                quote.totalInterest(),
                quote.totalRepayment(),
                quote.netDisbursement(),
                quote.disbursementDate(),
                quote.engineVersion(),
                json(quote.fees().stream().map(this::fee).toList()),
                prepared.conditions(),
                prepared.collateralDetails(),
                prepared.guarantorDetails(),
                prepared.defaultProvisions());
    }

    private Map<String, Object> fee(CalculatedFee fee) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("type", fee.type());
        row.put("name", fee.name());
        row.put("calculation", fee.calculation().name());
        row.put("amount", fee.amount());
        row.put("rate", fee.rate());
        row.put("charged", fee.charged());
        return row;
    }

    private List<ScheduleItem> schedule(UUID versionId, LoanQuote quote) {
        return quote.schedule().stream().map(item -> new ScheduleItem(
                UUID.randomUUID(),
                versionId,
                item.number(),
                item.dueDate(),
                item.openingBalance(),
                item.principalDue(),
                item.interestDue(),
                item.instalmentAmount(),
                item.closingBalance())).toList();
    }

    private String snapshot(Prepared prepared) {
        LoanQuote quote = prepared.quote();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("engineVersion", quote.engineVersion());
        body.put("fingerprint", quote.fingerprint());
        body.put("productVersionId", prepared.version().id().toString());
        body.put("principal", quote.principal());
        body.put("interestRate", quote.interestRatePercent());
        body.put("interestMethod", quote.interestMethod().name());
        body.put("interestPeriod", quote.interestPeriod().name());
        body.put("tenure", quote.tenure());
        body.put("tenureUnit", quote.tenureUnit().name());
        body.put("repaymentFrequency", quote.frequency().name());
        body.put("dayCountConvention", quote.dayCount().name());
        body.put("roundingPrecision", quote.scale());
        body.put("roundingMethod", quote.rounding().name());
        body.put("instalmentAmount", quote.instalmentAmount());
        body.put("processingFees", quote.processingFees());
        body.put("otherFees", quote.otherFees());
        body.put("taxes", quote.taxes());
        body.put("totalInterest", quote.totalInterest());
        body.put("totalRepayment", quote.totalRepayment());
        body.put("netDisbursement", quote.netDisbursement());
        body.put("schedule", quote.schedule().stream().map(this::instalment).toList());
        return json(body);
    }

    private Map<String, Object> instalment(CalculatedInstalment item) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("number", item.number());
        row.put("dueDate", item.dueDate().toString());
        row.put("openingBalance", item.openingBalance());
        row.put("principalDue", item.principalDue());
        row.put("interestDue", item.interestDue());
        row.put("instalmentAmount", item.instalmentAmount());
        row.put("closingBalance", item.closingBalance());
        return row;
    }

    private List<Rendered> render(Prepared prepared, OfferTerms terms, Instant expiresAt) {
        AgreementTemplate loanTemplate = offers.findActive(AgreementKind.LOAN)
                .orElseThrow(() -> new ConflictException("An active loan agreement template is required"));
        List<Rendered> documents = new ArrayList<>();
        if (prepared.pledgeCount() > 0) {
            documents.add(renderOne(AgreementKind.COLLATERAL, prepared, terms, expiresAt, "Collateral reference"));
        }
        if (prepared.guarantorCount() > 0 || prepared.version().guarantorRequired()) {
            documents.add(renderOne(AgreementKind.GUARANTOR, prepared, terms, expiresAt, "Guarantor agreement"));
        }
        documents.add(renderOne(loanTemplate, prepared, terms, expiresAt, "Loan agreement"));
        return documents;
    }

    private Rendered renderOne(AgreementKind kind, Prepared prepared, OfferTerms terms, Instant expiresAt, String title) {
        AgreementTemplate template = offers.findActive(kind)
                .orElseThrow(() -> new ConflictException("An active " + kind.name().toLowerCase() + " agreement template is required"));
        return renderOne(template, prepared, terms, expiresAt, title);
    }

    private Rendered renderOne(AgreementTemplate template, Prepared prepared, OfferTerms terms, Instant expiresAt, String title) {
        String text = render(template.body(), values(prepared, terms, template, expiresAt));
        String heading = title + " " + terms.applicationReference();
        return new Rendered(template.kind(), template, heading, text, PdfAgreementWriter.write(heading, text));
    }

    private Map<String, String> values(Prepared prepared, OfferTerms terms, AgreementTemplate template, Instant expiresAt) {
        LoanQuote quote = prepared.quote();
        Map<String, String> values = new LinkedHashMap<>();
        values.put("customerName", terms.customerName());
        values.put("customerReference", terms.customerReference());
        values.put("applicationReference", terms.applicationReference());
        values.put("productName", terms.productName());
        values.put("productCode", terms.productCode());
        values.put("currency", terms.currencyCode());
        values.put("principal", quote.principal().toPlainString());
        values.put("interestRate", quote.interestRatePercent().toPlainString());
        values.put("interestMethod", quote.interestMethod().name());
        values.put("interestPeriod", quote.interestPeriod().name());
        values.put("tenure", Integer.toString(quote.tenure()));
        values.put("tenureUnit", quote.tenureUnit().name());
        values.put("repaymentFrequency", quote.frequency().name());
        values.put("instalmentAmount", quote.instalmentAmount().toPlainString());
        values.put("processingFees", quote.processingFees().toPlainString());
        values.put("otherFees", quote.otherFees().toPlainString());
        values.put("taxes", quote.taxes().toPlainString());
        values.put("totalInterest", quote.totalInterest().toPlainString());
        values.put("totalRepayment", quote.totalRepayment().toPlainString());
        values.put("netDisbursement", quote.netDisbursement().toPlainString());
        values.put("disbursementDate", quote.disbursementDate().toString());
        values.put("expiresAt", expiresAt.toString());
        values.put("engineVersion", quote.engineVersion());
        values.put("schedule", scheduleText(quote));
        values.put("disbursementConditions", prepared.conditions());
        values.put("collateralDetails", prepared.collateralDetails());
        values.put("guarantorDetails", prepared.guarantorDetails());
        values.put("defaultProvisions", prepared.defaultProvisions());
        values.put("templateCode", template.code());
        values.put("templateVersion", Integer.toString(template.versionNumber()));
        return values;
    }

    private static String render(String template, Map<String, String> values) {
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String safe = entry.getValue() == null ? "" : entry.getValue().replace("{{", "");
            rendered = rendered.replace("{{" + entry.getKey() + "}}", safe);
        }
        return rendered;
    }

    private static String scheduleText(LoanQuote quote) {
        StringBuilder text = new StringBuilder();
        for (CalculatedInstalment item : quote.schedule()) {
            text.append(item.number())
                    .append(". ")
                    .append(item.dueDate())
                    .append(" principal ")
                    .append(item.principalDue().toPlainString())
                    .append(" interest ")
                    .append(item.interestDue().toPlainString())
                    .append(" instalment ")
                    .append(item.instalmentAmount().toPlainString())
                    .append(" balance ")
                    .append(item.closingBalance().toPlainString())
                    .append('\n');
        }
        return text.toString().trim();
    }

    private String collateralText(List<AllocationRecord> pledges) {
        if (pledges.isEmpty()) {
            return "No collateral is pledged to this application.";
        }
        StringBuilder text = new StringBuilder();
        for (AllocationRecord pledge : pledges) {
            collaterals.find(pledge.collateralId()).ifPresent(collateral -> text
                    .append(collateral.reference())
                    .append(' ')
                    .append(collateral.type().name())
                    .append(' ')
                    .append(collateral.description())
                    .append(" allocated ")
                    .append(pledge.allocatedAmount().toPlainString())
                    .append('\n'));
        }
        return text.toString().trim();
    }

    private String guarantorText(List<com.mfb.lending.module.collateral.domain.LoanGuarantorRecord> links) {
        if (links.isEmpty()) {
            return "No guarantor is linked to this application.";
        }
        StringBuilder text = new StringBuilder();
        for (var link : links) {
            guarantors.find(link.guarantorId()).ifPresent(guarantor -> text
                    .append(guarantor.reference())
                    .append(' ')
                    .append(guarantor.fullName())
                    .append(" (")
                    .append(link.relationship())
                    .append(link.approvedSecurity() ? ", approved security" : ", not approved security")
                    .append(")\n"));
        }
        return text.toString().trim();
    }

    private static String conditions(LoanProductVersionRecord version, ProductComponents parts, ApplicationRecord application) {
        StringBuilder text = new StringBuilder("Customer identity must still be verified at disbursement.");
        List<String> documents = parts.documents().stream().filter(item -> item.required()).map(item -> item.documentName()).toList();
        if (!documents.isEmpty()) {
            text.append(" Required documents: ").append(String.join(", ", documents)).append('.');
        }
        if (version.guarantorRequired()) {
            text.append(" At least ").append(version.minimumGuarantors()).append(" approved guarantor(s) must remain in place.");
        }
        if (version.creditBureauRequired()) {
            text.append(" The credit bureau requirement on this product remains in force.");
        }
        if (application.disbursementAccountId() != null) {
            text.append(" Funds are payable to the disbursement account named on the application.");
        }
        return text.toString();
    }

    private static String defaults(LoanProductVersionRecord version) {
        String penalty = switch (version.penaltyBasis()) {
            case NONE -> "No contractual penalty is configured on this product version.";
            case FLAT_AMOUNT -> "A flat penalty of NGN " + version.penaltyAmount().toPlainString()
                    + " applies after " + version.penaltyGraceDays() + " days past due.";
            case PERCENTAGE_OF_OVERDUE -> "A penalty of " + version.penaltyRate().toPlainString()
                    + " percent of the overdue amount applies after " + version.penaltyGraceDays() + " days past due.";
            case PERCENTAGE_OF_INSTALMENT -> "A penalty of " + version.penaltyRate().toPlainString()
                    + " percent of the instalment applies after " + version.penaltyGraceDays() + " days past due.";
        };
        String early = switch (version.earlyRepaymentRule()) {
            case ALLOWED -> "Early repayment is allowed.";
            case NOT_ALLOWED -> "Early repayment is not allowed.";
            case ALLOWED_WITH_FEE -> "Early repayment is allowed with a fee of " + version.earlyRepaymentFeeRate().toPlainString() + " percent.";
        };
        return penalty + " " + early;
    }

    private Instant expiry(GenerateOfferRequest request, Instant now) {
        if (request != null && request.expiresOn() != null) {
            Instant end = request.expiresOn().plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            if (!end.isAfter(now)) {
                throw ValidationException.field("expiresOn", "The offer expiry must be in the future");
            }
            return end;
        }
        return now.plus(properties.getExpiryDays(), ChronoUnit.DAYS);
    }

    private void assertCanView(AuthenticatedUser actor, ApplicationRecord application, CustomerRecord customer) {
        if (CustomerAccess.isOwner(actor, customer)) {
            return;
        }
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            throw new ResourceNotFoundException("Loan offer");
        }
        boolean allowed = actor.permissions().contains(PermissionCodes.LOAN_OFFER_VIEW)
                || actor.permissions().contains(PermissionCodes.LOAN_OFFER_MANAGE)
                || actor.permissions().contains(PermissionCodes.LOAN_APPLICATION_VIEW);
        if (!allowed) {
            throw new ForbiddenException("You do not have permission to view this offer");
        }
        assertBranch(actor, application.branchId() == null ? customer.branchId() : application.branchId());
    }

    private void assertStaff(AuthenticatedUser actor, ApplicationRecord application) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER
                || !actor.permissions().contains(PermissionCodes.LOAN_OFFER_MANAGE)) {
            throw new ForbiddenException("You do not have permission to manage this offer");
        }
        assertBranch(actor, application.branchId());
    }

    private static void assertBranch(AuthenticatedUser actor, UUID branchId) {
        if (actor.unrestrictedBranches()) {
            return;
        }
        if (branchId == null || !actor.branchIds().contains(branchId)) {
            throw new ForbiddenException("You cannot access an offer outside your branches");
        }
    }

    private static AgreementKind kind(String value) {
        if (value == null || value.isBlank()) {
            return AgreementKind.LOAN;
        }
        try {
            return AgreementKind.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException ex) {
            throw ValidationException.field("kind", "kind must be LOAN, GUARANTOR, or COLLATERAL");
        }
    }

    private static String trim(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }

    private static LoanOffer copy(
            LoanOffer current,
            OfferStatus status,
            UUID versionId,
            Instant expiresAt,
            Instant acceptedAt,
            Instant declinedAt,
            Instant cancelledAt,
            String reason,
            int version,
            String updatedBy) {
        return new LoanOffer(
                current.id(), current.reference(), current.applicationId(), current.customerId(), status, versionId,
                expiresAt, acceptedAt, declinedAt, cancelledAt, reason, version, current.createdAt(), current.updatedAt(),
                current.createdBy(), updatedBy);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Offer payload could not be written", ex);
        }
    }

    public record DocumentFile(String fileName, String checksumSha256, byte[] body) {
    }

    private record Prepared(
            LoanProductRecord product,
            LoanProductVersionRecord version,
            LoanQuote quote,
            Instant expiresAt,
            String conditions,
            String collateralDetails,
            String guarantorDetails,
            String defaultProvisions,
            int pledgeCount,
            int guarantorCount
    ) {
    }

    private record Loaded(LoanOffer offer, OfferVersion version, ApplicationRecord application, CustomerRecord customer) {
    }

    private record Rendered(AgreementKind kind, AgreementTemplate template, String title, String text, byte[] pdf) {
    }
}
