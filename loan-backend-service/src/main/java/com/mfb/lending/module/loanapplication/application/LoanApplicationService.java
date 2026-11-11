package com.mfb.lending.module.loanapplication.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.approval.application.ApprovalService;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.application.CustomerAccess;
import com.mfb.lending.module.customer.application.CustomerEligibilityService;
import com.mfb.lending.module.customer.domain.AccountRecord;
import com.mfb.lending.module.customer.domain.AccountVerificationStatus;
import com.mfb.lending.module.customer.domain.ClientChannel;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.persistence.CustomerAccountRepository;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import com.mfb.lending.module.loanapplication.domain.ApplicationRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;
import com.mfb.lending.module.loanapplication.domain.CollateralRecord;
import com.mfb.lending.module.loanapplication.domain.EmploymentStatus;
import com.mfb.lending.module.loanapplication.domain.FinancialProfile;
import com.mfb.lending.module.loanapplication.domain.GuarantorRecord;
import com.mfb.lending.module.loanapplication.domain.IndividualFinancials;
import com.mfb.lending.module.loanapplication.domain.Obligation;
import com.mfb.lending.module.loanapplication.domain.SmeFinancials;
import com.mfb.lending.module.loanapplication.dto.ApplicationDashboardResponse;
import com.mfb.lending.module.loanapplication.dto.ApplicationHistoryResponse;
import com.mfb.lending.module.loanapplication.dto.LoanApplicationResponse;
import com.mfb.lending.module.loanapplication.dto.LoanApplicationSummaryResponse;
import com.mfb.lending.module.loanapplication.dto.SaveLoanApplicationRequest;
import com.mfb.lending.module.loanapplication.dto.SaveLoanApplicationRequest.CollateralRequest;
import com.mfb.lending.module.loanapplication.dto.SaveLoanApplicationRequest.GuarantorRequest;
import com.mfb.lending.module.loanapplication.dto.SaveLoanApplicationRequest.IndividualFinancialsRequest;
import com.mfb.lending.module.loanapplication.dto.SaveLoanApplicationRequest.ObligationRequest;
import com.mfb.lending.module.loanapplication.dto.SaveLoanApplicationRequest.SmeFinancialsRequest;
import com.mfb.lending.module.loanapplication.dto.TransitionRequest;
import com.mfb.lending.module.loanapplication.dto.WithdrawRequest;
import com.mfb.lending.module.loanapplication.persistence.ApplicationSearch;
import com.mfb.lending.module.loanapplication.persistence.LoanApplicationRepository;
import com.mfb.lending.module.loanproduct.domain.LoanProductRecord;
import com.mfb.lending.module.loanproduct.domain.LoanProductVersionRecord;
import com.mfb.lending.module.loanproduct.domain.ProductComponents;
import com.mfb.lending.module.loanproduct.domain.ProductStatus;
import com.mfb.lending.module.loanproduct.domain.RepaymentFrequency;
import com.mfb.lending.module.loanproduct.persistence.LoanProductComponentRepository;
import com.mfb.lending.module.loanproduct.persistence.LoanProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LoanApplicationService {

    private static final Pattern PHONE = Pattern.compile("^\\+[1-9]\\d{7,14}$");
    private static final Pattern COLLATERAL_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,39}$");
    private static final List<ApplicationStatus> CLOSED_QUEUE = List.of(
            ApplicationStatus.DRAFT,
            ApplicationStatus.REJECTED,
            ApplicationStatus.WITHDRAWN,
            ApplicationStatus.CANCELLED,
            ApplicationStatus.EXPIRED,
            ApplicationStatus.DISBURSED);
    private static final String LOCKED = "Submitted applications cannot be modified. Ask for the application to be returned for correction.";

    private final LoanApplicationRepository applications;
    private final LoanProductRepository products;
    private final LoanProductComponentRepository components;
    private final CustomerRepository customers;
    private final CustomerAccountRepository accounts;
    private final CustomerEligibilityService eligibility;
    private final LoanApplicationViews views;
    private final AuditService audit;
    private final ApprovalService approvals;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public LoanApplicationService(
            LoanApplicationRepository applications,
            LoanProductRepository products,
            LoanProductComponentRepository components,
            CustomerRepository customers,
            CustomerAccountRepository accounts,
            CustomerEligibilityService eligibility,
            LoanApplicationViews views,
            AuditService audit,
            ApprovalService approvals,
            Clock clock,
            TransactionTemplate transactions) {
        this.applications = applications;
        this.products = products;
        this.components = components;
        this.customers = customers;
        this.accounts = accounts;
        this.eligibility = eligibility;
        this.views = views;
        this.audit = audit;
        this.approvals = approvals;
        this.clock = clock;
        this.transactions = transactions;
    }

    public LoanApplicationResponse create(
            SaveLoanApplicationRequest request,
            AuthenticatedUser actor,
            ClientContext client,
            ClientChannel channel) {
        if (request.productId() == null) {
            throw ValidationException.field("productId", "productId is required");
        }
        CustomerRecord customer = resolveCustomer(request.customerId(), actor);
        LoanApplicationResponse response = transactions.execute(status ->
                createLocked(request, actor, channel, customer.id()));
        audit.record("LOAN_APPLICATION_CREATED", "loan_application", response.id().toString(), actor.getUsername(), client, Map.of(
                "reference", response.reference(),
                "productId", response.productId().toString(),
                "customerSegment", response.customerSegment()));
        return response;
    }

    public LoanApplicationResponse update(
            UUID id,
            SaveLoanApplicationRequest request,
            AuthenticatedUser actor,
            ClientContext client) {
        LoanApplicationResponse response = transactions.execute(status -> updateLocked(id, request, actor));
        audit.record("LOAN_APPLICATION_UPDATED", "loan_application", id.toString(), actor.getUsername(), client, Map.of(
                "reference", response.reference(),
                "revision", response.revisionNumber(),
                "status", response.status()));
        return response;
    }

    public LoanApplicationResponse get(UUID id, AuthenticatedUser actor) {
        ApplicationRecord application = applications.find(id).orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        ApplicationAccess.assertCanView(actor, application, customerOf(application));
        return assemble(application);
    }

    public PageResponse<LoanApplicationSummaryResponse> list(
            AuthenticatedUser actor,
            UUID customerId,
            UUID productId,
            UUID branchId,
            String status,
            String segment,
            String query,
            String sort,
            String direction,
            int page,
            int size) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            return search(ownCustomer(actor).id(), false, List.of(), status(status), productId, segment(segment),
                    query, sort, direction, page, size, "a.updated_at", null);
        }
        if (!ApplicationAccess.canRead(actor)) {
            throw new ForbiddenException("You do not have permission to view loan applications");
        }
        BranchScope scope = scope(actor, branchId);
        return search(customerId, scope.restrict(), scope.branchIds(), status(status), productId, segment(segment),
                query, sort, direction, page, size, "a.updated_at", null);
    }

    public PageResponse<LoanApplicationSummaryResponse> myApplications(
            AuthenticatedUser actor,
            String status,
            String sort,
            String direction,
            int page,
            int size) {
        return search(ownCustomer(actor).id(), false, List.of(), status(status), null, null,
                null, sort, direction, page, size, "a.updated_at", null);
    }

    public ApplicationDashboardResponse dashboard(AuthenticatedUser actor) {
        return views.dashboard(applications.totals(ownCustomer(actor).id()));
    }

    public LoanApplicationResponse submit(UUID id, AuthenticatedUser actor, ClientContext client) {
        LoanApplicationResponse response = transactions.execute(status -> submitLocked(id, actor));
        audit.record("LOAN_APPLICATION_SUBMITTED", "loan_application", id.toString(), actor.getUsername(), client, Map.of(
                "reference", response.reference(),
                "revision", response.revisionNumber()));
        return response;
    }

    public LoanApplicationResponse withdraw(UUID id, WithdrawRequest request, AuthenticatedUser actor, ClientContext client) {
        LoanApplicationResponse response = transactions.execute(status -> withdrawLocked(id, request, actor));
        audit.record("LOAN_APPLICATION_WITHDRAWN", "loan_application", id.toString(), actor.getUsername(), client, Map.of(
                "reference", response.reference()));
        return response;
    }

    public LoanApplicationResponse acceptOffer(UUID id, AuthenticatedUser actor, ClientContext client) {
        LoanApplicationResponse response = transactions.execute(status -> acceptLocked(id, actor));
        audit.record("LOAN_APPLICATION_OFFER_ACCEPTED", "loan_application", id.toString(), actor.getUsername(), client, Map.of(
                "reference", response.reference()));
        return response;
    }

    public ApplicationHistoryResponse history(UUID id, AuthenticatedUser actor) {
        ApplicationRecord application = applications.find(id).orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        ApplicationAccess.assertCanView(actor, application, customerOf(application));
        return views.history(applications.listHistory(id), applications.listEvents(id));
    }

    public PageResponse<LoanApplicationSummaryResponse> queue(
            AuthenticatedUser actor,
            UUID productId,
            UUID branchId,
            String status,
            String segment,
            String query,
            String sort,
            String direction,
            int page,
            int size) {
        if (!ApplicationAccess.canRead(actor) || actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            throw new ForbiddenException("You do not have permission to view the application queue");
        }
        ApplicationStatus selected = status(status);
        BranchScope scope = scope(actor, branchId);
        return search(null, scope.restrict(), scope.branchIds(), selected, productId, segment(segment),
                query, sort, direction, page, size, "a.submitted_at", selected == null ? CLOSED_QUEUE : null);
    }

    public LoanApplicationResponse transition(UUID id, TransitionRequest request, AuthenticatedUser actor, ClientContext client) {
        LoanApplicationResponse response = transactions.execute(status -> transitionLocked(id, request, actor));
        audit.record("LOAN_APPLICATION_STATUS_CHANGED", "loan_application", id.toString(), actor.getUsername(), client, Map.of(
                "reference", response.reference(),
                "status", response.status()));
        return response;
    }

    private LoanApplicationResponse createLocked(
            SaveLoanApplicationRequest request,
            AuthenticatedUser actor,
            ClientChannel channel,
            UUID customerId) {
        if (!applications.lockCustomer(customerId)) {
            throw new ResourceNotFoundException("Customer");
        }
        CustomerRecord customer = customers.findById(customerId).orElseThrow(() -> new ResourceNotFoundException("Customer"));
        ApplicationAccess.assertCanOriginate(actor, customer);
        assertEligible(customer);
        Offered offered = loadOffered(request.productId());
        UUID id = UUID.randomUUID();
        LoanApplicationViews.DraftState state = draft(id, customer.id(), request, null, FinancialProfile.empty(), List.of(), List.of(), false);
        check(customer.kind(), offered.terms(), state, false);
        int max = ApplicationRules.maxActiveApplications(offered.terms().eligibilityRules());
        if (applications.countActive(customer.id(), offered.product().id()) >= max) {
            throw duplicate(max);
        }
        String reference = applications.nextReference(clock.instant().atZone(ZoneOffset.UTC).getYear());
        ApplicationRecord application = new ApplicationRecord(
                id,
                reference,
                customer.id(),
                offered.product().id(),
                offered.version().id(),
                customer.branchId(),
                customer.kind(),
                ApplicationStatus.DRAFT,
                1,
                false,
                state.amount(),
                "NGN",
                state.purpose(),
                state.tenure(),
                offered.version().tenureUnit(),
                state.frequency(),
                state.accountId(),
                channel,
                null,
                null,
                null,
                null,
                null,
                actor.getUsername(),
                actor.getUsername());
        applications.insert(application);
        applications.upsertFinancials(id, state.financials());
        if (request.guarantors() != null) {
            applications.replaceGuarantors(id, state.guarantors());
        }
        if (request.collaterals() != null) {
            applications.replaceCollaterals(id, state.collaterals());
        }
        applications.insertHistory(UUID.randomUUID(), id, null, ApplicationStatus.DRAFT, "Application started", actor.getId());
        applications.insertEvent(UUID.randomUUID(), id, "CREATED", views.json(Map.of(
                "reference", reference,
                "productId", offered.product().id().toString())), actor.getId());
        return assemble(applications.find(id).orElseThrow(() -> new ResourceNotFoundException("Loan application")));
    }

    private LoanApplicationResponse updateLocked(UUID id, SaveLoanApplicationRequest request, AuthenticatedUser actor) {
        ApplicationRecord current = locked(id);
        CustomerRecord customer = customerOf(current);
        ApplicationAccess.assertCanMutate(actor, current, customer);
        if (!ApplicationWorkflow.editable(current.status(), current.correctionOpen())) {
            throw new ConflictException(LOCKED);
        }
        if (request.productId() != null && !request.productId().equals(current.productId())) {
            throw ValidationException.field("productId", "The loan product cannot be changed");
        }
        Offered offered = loadPinned(current);
        FinancialProfile financials = applications.findFinancials(id);
        List<GuarantorRecord> guarantors = applications.listGuarantors(id);
        List<CollateralRecord> collaterals = applications.listCollaterals(id);
        LoanApplicationViews.DraftState state = draft(id, customer.id(), request, current, financials, guarantors, collaterals, true);
        check(current.segment(), offered.terms(), state, false);
        List<String> changed = LoanApplicationViews.changedFields(current, financials, guarantors, collaterals, state);
        int revision = current.status() != ApplicationStatus.DRAFT && !changed.isEmpty()
                ? current.revisionNumber() + 1
                : current.revisionNumber();
        applications.update(evolve(current, current.status(), revision, current.correctionOpen(), state, current.submittedAt(),
                current.withdrawnAt(), current.decisionReason(), actor.getUsername()));
        applications.upsertFinancials(id, state.financials());
        if (request.guarantors() != null) {
            applications.replaceGuarantors(id, state.guarantors());
        }
        if (request.collaterals() != null) {
            applications.replaceCollaterals(id, state.collaterals());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("revision", revision);
        payload.put("fields", changed);
        applications.insertEvent(UUID.randomUUID(), id, revision == current.revisionNumber() ? "UPDATED" : "REVISED",
                views.json(payload), actor.getId());
        if (revision != current.revisionNumber()) {
            approvals.noteRevision(id, actor.getId());
        }
        return assemble(applications.find(id).orElseThrow(() -> new ResourceNotFoundException("Loan application")));
    }

    private LoanApplicationResponse submitLocked(UUID id, AuthenticatedUser actor) {
        ApplicationRecord current = locked(id);
        CustomerRecord customer = customerOf(current);
        ApplicationAccess.assertCanMutate(actor, current, customer);
        boolean correction = current.correctionOpen();
        if (current.status() != ApplicationStatus.DRAFT
                && !(current.status() == ApplicationStatus.DOCUMENTS_PENDING && correction)) {
            throw ValidationException.field("status", "Only a draft or an application returned for correction can be submitted");
        }
        assertEligible(customer);
        Offered offered = loadPinned(current);
        assertStillOffered(current, offered);
        FinancialProfile financials = applications.findFinancials(id);
        List<GuarantorRecord> guarantors = applications.listGuarantors(id);
        List<CollateralRecord> collaterals = applications.listCollaterals(id);
        LoanApplicationViews.DraftState state = new LoanApplicationViews.DraftState(
                current.amount(),
                current.purpose(),
                current.tenure(),
                current.repaymentFrequency(),
                current.disbursementAccountId(),
                verified(customer.id(), current.disbursementAccountId()),
                financials,
                guarantors,
                collaterals);
        check(current.segment(), offered.terms(), state, true, applications.listDocuments(id).stream()
                .map(document -> document.documentCode()).collect(java.util.stream.Collectors.toSet()));
        ApplicationWorkflow.assertTransition(current.status(), ApplicationStatus.SUBMITTED);
        applications.update(evolve(current, ApplicationStatus.SUBMITTED, current.revisionNumber(), false, state,
                Instant.now(clock), null, null, actor.getUsername()));
        applications.insertHistory(UUID.randomUUID(), id, current.status(), ApplicationStatus.SUBMITTED,
                correction ? "Corrections submitted" : "Application submitted", actor.getId());
        applications.insertEvent(UUID.randomUUID(), id, "SUBMITTED", views.json(Map.of(
                "revision", current.revisionNumber(),
                "resubmission", correction)), actor.getId());
        return assemble(applications.find(id).orElseThrow(() -> new ResourceNotFoundException("Loan application")));
    }

    private LoanApplicationResponse withdrawLocked(UUID id, WithdrawRequest request, AuthenticatedUser actor) {
        ApplicationRecord current = locked(id);
        CustomerRecord customer = customerOf(current);
        ApplicationAccess.assertCanView(actor, current, customer);
        if (!CustomerAccess.isOwner(actor, customer)) {
            throw new ForbiddenException("Only the customer can withdraw this application");
        }
        ApplicationWorkflow.assertTransition(current.status(), ApplicationStatus.WITHDRAWN);
        String reason = request.reason().trim();
        applications.update(new ApplicationRecord(
                current.id(), current.reference(), current.customerId(), current.productId(), current.productVersionId(),
                current.branchId(), current.segment(), ApplicationStatus.WITHDRAWN, current.revisionNumber(), false,
                current.amount(), current.currencyCode(), current.purpose(), current.tenure(), current.tenureUnit(),
                current.repaymentFrequency(), current.disbursementAccountId(), current.channel(), current.submittedAt(),
                Instant.now(clock), reason, current.createdAt(), current.updatedAt(), current.createdBy(), actor.getUsername()));
        applications.insertHistory(UUID.randomUUID(), id, current.status(), ApplicationStatus.WITHDRAWN, reason, actor.getId());
        applications.insertEvent(UUID.randomUUID(), id, "WITHDRAWN", views.json(Map.of("reason", reason)), actor.getId());
        return assemble(applications.find(id).orElseThrow(() -> new ResourceNotFoundException("Loan application")));
    }

    private LoanApplicationResponse acceptLocked(UUID id, AuthenticatedUser actor) {
        ApplicationRecord current = locked(id);
        CustomerRecord customer = customerOf(current);
        ApplicationAccess.assertCanView(actor, current, customer);
        if (!CustomerAccess.isOwner(actor, customer)) {
            throw new ForbiddenException("Only the customer can accept this offer");
        }
        ApplicationWorkflow.assertTransition(current.status(), ApplicationStatus.OFFER_ACCEPTED);
        applications.update(new ApplicationRecord(
                current.id(), current.reference(), current.customerId(), current.productId(), current.productVersionId(),
                current.branchId(), current.segment(), ApplicationStatus.OFFER_ACCEPTED, current.revisionNumber(), false,
                current.amount(), current.currencyCode(), current.purpose(), current.tenure(), current.tenureUnit(),
                current.repaymentFrequency(), current.disbursementAccountId(), current.channel(), current.submittedAt(),
                null, null, current.createdAt(), current.updatedAt(), current.createdBy(), actor.getUsername()));
        applications.insertHistory(UUID.randomUUID(), id, current.status(), ApplicationStatus.OFFER_ACCEPTED, "Offer accepted", actor.getId());
        applications.insertEvent(UUID.randomUUID(), id, "OFFER_ACCEPTED", views.json(Map.of("status", "OFFER_ACCEPTED")), actor.getId());
        return assemble(applications.find(id).orElseThrow(() -> new ResourceNotFoundException("Loan application")));
    }

    private LoanApplicationResponse transitionLocked(UUID id, TransitionRequest request, AuthenticatedUser actor) {
        ApplicationRecord current = locked(id);
        CustomerRecord customer = customerOf(current);
        ApplicationAccess.assertCanView(actor, current, customer);
        ApplicationStatus target = ApplicationStatus.parse(request.status(), "status");
        if (ApplicationWorkflow.customerControlled(target)) {
            throw ValidationException.field("status", "The customer completes this step");
        }
        ApplicationAccess.assertCanTransition(actor, target);
        String reason = text(request.reason(), 500, "reason");
        if (ApplicationWorkflow.reasonRequired(target) && reason == null) {
            throw ValidationException.field("reason", "reason is required");
        }
        ApplicationWorkflow.assertTransition(current.status(), target);
        if (target == ApplicationStatus.DISBURSED) {
            eligibility.assertDisbursementAllowed(customer.id());
        }
        boolean correction = target == ApplicationStatus.DOCUMENTS_PENDING;
        String decision = ApplicationWorkflow.reasonRequired(target) ? reason : null;
        applications.update(new ApplicationRecord(
                current.id(), current.reference(), current.customerId(), current.productId(), current.productVersionId(),
                current.branchId(), current.segment(), target, current.revisionNumber(), correction,
                current.amount(), current.currencyCode(), current.purpose(), current.tenure(), current.tenureUnit(),
                current.repaymentFrequency(), current.disbursementAccountId(), current.channel(), current.submittedAt(),
                null, decision, current.createdAt(), current.updatedAt(), current.createdBy(), actor.getUsername()));
        applications.insertHistory(UUID.randomUUID(), id, current.status(), target, reason, actor.getId());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", current.status().name());
        payload.put("to", target.name());
        if (reason != null) {
            payload.put("reason", reason);
        }
        applications.insertEvent(UUID.randomUUID(), id, "STATUS_CHANGED", views.json(payload), actor.getId());
        return assemble(applications.find(id).orElseThrow(() -> new ResourceNotFoundException("Loan application")));
    }

    private LoanApplicationViews.DraftState draft(
            UUID applicationId,
            UUID customerId,
            SaveLoanApplicationRequest request,
            ApplicationRecord current,
            FinancialProfile currentFinancials,
            List<GuarantorRecord> currentGuarantors,
            List<CollateralRecord> currentCollaterals,
            boolean overlay) {
        BigDecimal amount = request.amount() == null
                ? value(overlay, current == null ? null : current.amount(), null)
                : ApplicationRules.money(request.amount(), "amount", false);
        String purpose = request.purpose() == null
                ? value(overlay, current == null ? null : current.purpose(), null)
                : text(request.purpose(), 500, "purpose");
        Integer tenure = request.tenure() == null
                ? value(overlay, current == null ? null : current.tenure(), null)
                : request.tenure();
        RepaymentFrequency frequency = request.repaymentFrequency() == null
                ? value(overlay, current == null ? null : current.repaymentFrequency(), null)
                : ApplicationRules.frequency(request.repaymentFrequency(), "repaymentFrequency");
        UUID accountId = request.disbursementAccountId() != null
                ? request.disbursementAccountId()
                : value(overlay, current == null ? null : current.disbursementAccountId(), null);
        IndividualFinancials individual = request.individualFinancials() == null
                ? value(overlay, currentFinancials.individual(), null)
                : individual(request.individualFinancials());
        SmeFinancials sme = request.smeFinancials() == null
                ? value(overlay, currentFinancials.sme(), null)
                : sme(request.smeFinancials());
        List<Obligation> obligations = request.obligations() == null
                ? value(overlay, currentFinancials.obligations(), List.of())
                : request.obligations().stream().map(LoanApplicationService::obligation).toList();
        List<GuarantorRecord> guarantors = request.guarantors() == null
                ? value(overlay, currentGuarantors, List.of())
                : request.guarantors().stream().map(item -> guarantor(applicationId, item)).toList();
        List<CollateralRecord> collaterals = request.collaterals() == null
                ? value(overlay, currentCollaterals, List.of())
                : request.collaterals().stream().map(item -> collateral(applicationId, item)).toList();
        return new LoanApplicationViews.DraftState(
                amount,
                purpose,
                tenure,
                frequency,
                accountId,
                verified(customerId, accountId),
                new FinancialProfile(individual, sme, obligations),
                guarantors,
                collaterals);
    }

    private void check(CustomerKind segment, ProductTerms terms, LoanApplicationViews.DraftState state, boolean submitting) {
        check(segment, terms, state, submitting, java.util.Set.of());
    }

    private void check(
            CustomerKind segment,
            ProductTerms terms,
            LoanApplicationViews.DraftState state,
            boolean submitting,
            java.util.Set<String> documents) {
        ApplicationRules.throwIfPresent(ApplicationRules.validate(
                segment,
                terms,
                new ApplicationInput(
                        state.amount(),
                        state.purpose(),
                        state.tenure(),
                        state.frequency(),
                        state.accountId(),
                        state.accountVerified(),
                        state.financials().individual(),
                        state.financials().sme(),
                        state.financials().obligations(),
                        state.guarantors().stream().map(GuarantorRecord::phoneNumber).toList(),
                        state.collaterals().stream()
                                .map(item -> new ApplicationInput.CollateralPosition(item.collateralType(), item.estimatedValue()))
                                .toList(),
                        documents),
                LocalDate.now(clock),
                submitting));
    }

    private LoanApplicationResponse assemble(ApplicationRecord application) {
        Offered offered = loadPinned(application);
        AccountRecord account = application.disbursementAccountId() == null
                ? null
                : accounts.find(application.customerId(), application.disbursementAccountId()).orElse(null);
        return views.detail(
                application,
                offered.product(),
                offered.version(),
                account,
                applications.findFinancials(application.id()),
                applications.listGuarantors(application.id()),
                applications.listCollaterals(application.id()),
                applications.listDocuments(application.id()),
                offered.components().documents());
    }

    private Offered loadOffered(UUID productId) {
        LoanProductRecord product = products.findProduct(productId).orElseThrow(() -> new ResourceNotFoundException("Loan product"));
        if (product.status() != ProductStatus.ACTIVE || product.currentVersionId() == null) {
            throw ValidationException.field("productId", "Only an active loan product can accept applications");
        }
        LoanProductVersionRecord version = products.findVersion(product.currentVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan product"));
        if (version.status() != ProductStatus.ACTIVE) {
            throw ValidationException.field("productId", "Only an active loan product can accept applications");
        }
        return offered(product, version);
    }

    private Offered loadPinned(ApplicationRecord application) {
        LoanProductRecord product = products.findProduct(application.productId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan product"));
        LoanProductVersionRecord version = products.findVersion(application.productVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan product"));
        return offered(product, version);
    }

    private Offered offered(LoanProductRecord product, LoanProductVersionRecord version) {
        ProductComponents loaded = components.load(version.id());
        return new Offered(product, version, loaded, new ProductTerms(
                product.status(),
                version.status(),
                version.customerSegment(),
                version.minimumAmount(),
                version.maximumAmount(),
                version.minimumTenure(),
                version.maximumTenure(),
                version.tenureUnit(),
                version.repaymentFrequency(),
                version.guarantorRequired(),
                version.minimumGuarantors(),
                loaded.documents(),
                loaded.collateralRules(),
                loaded.eligibilityRules()));
    }

    private void assertStillOffered(ApplicationRecord application, Offered offered) {
        if (offered.product().status() != ProductStatus.ACTIVE
                || offered.product().currentVersionId() == null
                || !offered.product().currentVersionId().equals(application.productVersionId())
                || offered.version().status() != ProductStatus.ACTIVE) {
            throw ValidationException.field("productId", "This product version is no longer offered");
        }
    }

    private void assertEligible(CustomerRecord customer) {
        ApplicationRules.Eligibility result = ApplicationRules.eligibility(customer);
        if (result.complianceHold()) {
            throw new ConflictException(ErrorCode.COMPLIANCE_HOLD, "The customer cannot apply while blacklisted or on a compliance hold");
        }
        ApplicationRules.throwIfPresent(result.violations());
    }

    private boolean verified(UUID customerId, UUID accountId) {
        if (accountId == null) {
            return false;
        }
        return accounts.find(customerId, accountId)
                .map(account -> account.verificationStatus() == AccountVerificationStatus.VERIFIED)
                .orElse(false);
    }

    private PageResponse<LoanApplicationSummaryResponse> search(
            UUID customerId,
            boolean restrictBranches,
            List<UUID> branchIds,
            ApplicationStatus status,
            UUID productId,
            CustomerKind segment,
            String query,
            String sort,
            String direction,
            int page,
            int size,
            String defaultSort,
            List<ApplicationStatus> excluded) {
        int offset = offset(page, size);
        ApplicationSearch search = new ApplicationSearch(
                customerId,
                branchIds,
                restrictBranches,
                status,
                productId,
                segment,
                queryText(query),
                excluded,
                sortColumn(sort, defaultSort),
                ascending(direction),
                size,
                offset);
        List<LoanApplicationSummaryResponse> items = applications.search(search).stream().map(views::summary).toList();
        return new PageResponse<>(items, page, size, applications.count(search));
    }

    private CustomerRecord resolveCustomer(UUID requestedId, AuthenticatedUser actor) {
        if (actor.getPrincipalType() == PrincipalType.CUSTOMER) {
            CustomerRecord own = ownCustomer(actor);
            if (requestedId != null && !own.id().equals(requestedId)) {
                throw new ForbiddenException("You cannot apply for another customer");
            }
            return own;
        }
        if (requestedId == null) {
            throw ValidationException.field("customerId", "customerId is required");
        }
        return customers.findById(requestedId).orElseThrow(() -> new ResourceNotFoundException("Customer"));
    }

    private CustomerRecord ownCustomer(AuthenticatedUser actor) {
        return customers.findByUserId(actor.getId()).orElseThrow(() -> new ResourceNotFoundException("Customer"));
    }

    private CustomerRecord customerOf(ApplicationRecord application) {
        return customers.findById(application.customerId()).orElseThrow(() -> new ResourceNotFoundException("Loan application"));
    }

    private ApplicationRecord locked(UUID id) {
        return applications.lock(id).orElseThrow(() -> new ResourceNotFoundException("Loan application"));
    }

    private BranchScope scope(AuthenticatedUser actor, UUID branchId) {
        if (actor.unrestrictedBranches()) {
            return branchId == null ? new BranchScope(false, List.of()) : new BranchScope(true, List.of(branchId));
        }
        if (branchId != null) {
            ApplicationAccess.assertBranch(actor, branchId);
            return new BranchScope(true, List.of(branchId));
        }
        return new BranchScope(true, List.copyOf(actor.branchIds()));
    }

    private static ApplicationRecord evolve(
            ApplicationRecord current,
            ApplicationStatus status,
            int revision,
            boolean correctionOpen,
            LoanApplicationViews.DraftState state,
            Instant submittedAt,
            Instant withdrawnAt,
            String decisionReason,
            String updatedBy) {
        return new ApplicationRecord(
                current.id(),
                current.reference(),
                current.customerId(),
                current.productId(),
                current.productVersionId(),
                current.branchId(),
                current.segment(),
                status,
                revision,
                correctionOpen,
                state.amount(),
                current.currencyCode(),
                state.purpose(),
                state.tenure(),
                current.tenureUnit(),
                state.frequency(),
                state.accountId(),
                current.channel(),
                submittedAt,
                withdrawnAt,
                decisionReason,
                current.createdAt(),
                current.updatedAt(),
                current.createdBy(),
                updatedBy);
    }

    private static IndividualFinancials individual(IndividualFinancialsRequest request) {
        IndividualFinancials financials = new IndividualFinancials(
                ApplicationRules.money(request.monthlyIncome(), "individualFinancials.monthlyIncome", true),
                ApplicationRules.money(request.otherIncome(), "individualFinancials.otherIncome", true),
                ApplicationRules.money(request.monthlyExpenses(), "individualFinancials.monthlyExpenses", true),
                EmploymentStatus.parse(request.employmentStatus(), "individualFinancials.employmentStatus", false),
                text(request.employerName(), 255, "individualFinancials.employerName"),
                text(request.jobTitle(), 160, "individualFinancials.jobTitle"),
                request.employmentStartDate());
        return financials.captured() ? financials : null;
    }

    private static SmeFinancials sme(SmeFinancialsRequest request) {
        SmeFinancials financials = new SmeFinancials(
                ApplicationRules.money(request.annualTurnover(), "smeFinancials.annualTurnover", true),
                ApplicationRules.money(request.monthlyTurnover(), "smeFinancials.monthlyTurnover", true),
                ApplicationRules.money(request.operatingExpenses(), "smeFinancials.operatingExpenses", true),
                ApplicationRules.signedMoney(request.monthlyCashFlow(), "smeFinancials.monthlyCashFlow"),
                ApplicationRules.money(request.businessLiabilities(), "smeFinancials.businessLiabilities", true),
                request.authorisedToBorrow(),
                text(request.authorisedBorrowerName(), 255, "smeFinancials.authorisedBorrowerName"),
                ApplicationRules.money(request.authorisedBorrowingLimit(), "smeFinancials.authorisedBorrowingLimit", true));
        return financials.captured() ? financials : null;
    }

    private static Obligation obligation(ObligationRequest request) {
        return new Obligation(
                text(request.lender(), 160, "obligations.lender"),
                ApplicationRules.money(request.outstandingBalance(), "obligations.outstandingBalance", true),
                ApplicationRules.money(request.monthlyRepayment(), "obligations.monthlyRepayment", true));
    }

    private static GuarantorRecord guarantor(UUID applicationId, GuarantorRequest request) {
        String phone = request.phoneNumber().trim();
        if (!PHONE.matcher(phone).matches()) {
            throw ValidationException.field("guarantors.phoneNumber", "phoneNumber must be in international format");
        }
        String email = text(request.email(), 255, "guarantors.email");
        return new GuarantorRecord(
                UUID.randomUUID(),
                applicationId,
                text(request.fullName(), 255, "guarantors.fullName"),
                phone,
                email == null ? null : email.toLowerCase(Locale.ROOT),
                text(request.relationship(), 80, "guarantors.relationship"),
                text(request.occupation(), 160, "guarantors.occupation"),
                text(request.addressLine(), 500, "guarantors.addressLine"));
    }

    private static CollateralRecord collateral(UUID applicationId, CollateralRequest request) {
        String type = request.collateralType().trim().toUpperCase(Locale.ROOT);
        if (!COLLATERAL_TYPE.matcher(type).matches()) {
            throw ValidationException.field("collaterals.collateralType", "collateralType must be an uppercase code");
        }
        return new CollateralRecord(
                UUID.randomUUID(),
                applicationId,
                type,
                text(request.description(), 500, "collaterals.description"),
                ApplicationRules.money(request.estimatedValue(), "collaterals.estimatedValue", false),
                text(request.ownerName(), 255, "collaterals.ownerName"),
                text(request.location(), 500, "collaterals.location"));
    }

    private static ConflictException duplicate(int max) {
        return new ConflictException(max == 1
                ? "An active application already exists for this product"
                : "This product allows " + max + " active applications, and that limit has been reached");
    }

    private static <T> T value(boolean overlay, T current, T fallback) {
        if (overlay) {
            return current;
        }
        return fallback;
    }

    private static String text(String value, int max, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.length() > max) {
            throw ValidationException.field(field, "Must be at most " + max + " characters");
        }
        return trimmed;
    }

    private static ApplicationStatus status(String value) {
        return value == null || value.isBlank() ? null : ApplicationStatus.parse(value, "status");
    }

    private static CustomerKind segment(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return CustomerKind.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("customerSegment", "customerSegment must be INDIVIDUAL or SME");
        }
    }

    private static String queryText(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String trimmed = query.trim();
        if (trimmed.length() > 80) {
            throw ValidationException.field("q", "q must be at most 80 characters");
        }
        return trimmed;
    }

    private static String sortColumn(String sort, String defaultColumn) {
        if (sort == null || sort.isBlank()) {
            return defaultColumn;
        }
        return switch (sort) {
            case "submittedAt" -> "a.submitted_at";
            case "updatedAt" -> "a.updated_at";
            case "amount" -> "a.amount";
            case "reference" -> "a.reference";
            default -> throw ValidationException.field("sort", "sort must be submittedAt, updatedAt, amount, or reference");
        };
    }

    private static boolean ascending(String direction) {
        if (direction == null || direction.isBlank() || "desc".equalsIgnoreCase(direction)) {
            return false;
        }
        if ("asc".equalsIgnoreCase(direction)) {
            return true;
        }
        throw ValidationException.field("direction", "direction must be asc or desc");
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

    private record Offered(
            LoanProductRecord product,
            LoanProductVersionRecord version,
            ProductComponents components,
            ProductTerms terms
    ) {
    }

    private record BranchScope(boolean restrict, List<UUID> branchIds) {
    }
}
