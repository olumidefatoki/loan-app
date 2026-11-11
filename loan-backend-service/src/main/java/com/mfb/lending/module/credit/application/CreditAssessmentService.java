package com.mfb.lending.module.credit.application;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.credit.bureau.BureauSubject;
import com.mfb.lending.module.credit.bureau.CreditBureauPort;
import com.mfb.lending.module.credit.bureau.MappedBureauResponse;
import com.mfb.lending.module.credit.domain.AnalystRecommendation;
import com.mfb.lending.module.credit.domain.AssessmentFacts;
import com.mfb.lending.module.credit.domain.AssessmentStatus;
import com.mfb.lending.module.credit.domain.BureauView;
import com.mfb.lending.module.credit.domain.CreditAssessmentRecord;
import com.mfb.lending.module.credit.domain.CreditBureauCheck;
import com.mfb.lending.module.credit.domain.CreditComment;
import com.mfb.lending.module.credit.domain.CreditPolicyException;
import com.mfb.lending.module.credit.domain.CreditResultRecord;
import com.mfb.lending.module.credit.domain.CreditRule;
import com.mfb.lending.module.credit.domain.DecisionInput;
import com.mfb.lending.module.credit.domain.DecisionOutcome;
import com.mfb.lending.module.credit.domain.DecisionReason;
import com.mfb.lending.module.credit.domain.DecisionResult;
import com.mfb.lending.module.credit.domain.ScoreComponentResult;
import com.mfb.lending.module.credit.domain.ScoringVersionRecord;
import com.mfb.lending.module.credit.dto.BureauCheckResponse;
import com.mfb.lending.module.credit.dto.CommentRequest;
import com.mfb.lending.module.credit.dto.CommentResponse;
import com.mfb.lending.module.credit.dto.CreateCreditAssessmentRequest;
import com.mfb.lending.module.credit.dto.CreditAssessmentResponse;
import com.mfb.lending.module.credit.dto.CreditQueueItemResponse;
import com.mfb.lending.module.credit.dto.CreditReportResponse;
import com.mfb.lending.module.credit.dto.RecommendRequest;
import com.mfb.lending.module.credit.persistence.CreditAssessmentRepository;
import com.mfb.lending.module.credit.persistence.CreditPolicyRepository;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import com.mfb.lending.module.loanapplication.domain.ApplicationRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;
import com.mfb.lending.module.loanapplication.persistence.LoanApplicationRepository;
import com.mfb.lending.module.loanproduct.domain.CollateralRule;
import com.mfb.lending.module.loanproduct.domain.CustomerSegment;
import com.mfb.lending.module.loanproduct.domain.LoanProductVersionRecord;
import com.mfb.lending.module.loanproduct.persistence.LoanProductComponentRepository;
import com.mfb.lending.module.loanproduct.persistence.LoanProductRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CreditAssessmentService {

    private static final Set<ApplicationStatus> CLOSED_APPLICATIONS = Set.of(
            ApplicationStatus.REJECTED,
            ApplicationStatus.DISBURSED,
            ApplicationStatus.WITHDRAWN,
            ApplicationStatus.CANCELLED,
            ApplicationStatus.EXPIRED);

    private final CreditAssessmentRepository assessments;
    private final CreditPolicyRepository policies;
    private final LoanApplicationRepository applications;
    private final LoanProductRepository products;
    private final LoanProductComponentRepository components;
    private final CustomerRepository customers;
    private final CreditBureauPort bureau;
    private final CreditViews views;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final java.time.Clock clock;
    private final TransactionTemplate transactions;

    public CreditAssessmentService(
            CreditAssessmentRepository assessments,
            CreditPolicyRepository policies,
            LoanApplicationRepository applications,
            LoanProductRepository products,
            LoanProductComponentRepository components,
            CustomerRepository customers,
            CreditBureauPort bureau,
            CreditViews views,
            AuditService audit,
            ObjectMapper objectMapper,
            java.time.Clock clock,
            TransactionTemplate transactions) {
        this.assessments = assessments;
        this.policies = policies;
        this.applications = applications;
        this.products = products;
        this.components = components;
        this.customers = customers;
        this.bureau = bureau;
        this.views = views;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactions = transactions;
    }

    public CreditAssessmentResponse create(
            CreateCreditAssessmentRequest request,
            AuthenticatedUser actor,
            ClientContext client) {
        CreditAssessmentResponse response = transactions.execute(status -> createLocked(request, actor));
        audit.record("CREDIT_ASSESSMENT_CREATED", "credit_assessment", response.id().toString(), actor.getUsername(), client, Map.of(
                "reference", response.reference(),
                "applicationId", response.applicationId().toString()));
        return response;
    }

    public CreditAssessmentResponse get(UUID id, AuthenticatedUser actor) {
        CreditAssessmentRecord assessment = require(id);
        CreditAccess.assertBranch(actor, assessment.branchId());
        return views.assessment(assessment, reasons(assessment));
    }

    public CreditAssessmentResponse evaluate(UUID id, AuthenticatedUser actor, ClientContext client) {
        CreditAssessmentResponse response = transactions.execute(status -> evaluateLocked(id, actor));
        audit.record("CREDIT_ASSESSMENT_EVALUATED", "credit_assessment", id.toString(), actor.getUsername(), client, Map.of(
                "decision", response.decision(),
                "scoringModelVersionId", String.valueOf(response.scoringModelVersionId()),
                "policyVersion", String.valueOf(response.policyVersion())));
        return response;
    }

    public BureauCheckResponse bureauCheck(UUID id, AuthenticatedUser actor, ClientContext client) {
        BureauCheckResponse response = transactions.execute(status -> bureauLocked(id, actor));
        audit.record("CREDIT_BUREAU_ENQUIRY", "credit_assessment", id.toString(), actor.getUsername(), client, Map.of(
                "providerCode", response.providerCode(),
                "status", response.status()));
        return response;
    }

    public CreditAssessmentResponse recommend(
            UUID id,
            RecommendRequest request,
            AuthenticatedUser actor,
            ClientContext client) {
        CreditAssessmentResponse response = transactions.execute(status -> recommendLocked(id, request, actor));
        audit.record("CREDIT_RECOMMENDATION_RECORDED", "credit_assessment", id.toString(), actor.getUsername(), client, Map.of(
                "recommendation", request.recommendation(),
                "decision", String.valueOf(response.decision())));
        return response;
    }

    public CommentResponse comment(UUID id, CommentRequest request, AuthenticatedUser actor, ClientContext client) {
        CommentResponse response = transactions.execute(status -> commentLocked(id, request, actor));
        audit.record("CREDIT_COMMENT_ADDED", "credit_assessment", id.toString(), actor.getUsername(), client, Map.of(
                "commentId", response.id().toString()));
        return response;
    }

    public CreditReportResponse report(UUID id, AuthenticatedUser actor) {
        CreditAssessmentRecord assessment = require(id);
        CreditAccess.assertBranch(actor, assessment.branchId());
        CreditResultRecord latest = assessments.latestResult(assessment.id()).orElse(null);
        List<ScoreComponentResult> components = latest == null ? List.of() : assessments.listComponents(latest.id());
        return views.report(
                views.assessment(assessment, latest == null ? List.of() : assessments.listReasons(latest.id())),
                components,
                assessments.listResults(assessment.id()),
                assessments.listBureau(assessment.id()),
                assessments.listComments(assessment.id()),
                assessments.listExceptions(assessment.id()));
    }

    public PageResponse<CreditQueueItemResponse> queue(
            AuthenticatedUser actor,
            String decision,
            boolean includeUnassigned,
            int page,
            int size) {
        int offset = offset(page, size);
        if (decision != null && !decision.isBlank()) {
            parseDecision(decision);
        } else {
            decision = null;
        }
        boolean restrict = !actor.unrestrictedBranches();
        if (restrict && actor.branchIds().isEmpty()) {
            return new PageResponse<>(List.of(), page, size, 0);
        }
        List<UUID> branches = List.copyOf(actor.branchIds());
        String filter = decision;
        List<CreditQueueItemResponse> items = assessments.queue(
                        actor.getId(), includeUnassigned, filter, restrict, branches, size, offset)
                .stream()
                .map(views::queueItem)
                .toList();
        long total = assessments.queueCount(actor.getId(), includeUnassigned, filter, restrict, branches);
        return new PageResponse<>(items, page, size, total);
    }

    private CreditAssessmentResponse createLocked(CreateCreditAssessmentRequest request, AuthenticatedUser actor) {
        ApplicationRecord application = applications.lock(request.applicationId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        if (CLOSED_APPLICATIONS.contains(application.status())) {
            throw new ConflictException("Credit assessment is not available for an application in this status");
        }
        CustomerRecord customer = customers.findById(application.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer"));
        CreditAccess.assertBranch(actor, application.branchId());
        LoanProductVersionRecord version = products.findVersion(application.productVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan product version"));
        AssessmentFacts facts = CreditFactsResolver.resolve(
                application.segment(),
                applications.findFinancials(application.id()),
                applications.listCollaterals(application.id()),
                components.load(version.id()).collateralRules(),
                application.amount(),
                request,
                LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC));
        UUID id = UUID.randomUUID();
        String reference = assessments.nextReference(clock.instant().atZone(ZoneOffset.UTC).getYear());
        try {
            assessments.insert(new CreditAssessmentRecord(
                    id,
                    reference,
                    application.id(),
                    application.customerId(),
                    application.productId(),
                    application.productVersionId(),
                    application.branchId(),
                    application.segment().name(),
                    AssessmentStatus.OPEN,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    json(facts),
                    null,
                    null,
                    null,
                    null,
                    null,
                    actor.getId(),
                    1,
                    null,
                    null,
                    actor.getUsername(),
                    actor.getUsername()));
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("An open credit assessment already exists for this application");
        }
        return views.assessment(require(id), List.of());
    }

    private CreditAssessmentResponse evaluateLocked(UUID id, AuthenticatedUser actor) {
        CreditAssessmentRecord assessment = assessments.lock(id)
                .orElseThrow(() -> new ResourceNotFoundException("Credit assessment"));
        CreditAccess.assertBranch(actor, assessment.branchId());
        DecisionResult decision = decide(assessment);
        UUID resultId = UUID.randomUUID();
        String calculations = json(decision.calculations());
        String explanation = decision.score().explanation() == null ? decision.outcome().name() : decision.score().explanation();
        assessments.insertResult(new CreditResultRecord(
                resultId,
                assessment.id(),
                decision.score().modelId(),
                decision.score().modelVersionId(),
                decision.score().policyVersion(),
                decision.score().score(),
                decision.score().riskCategory(),
                decision.outcome(),
                limit(explanation, 4000),
                null), calculations);
        for (ScoreComponentResult component : decision.score().components()) {
            assessments.insertComponent(UUID.randomUUID(), assessment.id(), resultId, new ScoreComponentResult(
                    component.code(),
                    component.metric(),
                    component.rawValue(),
                    component.rawScore(),
                    component.weight(),
                    component.weightedScore(),
                    limit(component.explanation(), 1000)));
        }
        for (DecisionReason reason : decision.reasons()) {
            assessments.insertReason(UUID.randomUUID(), assessment.id(), resultId, new DecisionReason(
                    limit(reason.code(), 40),
                    reason.severity(),
                    limit(reason.message(), 1000),
                    reason.blocksApproval()));
        }
        assessments.saveEvaluation(
                assessment.id(),
                AssessmentStatus.forDecision(decision.outcome()),
                decision.outcome(),
                decision.score().score(),
                decision.score().riskCategory(),
                decision.score().modelId(),
                decision.score().modelVersionId(),
                decision.score().policyVersion(),
                calculations,
                actor.getUsername());
        return views.assessment(require(assessment.id()), decision.reasons());
    }

    private DecisionResult decide(CreditAssessmentRecord assessment) {
        ApplicationRecord application = applications.find(assessment.applicationId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        CustomerRecord customer = customers.findById(assessment.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer"));
        LoanProductVersionRecord version = products.findVersion(assessment.productVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan product version"));
        List<CollateralRule> collateralRules = components.load(version.id()).collateralRules();
        ScoringVersionRecord model = policies.findActiveModel(assessment.productId(), assessment.segment())
                .orElseThrow(() -> ValidationException.field("scoringModel", "No active scoring model applies to this application"));
        AssessmentFacts facts = read(assessment.inputsJson(), AssessmentFacts.class);
        CreditBureauCheck check = assessments.latestBureau(assessment.id()).orElse(null);
        boolean collateralRequired = collateralRules.stream().anyMatch(CollateralRule::mandatory);
        DecisionInput input = new DecisionInput(
                customer.status(),
                customer.kycStatus(),
                customer.blacklisted(),
                customer.complianceHold(),
                productEligible(version.customerSegment(), customer.kind(), assessment.segment()),
                application.amount(),
                version.minimumAmount(),
                version.maximumAmount(),
                collateralRequired,
                coverageFloor(collateralRules),
                version.creditBureauRequired(),
                version.minimumBureauScore(),
                bureauView(check),
                facts,
                policies.applicableRules(assessment.segment(), assessment.productId()),
                new HashSet<>(assessments.approvedExceptionCodes(assessment.id())),
                model.weights(),
                model.riskCategories(),
                model.thresholds(),
                model.modelId(),
                model.id(),
                model.policyVersion());
        return CreditDecisionEngine.decide(input);
    }

    private BureauCheckResponse bureauLocked(UUID id, AuthenticatedUser actor) {
        CreditAssessmentRecord assessment = assessments.lock(id)
                .orElseThrow(() -> new ResourceNotFoundException("Credit assessment"));
        CreditAccess.assertBranch(actor, assessment.branchId());
        CustomerRecord customer = customers.findById(assessment.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer"));
        BureauSubject subject = new BureauSubject(
                assessment.id(),
                customer.id(),
                customer.reference(),
                customer.kind(),
                customer.displayName());
        var identification = bureau.identify(subject);
        var enquiry = bureau.enquire(subject);
        var report = bureau.retrieve(subject, enquiry);
        var exposure = bureau.assessExposure(enquiry, report);
        MappedBureauResponse mapped = BureauResponseMapper.map(bureau.providerCode(), identification, enquiry, report, exposure);
        UUID checkId = UUID.randomUUID();
        assessments.insertBureau(new CreditBureauCheck(
                checkId,
                assessment.id(),
                customer.id(),
                mapped.providerCode(),
                mapped.status(),
                mapped.identificationMatched(),
                mapped.score(),
                mapped.existingExposure(),
                json(mapped),
                mapped.failureReason(),
                actor.getId(),
                clock.instant(),
                clock.instant()));
        return views.bureau(assessments.latestBureau(assessment.id()).orElseThrow());
    }

    private CreditAssessmentResponse recommendLocked(UUID id, RecommendRequest request, AuthenticatedUser actor) {
        CreditAssessmentRecord assessment = assessments.lock(id)
                .orElseThrow(() -> new ResourceNotFoundException("Credit assessment"));
        CreditAccess.assertBranch(actor, assessment.branchId());
        if (request.exceptionId() != null) {
            approveException(assessment, request, actor);
            return evaluateLocked(assessment.id(), actor);
        }
        if (assessment.decision() == null) {
            throw new ConflictException("Evaluate the assessment before submitting a recommendation");
        }
        AnalystRecommendation recommendation = AnalystRecommendation.parse(request.recommendation());
        if (recommendation == AnalystRecommendation.APPROVE) {
            return requestApproval(assessment, request.justification(), actor);
        }
        DecisionOutcome decision = assessment.decision();
        AssessmentStatus status = assessment.status();
        if (recommendation == AnalystRecommendation.REQUEST_INFORMATION && decision != DecisionOutcome.COMPLIANCE_HOLD) {
            decision = DecisionOutcome.PENDING_DATA;
            status = AssessmentStatus.PENDING_INFORMATION;
        }
        assessments.insertComment(new CreditComment(
                UUID.randomUUID(), assessment.id(), actor.getId(), request.justification().trim(), null));
        assessments.saveRecommendation(
                assessment.id(),
                recommendation,
                request.justification().trim(),
                actor.getId(),
                clock.instant(),
                status,
                decision,
                actor.getUsername());
        return views.assessment(require(assessment.id()), reasons(require(assessment.id())));
    }

    private CreditAssessmentResponse requestApproval(
            CreditAssessmentRecord assessment,
            String justification,
            AuthenticatedUser actor) {
        if (assessment.decision() == DecisionOutcome.COMPLIANCE_HOLD) {
            throw new ConflictException("A compliance hold cannot be overridden");
        }
        if (assessment.decision() == DecisionOutcome.PENDING_DATA) {
            throw new ConflictException("Supply the missing information before requesting approval");
        }
        if (assessment.decision() == DecisionOutcome.AUTO_APPROVE) {
            saveAdvice(assessment, AnalystRecommendation.APPROVE, justification, actor);
            return views.assessment(require(assessment.id()), reasons(require(assessment.id())));
        }
        CreditResultRecord latest = assessments.latestResult(assessment.id())
                .orElseThrow(() -> new ConflictException("Evaluate the assessment before submitting a recommendation"));
        List<DecisionReason> blocking = assessments.listReasons(latest.id()).stream()
                .filter(DecisionReason::blocksApproval)
                .toList();
        List<CreditRule> rules = policies.applicableRules(assessment.segment(), assessment.productId());
        Set<String> pendingOrApproved = new HashSet<>();
        for (CreditPolicyException exception : assessments.listExceptions(assessment.id())) {
            if ("PENDING".equals(exception.status()) || "APPROVED".equals(exception.status())) {
                pendingOrApproved.add(exception.ruleCode());
            }
        }
        boolean scoreFloor = blocking.stream().anyMatch(reason -> "SCORE_BELOW_REFER".equals(reason.code()));
        List<CreditRule> waivable = blocking.stream()
                .map(reason -> rules.stream().filter(rule -> rule.code().equals(reason.code())).findFirst().orElse(null))
                .filter(rule -> rule != null && rule.exceptionAllowed())
                .toList();
        boolean locked = blocking.stream().anyMatch(reason -> {
            if ("SCORE_BELOW_REFER".equals(reason.code()) || "SCORE_RECORDED".equals(reason.code())) {
                return false;
            }
            return waivable.stream().noneMatch(rule -> rule.code().equals(reason.code()));
        });
        if (locked || scoreFloor) {
            throw new ConflictException("A mandatory policy failure or compliance hold cannot be overridden");
        }
        if (waivable.isEmpty() && assessment.decision() == DecisionOutcome.REFER) {
            openException(assessment, "SCORE_THRESHOLD", justification, actor, pendingOrApproved);
        }
        for (CreditRule rule : waivable) {
            openException(assessment, rule.code(), justification, actor, pendingOrApproved);
        }
        saveAdvice(assessment, AnalystRecommendation.APPROVE, justification, actor);
        return views.assessment(require(assessment.id()), assessments.listReasons(latest.id()));
    }

    private void openException(
            CreditAssessmentRecord assessment,
            String ruleCode,
            String justification,
            AuthenticatedUser actor,
            Set<String> pendingOrApproved) {
        if (pendingOrApproved.contains(ruleCode)) {
            return;
        }
        CreditRule rule = policies.findRuleByCode(ruleCode)
                .orElseThrow(() -> new ConflictException("No exception policy exists for " + ruleCode));
        if (!rule.exceptionAllowed()) {
            throw new ConflictException("A mandatory policy failure or compliance hold cannot be overridden");
        }
        try {
            assessments.insertException(new CreditPolicyException(
                    UUID.randomUUID(),
                    assessment.id(),
                    rule.code(),
                    justification.trim(),
                    "PENDING",
                    actor.getId(),
                    null,
                    null,
                    null,
                    null));
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("A policy exception is already pending for " + ruleCode);
        }
        pendingOrApproved.add(ruleCode);
        audit.record(
                "CREDIT_EXCEPTION_REQUESTED",
                "credit_policy_exception",
                assessment.id().toString(),
                actor.getUsername(),
                null,
                Map.of("ruleCode", ruleCode));
    }

    private void saveAdvice(
            CreditAssessmentRecord assessment,
            AnalystRecommendation recommendation,
            String justification,
            AuthenticatedUser actor) {
        assessments.saveRecommendation(
                assessment.id(),
                recommendation,
                justification.trim(),
                actor.getId(),
                clock.instant(),
                assessment.status(),
                assessment.decision(),
                actor.getUsername());
    }

    private void approveException(CreditAssessmentRecord assessment, RecommendRequest request, AuthenticatedUser actor) {
        CreditPolicyException exception = assessments.lockException(request.exceptionId())
                .orElseThrow(() -> new ResourceNotFoundException("Credit policy exception"));
        if (!exception.assessmentId().equals(assessment.id())) {
            throw new ResourceNotFoundException("Credit policy exception");
        }
        if (!"PENDING".equals(exception.status())) {
            throw new ConflictException("The policy exception has already been decided");
        }
        if (exception.requestedBy().equals(actor.getId())) {
            throw new ForbiddenException("Another officer must approve this policy exception");
        }
        int updated = assessments.approveException(exception.id(), actor.getId(), clock.instant(), request.justification().trim());
        if (updated != 1) {
            throw new ConflictException("The policy exception has already been decided");
        }
        audit.record(
                "CREDIT_EXCEPTION_APPROVED",
                "credit_policy_exception",
                exception.id().toString(),
                actor.getUsername(),
                null,
                Map.of("ruleCode", exception.ruleCode(), "assessmentId", assessment.id().toString()));
    }

    private CommentResponse commentLocked(UUID id, CommentRequest request, AuthenticatedUser actor) {
        CreditAssessmentRecord assessment = assessments.lock(id)
                .orElseThrow(() -> new ResourceNotFoundException("Credit assessment"));
        CreditAccess.assertBranch(actor, assessment.branchId());
        UUID commentId = UUID.randomUUID();
        assessments.insertComment(new CreditComment(commentId, assessment.id(), actor.getId(), request.comment().trim(), null));
        return assessments.listComments(assessment.id()).stream()
                .filter(comment -> comment.id().equals(commentId))
                .findFirst()
                .map(comment -> new CommentResponse(comment.id(), comment.authorId(), comment.body(), comment.createdAt()))
                .orElseThrow(() -> new IllegalStateException("Credit comment was not stored"));
    }

    private List<DecisionReason> reasons(CreditAssessmentRecord assessment) {
        return assessments.latestResult(assessment.id())
                .map(result -> assessments.listReasons(result.id()))
                .orElse(List.of());
    }

    private CreditAssessmentRecord require(UUID id) {
        return assessments.find(id).orElseThrow(() -> new ResourceNotFoundException("Credit assessment"));
    }

    private BureauView bureauView(CreditBureauCheck check) {
        if (check == null) {
            return BureauView.none();
        }
        MappedBureauResponse mapped = read(check.mappedResponseJson(), MappedBureauResponse.class);
        return new BureauView(check.status(), check.bureauScore(), check.existingExposure(), mapped.repaymentHistory());
    }

    private static boolean productEligible(CustomerSegment productSegment, CustomerKind customerKind, String assessmentSegment) {
        if (!customerKind.name().equals(assessmentSegment)) {
            return false;
        }
        return switch (productSegment) {
            case BOTH -> true;
            case INDIVIDUAL -> customerKind == CustomerKind.INDIVIDUAL;
            case SME -> customerKind == CustomerKind.SME;
        };
    }

    private static BigDecimal coverageFloor(List<CollateralRule> rules) {
        return rules.stream()
                .filter(CollateralRule::mandatory)
                .map(CollateralRule::minimumCoveragePercent)
                .max(BigDecimal::compareTo)
                .map(percent -> percent.movePointLeft(2))
                .orElse(null);
    }

    private static DecisionOutcome parseDecision(String value) {
        try {
            return DecisionOutcome.valueOf(value.trim());
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("decision", "Unknown decision");
        }
    }

    private static int offset(int page, int size) {
        if (page < 0 || page > 10_000) {
            throw ValidationException.field("page", "page is out of range");
        }
        if (size < 1 || size > 100) {
            throw ValidationException.field("size", "size must be between 1 and 100");
        }
        return page * size;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Credit assessment payload could not be written", ex);
        }
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Credit assessment payload could not be read", ex);
        }
    }

    private static String limit(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
