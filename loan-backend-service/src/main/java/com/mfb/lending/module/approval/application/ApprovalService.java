package com.mfb.lending.module.approval.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.common.correlation.CorrelationIds;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.approval.domain.ApprovalAssignment;
import com.mfb.lending.module.approval.domain.ApprovalDecision;
import com.mfb.lending.module.approval.domain.ApprovalDelegation;
import com.mfb.lending.module.approval.domain.ApprovalExceptionCase;
import com.mfb.lending.module.approval.domain.ApprovalHistoryEntry;
import com.mfb.lending.module.approval.domain.ApprovalLevel;
import com.mfb.lending.module.approval.domain.ApprovalStage;
import com.mfb.lending.module.approval.domain.ApprovalStatus;
import com.mfb.lending.module.approval.domain.LoanApproval;
import com.mfb.lending.module.approval.domain.MatrixVersion;
import com.mfb.lending.module.approval.dto.ApprovalHistoryResponse;
import com.mfb.lending.module.approval.dto.ApprovalResponse;
import com.mfb.lending.module.approval.dto.DelegateApprovalRequest;
import com.mfb.lending.module.approval.persistence.ApprovalRepository;
import com.mfb.lending.module.approval.persistence.ApprovalRepository.PledgeFact;
import com.mfb.lending.module.approval.persistence.ApprovalRepository.ProductGate;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.credit.domain.CreditAssessmentRecord;
import com.mfb.lending.module.credit.domain.CreditResultRecord;
import com.mfb.lending.module.credit.domain.DecisionOutcome;
import com.mfb.lending.module.credit.domain.DecisionReason;
import com.mfb.lending.module.credit.persistence.CreditAssessmentRepository;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.CustomerStatus;
import com.mfb.lending.module.customer.domain.KycStatus;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import com.mfb.lending.module.loanapplication.application.ApplicationWorkflow;
import com.mfb.lending.module.loanapplication.domain.ApplicationRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;
import com.mfb.lending.module.loanapplication.persistence.LoanApplicationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ApprovalService {

    private static final Pattern EXCEPTION_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");

    private final ApprovalRepository approvals;
    private final LoanApplicationRepository applications;
    private final CustomerRepository customers;
    private final CreditAssessmentRepository assessments;
    private final ApprovalViews views;
    private final AuditService audit;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ApprovalService(
            ApprovalRepository approvals,
            LoanApplicationRepository applications,
            CustomerRepository customers,
            CreditAssessmentRepository assessments,
            ApprovalViews views,
            AuditService audit,
            ObjectMapper objectMapper,
            Clock clock,
            TransactionTemplate transactions) {
        this.approvals = approvals;
        this.applications = applications;
        this.customers = customers;
        this.assessments = assessments;
        this.views = views;
        this.audit = audit;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactions = transactions;
    }

    public ApprovalResponse create(UUID applicationId, String exceptionType, String reason, AuthenticatedUser actor, ClientContext client) {
        ApprovalResponse response = transactions.execute(status -> openLocked(applicationId, exceptionType, reason, actor, true));
        audit.record(response.automated() && "APPROVED".equals(response.status()) ? "APPROVAL_GRANTED" : "APPROVAL_REQUESTED",
                "loan_approval", response.id().toString(), actor.getUsername(), client, Map.of(
                        "applicationId", response.applicationId().toString(),
                        "status", response.status(),
                        "amount", response.amount().toPlainString()));
        return response;
    }

    public void consider(UUID applicationId, AuthenticatedUser actor, ClientContext client) {
        ApprovalResponse response = transactions.execute(status -> openLocked(applicationId, null, null, actor, false));
        if (response == null) {
            return;
        }
        audit.record(response.automated() && "APPROVED".equals(response.status()) ? "APPROVAL_GRANTED" : "APPROVAL_REQUESTED",
                "loan_approval", response.id().toString(), actor.getUsername(), client, Map.of(
                        "applicationId", applicationId.toString(),
                        "status", response.status(),
                        "automated", response.automated()));
    }

    public void noteRevision(UUID applicationId, UUID actorId) {
        transactions.executeWithoutResult(status -> {
            ApplicationRecord application = applications.lock(applicationId)
                    .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
            reconcile(application, latestResult(applicationId), actorId);
        });
    }

    public ApprovalResponse get(UUID id, AuthenticatedUser actor) {
        return transactions.execute(status -> views.approval(prepareForActor(id, actor)));
    }

    public PageResponse<ApprovalResponse> queue(AuthenticatedUser actor, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw ValidationException.field("page", "page must be at least 0 and size must be from 1 to 100");
        }
        List<ApprovalResponse> visible = transactions.execute(status -> queueLocked(actor));
        int from = Math.min(page * size, visible.size());
        int to = Math.min(from + size, visible.size());
        return new PageResponse<>(List.copyOf(visible.subList(from, to)), page, size, visible.size());
    }

    public ApprovalResponse approve(UUID id, int version, String reason, AuthenticatedUser actor, ClientContext client) {
        return decide(id, ApprovalDecision.APPROVE, version, reason, actor, client, "APPROVAL_GRANTED");
    }

    public ApprovalResponse reject(UUID id, int version, String reason, AuthenticatedUser actor, ClientContext client) {
        return decide(id, ApprovalDecision.REJECT, version, reason, actor, client, "APPROVAL_REJECTED");
    }

    public ApprovalResponse returnForCorrection(UUID id, int version, String reason, AuthenticatedUser actor, ClientContext client) {
        return decide(id, ApprovalDecision.RETURN, version, reason, actor, client, "APPROVAL_RETURNED");
    }

    public ApprovalResponse requestInformation(UUID id, int version, String reason, AuthenticatedUser actor, ClientContext client) {
        return decide(id, ApprovalDecision.REQUEST_INFORMATION, version, reason, actor, client, "APPROVAL_INFORMATION_REQUESTED");
    }

    public ApprovalResponse delegate(UUID id, DelegateApprovalRequest request, AuthenticatedUser actor, ClientContext client) {
        ApprovalResponse response = transactions.execute(status -> delegateLocked(id, request, actor));
        audit.record("APPROVAL_DELEGATED", "loan_approval", id.toString(), actor.getUsername(), client, Map.of(
                "delegateUserId", request.delegateUserId().toString(),
                "stage", response.stage()));
        return response;
    }

    public List<ApprovalHistoryResponse> history(UUID id, AuthenticatedUser actor) {
        get(id, actor);
        return approvals.listHistory(id).stream().map(views::history).toList();
    }

    private ApprovalResponse decide(
            UUID id,
            ApprovalDecision decision,
            int version,
            String reason,
            AuthenticatedUser actor,
            ClientContext client,
            String action) {
        ApprovalResponse response = transactions.execute(status -> decideLocked(id, decision, version, reason.trim(), actor));
        audit.record(action, "loan_approval", id.toString(), actor.getUsername(), client, Map.of(
                "decision", decision.name(),
                "stage", response.stage(),
                "status", response.status(),
                "amount", response.amount().toPlainString()));
        return response;
    }

    private ApprovalResponse openLocked(
            UUID applicationId,
            String exceptionType,
            String reason,
            AuthenticatedUser actor,
            boolean strict) {
        ApplicationRecord application = applications.lock(applicationId).orElse(null);
        if (application == null) {
            if (!strict) {
                return null;
            }
            throw new ResourceNotFoundException("Loan application");
        }
        if (strict) {
            assertStaff(actor, application.branchId());
        }
        if (application.status() != ApplicationStatus.CREDIT_ASSESSMENT && application.status() != ApplicationStatus.PENDING_APPROVAL) {
            if (!strict) {
                return null;
            }
            throw ValidationException.field("status", "Approval starts when the application is in credit assessment");
        }
        String exception = exception(exceptionType, reason, strict);
        CreditResultRecord result = latestResult(applicationId);
        reconcile(application, result, actor.getId());
        application = applications.find(applicationId).orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        LoanApproval open = approvals.findOpen(applicationId).orElse(null);
        if (open != null) {
            if (!strict) {
                return views.approval(open);
            }
            throw new ConflictException("An approval request is already open for this application");
        }
        if (result == null) {
            if (!strict) {
                return null;
            }
            throw new ConflictException("A completed credit assessment is required");
        }
        CreditAssessmentRecord assessment = assessments.find(result.assessmentId())
                .orElseThrow(() -> new ConflictException("A completed credit assessment is required"));
        CustomerRecord customer = customers.findById(application.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer"));
        ProductGate product = approvals.productGate(application.productVersionId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan product"));
        boolean blocking = blocking(result);
        boolean complianceClear = complianceClear(customer, result.decision());
        boolean eligibilityClear = eligibilityClear(customer);
        Position position = position(application, product.category());
        MatrixVersion version = ApprovalRules.select(approvals.listActiveVersions(), new ApprovalRules.MatchFacts(
                application.amount(),
                application.productId(),
                application.segment().name(),
                application.branchId(),
                customer.riskClassification().name(),
                position.collateralStatus(),
                position.securityClassification(),
                exception));
        if (version == null) {
            if (!strict) {
                return null;
            }
            throw new ConflictException("No active approval matrix covers this application");
        }
        List<ApprovalLevel> required = ApprovalRules.requiredLevels(approvals.listLevels(version.id()), application.amount());
        boolean automate = ApprovalRules.automated(new ApprovalRules.AutomationGate(
                product.automatedApprovalAllowed(),
                eligibilityClear,
                result.decision() == DecisionOutcome.AUTO_APPROVE && !blocking,
                complianceClear,
                blocking,
                exception), version, required);
        if (!automate) {
            try {
                ApprovalRules.assertManualPermitted(result.decision().name(), complianceClear, blocking, exception);
            } catch (ConflictException ex) {
                if (!strict) {
                    return null;
                }
                throw ex;
            }
        }
        Instant now = clock.instant();
        UUID approvalId = UUID.randomUUID();
        UUID exceptionId = null;
        if (exception != null) {
            exceptionId = UUID.randomUUID();
            approvals.insertException(new ApprovalExceptionCase(
                    exceptionId, null, application.id(), exception, reason.trim(), "PENDING", actor.getId(), null, null, now));
        }
        ApprovalLevel first = required.get(0);
        String reference = approvals.nextReference(now.atZone(ZoneOffset.UTC).getYear());
        String correlation = correlation();
        LoanApproval created = new LoanApproval(
                approvalId,
                reference,
                application.id(),
                application.revisionNumber(),
                assessment.id(),
                result.id(),
                version.matrixId(),
                version.id(),
                exceptionId,
                application.amount(),
                application.currencyCode(),
                first.stage(),
                first.sequence(),
                automate ? ApprovalStatus.APPROVED : ApprovalStatus.PENDING,
                automate ? "APPROVE" : null,
                automate ? "Automated approval" : null,
                null,
                automate ? now : null,
                automate ? null : expiry(first, now),
                automate,
                actor.getId(),
                correlation,
                0,
                now,
                now);
        approvals.insertApproval(created);
        if (exceptionId != null) {
            approvals.attachException(exceptionId, approvalId);
        }
        history(created, actor.getId(), "MAKER", ApprovalDecision.OPEN, reason == null ? "Approval requested" : reason.trim(), now);
        if (automate) {
            history(created, null, "SYSTEM", ApprovalDecision.APPROVE, "Automated approval", now);
            ApplicationRecord pending = application.status() == ApplicationStatus.CREDIT_ASSESSMENT
                    ? move(application, ApplicationStatus.PENDING_APPROVAL, false, "Approval requested", actor)
                    : application;
            move(pending, ApplicationStatus.APPROVED, false, "Automated approval", actor);
        } else {
            assign(created, first.stage(), null, actor.getId(), null);
            if (application.status() == ApplicationStatus.CREDIT_ASSESSMENT) {
                move(application, ApplicationStatus.PENDING_APPROVAL, false, "Approval requested", actor);
            }
        }
        return views.approval(approvals.findApproval(approvalId).orElseThrow());
    }

    private ApprovalResponse decideLocked(UUID id, ApprovalDecision decision, int version, String reason, AuthenticatedUser actor) {
        LoanApproval current = require(id);
        ApplicationRecord application = applications.lock(current.applicationId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        assertStaff(actor, application.branchId());
        current = approvals.lockApproval(id).orElseThrow(() -> new ResourceNotFoundException("Loan approval"));
        ApprovalStage originalStage = current.stage();
        current = prepare(application, current, latestResult(application.id()), actor.getId());
        if (!current.status().open()) {
            throw closed(current);
        }
        if (current.version() != version || current.stage() != originalStage) {
            throw new ConflictException("This approval was updated by another decision");
        }
        CreditAssessmentRecord assessment = assessments.find(current.creditAssessmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Credit assessment"));
        ApprovalRules.assertChecker(actor.getId(), actor.getUsername(), current.requestedBy(), application.createdBy(), assessment.recommendedBy());
        ApprovalLevel level = currentLevel(current);
        ApprovalRules.assertAuthority(
                actor.roles(),
                level.roleCode(),
                actor.branchIds(),
                actor.unrestrictedBranches(),
                application.branchId(),
                approvals.assignee(current.id()).orElse(null),
                actor.getId(),
                current.amount(),
                approvals.delegatedLimit(actor.getId(), current.id(), current.stage(), level.roleCode(), application.branchId(), clock.instant()));
        Instant now = clock.instant();
        return switch (decision) {
            case APPROVE -> views.approval(approveLocked(current, application, level, reason, actor, now));
            case REJECT -> views.approval(close(current, application, ApprovalStatus.REJECTED, "REJECT", ApprovalDecision.REJECT,
                    ApplicationStatus.REJECTED, false, reason, actor, now));
            case RETURN -> views.approval(close(current, application, ApprovalStatus.RETURNED, "RETURN", ApprovalDecision.RETURN,
                    ApplicationStatus.DOCUMENTS_PENDING, true, reason, actor, now));
            case REQUEST_INFORMATION -> views.approval(requestInformationLocked(current, reason, actor, now));
            default -> throw ValidationException.field("decision", "Unsupported decision");
        };
    }

    private LoanApproval approveLocked(
            LoanApproval current,
            ApplicationRecord application,
            ApprovalLevel level,
            String reason,
            AuthenticatedUser actor,
            Instant now) {
        if (approvals.voted(current.id(), current.stage(), actor.getId())) {
            throw new ConflictException("You have already voted at this stage");
        }
        List<ApprovalLevel> required = ApprovalRules.requiredLevels(approvals.listLevels(current.matrixVersionId()), current.amount());
        int index = indexOf(required, current.stage());
        int votes = approvals.approvalsAtStage(current.id(), current.stage()) + 1;
        if (!ApprovalRules.quorumMet(votes, level.quorum())) {
            LoanApproval saved = persist(copy(current, current.stage(), current.stageSequence(), current.status(), null, null, null, null, current.expiresAt()), current.version());
            history(saved, actor.getId(), level.roleCode(), ApprovalDecision.APPROVE, reason, now);
            return approvals.findApproval(saved.id()).orElseThrow();
        }
        if (index < required.size() - 1) {
            ApprovalLevel next = required.get(index + 1);
            ApprovalStatus status = current.status() == ApprovalStatus.INFORMATION_REQUESTED ? ApprovalStatus.PENDING : current.status();
            assertTransition(current.status(), status);
            LoanApproval saved = persist(copy(current, next.stage(), next.sequence(), status, null, null, null, null, expiry(next, now)), current.version());
            approvals.closeAssignments(saved.id(), "COMPLETED");
            assign(saved, next.stage(), null, actor.getId(), null);
            history(saved, level.stage(), actor.getId(), level.roleCode(), ApprovalDecision.APPROVE, reason, now);
            return approvals.findApproval(saved.id()).orElseThrow();
        }
        assertTransition(current.status(), ApprovalStatus.APPROVED);
        LoanApproval saved = persist(copy(current, current.stage(), current.stageSequence(), ApprovalStatus.APPROVED, "APPROVE", reason, actor.getId(), now, current.expiresAt()), current.version());
        approvals.closeAssignments(saved.id(), "COMPLETED");
        history(saved, actor.getId(), level.roleCode(), ApprovalDecision.APPROVE, reason, now);
        if (saved.exceptionId() != null) {
            approvals.decideException(saved.exceptionId(), "APPROVED", actor.getId(), now);
        }
        if (application.status() != ApplicationStatus.APPROVED) {
            move(application, ApplicationStatus.APPROVED, false, reason, actor);
        }
        return approvals.findApproval(saved.id()).orElseThrow();
    }

    private LoanApproval close(
            LoanApproval current,
            ApplicationRecord application,
            ApprovalStatus status,
            String storedDecision,
            ApprovalDecision historyDecision,
            ApplicationStatus applicationStatus,
            boolean correction,
            String reason,
            AuthenticatedUser actor,
            Instant now) {
        assertTransition(current.status(), status);
        ApprovalLevel level = currentLevel(current);
        LoanApproval saved = persist(copy(current, current.stage(), current.stageSequence(), status, storedDecision, reason, actor.getId(), now, current.expiresAt()), current.version());
        approvals.closeAssignments(saved.id(), "COMPLETED");
        history(saved, actor.getId(), level.roleCode(), historyDecision, reason, now);
        if (saved.exceptionId() != null) {
            approvals.decideException(saved.exceptionId(), "REJECTED", actor.getId(), now);
        }
        move(application, applicationStatus, correction, reason, actor);
        return approvals.findApproval(saved.id()).orElseThrow();
    }

    private LoanApproval requestInformationLocked(LoanApproval current, String reason, AuthenticatedUser actor, Instant now) {
        if (current.status() == ApprovalStatus.INFORMATION_REQUESTED) {
            throw new ConflictException("Information has already been requested");
        }
        assertTransition(current.status(), ApprovalStatus.INFORMATION_REQUESTED);
        ApprovalLevel level = currentLevel(current);
        LoanApproval saved = persist(copy(current, current.stage(), current.stageSequence(), ApprovalStatus.INFORMATION_REQUESTED, null, null, null, null, current.expiresAt()), current.version());
        history(saved, actor.getId(), level.roleCode(), ApprovalDecision.REQUEST_INFORMATION, reason, now);
        return approvals.findApproval(saved.id()).orElseThrow();
    }

    private ApprovalResponse delegateLocked(UUID id, DelegateApprovalRequest request, AuthenticatedUser actor) {
        LoanApproval current = require(id);
        ApplicationRecord application = applications.lock(current.applicationId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        assertStaff(actor, application.branchId());
        current = approvals.lockApproval(id).orElseThrow(() -> new ResourceNotFoundException("Loan approval"));
        ApprovalStage originalStage = current.stage();
        current = prepare(application, current, latestResult(application.id()), actor.getId());
        if (!current.status().open() || current.version() != request.version() || current.stage() != originalStage) {
            throw current.status().open()
                    ? new ConflictException("This approval was updated by another decision")
                    : closed(current);
        }
        if (request.delegateUserId().equals(actor.getId())) {
            throw ValidationException.field("delegateUserId", "You cannot delegate an approval to yourself");
        }
        CreditAssessmentRecord assessment = assessments.find(current.creditAssessmentId())
                .orElseThrow(() -> new ResourceNotFoundException("Credit assessment"));
        ApprovalRules.assertChecker(actor.getId(), actor.getUsername(), current.requestedBy(), application.createdBy(), assessment.recommendedBy());
        ApprovalLevel level = currentLevel(current);
        Instant now = clock.instant();
        BigDecimal limit = approvals.delegatedLimit(actor.getId(), current.id(), current.stage(), level.roleCode(), application.branchId(), now);
        ApprovalRules.assertAuthority(
                actor.roles(),
                level.roleCode(),
                actor.branchIds(),
                actor.unrestrictedBranches(),
                application.branchId(),
                approvals.assignee(current.id()).orElse(null),
                actor.getId(),
                current.amount(),
                limit);
        String delegateEmail = approvals.emailOf(request.delegateUserId())
                .orElseThrow(() -> ValidationException.field("delegateUserId", "Unknown user"));
        if (!approvals.userHasRole(request.delegateUserId(), level.roleCode())) {
            throw ValidationException.field("delegateUserId", "The delegate is not authorised for this stage");
        }
        if (!approvals.userCoversBranch(request.delegateUserId(), application.branchId())) {
            throw ValidationException.field("delegateUserId", "The delegate is outside this branch");
        }
        ApprovalRules.assertChecker(request.delegateUserId(), delegateEmail, current.requestedBy(), application.createdBy(), assessment.recommendedBy());
        Instant until = current.expiresAt() == null ? now.plus(7, ChronoUnit.DAYS) : current.expiresAt();
        if (!until.isAfter(now)) {
            until = now.plus(1, ChronoUnit.HOURS);
        }
        approvals.insertDelegation(new ApprovalDelegation(
                UUID.randomUUID(),
                request.delegateUserId(),
                current.id(),
                level.roleCode(),
                current.stage(),
                application.branchId(),
                limit,
                "NGN",
                now.minusSeconds(1),
                until,
                true,
                request.reason().trim(),
                actor.getId(),
                now));
        approvals.closeAssignments(current.id(), "DELEGATED");
        LoanApproval saved = persist(copy(current, current.stage(), current.stageSequence(), current.status(), null, null, null, null, current.expiresAt()), current.version());
        assign(saved, current.stage(), request.delegateUserId(), actor.getId(), limit);
        history(saved, actor.getId(), level.roleCode(), ApprovalDecision.DELEGATE, request.reason().trim(), now);
        return views.approval(approvals.findApproval(saved.id()).orElseThrow());
    }

    private List<ApprovalResponse> queueLocked(AuthenticatedUser actor) {
        List<LoanApproval> candidates = new ArrayList<>(approvals.queue(
                actor.getId(), actor.roles(), actor.unrestrictedBranches(), actor.branchIds()));
        candidates.sort(Comparator.comparing(LoanApproval::applicationId));
        List<ApprovalResponse> visible = new ArrayList<>();
        for (LoanApproval candidate : candidates) {
            ApplicationRecord application = applications.lock(candidate.applicationId()).orElse(null);
            if (application == null) {
                continue;
            }
            LoanApproval locked = approvals.lockApproval(candidate.id()).orElse(null);
            if (locked == null || !locked.status().open()) {
                continue;
            }
            LoanApproval prepared = prepare(application, locked, latestResult(application.id()), actor.getId());
            if (prepared.status().open() && canAct(actor, prepared, application)) {
                visible.add(views.approval(prepared));
            }
        }
        return visible;
    }

    private LoanApproval prepareForActor(UUID id, AuthenticatedUser actor) {
        LoanApproval found = require(id);
        ApplicationRecord application = applications.lock(found.applicationId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        assertStaff(actor, application.branchId());
        LoanApproval locked = approvals.lockApproval(id).orElseThrow(() -> new ResourceNotFoundException("Loan approval"));
        return prepare(application, locked, latestResult(application.id()), actor.getId());
    }

    private LoanApproval prepare(ApplicationRecord application, LoanApproval approval, CreditResultRecord latest, UUID actorId) {
        if (ApprovalRules.stale(
                approval.applicationRevision(),
                application.revisionNumber(),
                approval.amount(),
                application.amount(),
                approval.creditResultId(),
                latest == null ? null : latest.id())) {
            supersede(approval, actorId);
            return approvals.findApproval(approval.id()).orElseThrow();
        }
        if (approval.status().open() && approval.expiresAt() != null && !clock.instant().isBefore(approval.expiresAt())) {
            expireOrEscalate(approval, actorId);
            return approvals.findApproval(approval.id()).orElseThrow();
        }
        return approval;
    }

    private void reconcile(ApplicationRecord application, CreditResultRecord latest, UUID actorId) {
        for (LoanApproval approval : approvals.lockLive(application.id())) {
            if (ApprovalRules.stale(
                    approval.applicationRevision(),
                    application.revisionNumber(),
                    approval.amount(),
                    application.amount(),
                    approval.creditResultId(),
                    latest == null ? null : latest.id())) {
                supersede(approval, actorId);
            }
        }
    }

    private void supersede(LoanApproval approval, UUID actorId) {
        assertTransition(approval.status(), ApprovalStatus.SUPERSEDED);
        LoanApproval saved = persist(copy(
                approval,
                approval.stage(),
                approval.stageSequence(),
                ApprovalStatus.SUPERSEDED,
                approval.decision(),
                approval.decisionReason(),
                approval.decidedBy(),
                approval.decidedAt(),
                approval.expiresAt()), approval.version());
        approvals.closeAssignments(saved.id(), "CANCELLED");
        history(saved, actorId, "SYSTEM", ApprovalDecision.SUPERSEDE, "The application or credit assessment changed", clock.instant());
        ApplicationRecord fresh = applications.find(approval.applicationId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        if (fresh.status() == ApplicationStatus.APPROVED) {
            move(fresh, ApplicationStatus.PENDING_APPROVAL, false, "Approval withdrawn because the application changed", actorId, "system");
        }
    }

    private void expireOrEscalate(LoanApproval approval, UUID actorId) {
        List<ApprovalLevel> required = ApprovalRules.requiredLevels(approvals.listLevels(approval.matrixVersionId()), approval.amount());
        int index = indexOf(required, approval.stage());
        ApprovalLevel level = required.get(index);
        Instant now = clock.instant();
        if (level.escalateOnExpiry() && index < required.size() - 1) {
            ApprovalLevel next = required.get(index + 1);
            ApprovalStatus status = approval.status() == ApprovalStatus.INFORMATION_REQUESTED ? ApprovalStatus.PENDING : approval.status();
            assertTransition(approval.status(), status);
            LoanApproval saved = persist(copy(approval, next.stage(), next.sequence(), status, null, null, null, null, expiry(next, now)), approval.version());
            approvals.closeAssignments(saved.id(), "EXPIRED");
            assign(saved, next.stage(), null, null, null);
            history(saved, level.stage(), null, "SYSTEM", ApprovalDecision.ESCALATE, "Approval period elapsed", now);
            return;
        }
        assertTransition(approval.status(), ApprovalStatus.EXPIRED);
        LoanApproval saved = persist(copy(approval, approval.stage(), approval.stageSequence(), ApprovalStatus.EXPIRED, "EXPIRE", "Approval period elapsed", null, now, approval.expiresAt()), approval.version());
        approvals.closeAssignments(saved.id(), "EXPIRED");
        history(saved, actorId, "SYSTEM", ApprovalDecision.EXPIRE, "Approval period elapsed", now);
    }

    private boolean canAct(AuthenticatedUser actor, LoanApproval approval, ApplicationRecord application) {
        if (approval.requestedBy() != null && approval.requestedBy().equals(actor.getId())) {
            return false;
        }
        if (application.createdBy() != null && application.createdBy().equalsIgnoreCase(actor.getUsername())) {
            return false;
        }
        CreditAssessmentRecord assessment = assessments.find(approval.creditAssessmentId()).orElse(null);
        if (assessment != null && actor.getId().equals(assessment.recommendedBy())) {
            return false;
        }
        UUID assignee = approvals.assignee(approval.id()).orElse(null);
        if (assignee != null && !assignee.equals(actor.getId())) {
            return false;
        }
        ApprovalLevel level = currentLevel(approval);
        if (!actor.roles().contains(level.roleCode())) {
            return false;
        }
        if (!actor.unrestrictedBranches() && (application.branchId() == null || !actor.branchIds().contains(application.branchId()))) {
            return false;
        }
        BigDecimal limit = approvals.delegatedLimit(actor.getId(), approval.id(), approval.stage(), level.roleCode(), application.branchId(), clock.instant());
        return limit != null && approval.amount().compareTo(limit) <= 0;
    }

    private ApplicationRecord move(ApplicationRecord current, ApplicationStatus target, boolean correction, String reason, AuthenticatedUser actor) {
        return move(current, target, correction, reason, actor.getId(), actor.getUsername());
    }

    private ApplicationRecord move(ApplicationRecord current, ApplicationStatus target, boolean correction, String reason, UUID actorId, String updatedBy) {
        ApplicationWorkflow.assertTransition(current.status(), target);
        String decision = ApplicationWorkflow.reasonRequired(target) ? reason : current.decisionReason();
        ApplicationRecord next = new ApplicationRecord(
                current.id(),
                current.reference(),
                current.customerId(),
                current.productId(),
                current.productVersionId(),
                current.branchId(),
                current.segment(),
                target,
                current.revisionNumber(),
                correction,
                current.amount(),
                current.currencyCode(),
                current.purpose(),
                current.tenure(),
                current.tenureUnit(),
                current.repaymentFrequency(),
                current.disbursementAccountId(),
                current.channel(),
                current.submittedAt(),
                current.withdrawnAt(),
                decision,
                current.createdAt(),
                current.updatedAt(),
                current.createdBy(),
                updatedBy);
        applications.update(next);
        applications.insertHistory(UUID.randomUUID(), current.id(), current.status(), target, reason, actorId);
        applications.insertEvent(UUID.randomUUID(), current.id(), "STATUS_CHANGED", json(Map.of(
                "from", current.status().name(),
                "to", target.name())), actorId);
        return next;
    }

    private LoanApproval persist(LoanApproval next, int expectedVersion) {
        if (!approvals.save(next, expectedVersion)) {
            throw new ConflictException("This approval was updated by another decision");
        }
        return approvals.findApproval(next.id()).orElseThrow();
    }

    private void assign(LoanApproval approval, ApprovalStage stage, UUID assigneeId, UUID assignedBy, BigDecimal delegatedLimit) {
        approvals.insertAssignment(new ApprovalAssignment(
                UUID.randomUUID(), approval.id(), stage, assigneeId, assignedBy, "ASSIGNED", delegatedLimit, clock.instant()));
    }

    private void history(LoanApproval approval, UUID actorId, String role, ApprovalDecision decision, String reason, Instant when) {
        history(approval, approval.stage(), actorId, role, decision, reason, when);
    }

    private void history(
            LoanApproval approval,
            ApprovalStage stage,
            UUID actorId,
            String role,
            ApprovalDecision decision,
            String reason,
            Instant when) {
        approvals.insertHistory(new ApprovalHistoryEntry(
                UUID.randomUUID(),
                approval.id(),
                actorId,
                role,
                stage,
                decision,
                approval.amount(),
                trim(reason),
                approval.correlationId(),
                when));
    }

    private ApprovalLevel currentLevel(LoanApproval approval) {
        return approvals.listLevels(approval.matrixVersionId()).stream()
                .filter(level -> level.stage() == approval.stage())
                .findFirst()
                .orElseThrow(() -> new ConflictException("The approval stage is no longer on this matrix version"));
    }

    private static int indexOf(List<ApprovalLevel> levels, ApprovalStage stage) {
        for (int index = 0; index < levels.size(); index++) {
            if (levels.get(index).stage() == stage) {
                return index;
            }
        }
        throw new ConflictException("The approval stage is no longer required for this amount");
    }

    private void assertTransition(ApprovalStatus from, ApprovalStatus to) {
        if (from != to && !approvals.transitionAllowed(from, to)) {
            throw new ConflictException("Cannot move an approval from " + from + " to " + to);
        }
    }

    private CreditResultRecord latestResult(UUID applicationId) {
        return assessments.findLatestForApplication(applicationId)
                .flatMap(assessment -> assessments.latestResult(assessment.id()))
                .orElse(null);
    }

    private boolean blocking(CreditResultRecord result) {
        return assessments.listReasons(result.id()).stream().anyMatch(DecisionReason::blocksApproval);
    }

    private Position position(ApplicationRecord application, String category) {
        List<PledgeFact> pledges = approvals.pledges(application.id());
        if (pledges.isEmpty()) {
            boolean securedProduct = "SECURED".equals(category);
            return new Position(securedProduct ? "PENDING" : "NONE", securedProduct ? "SECURED" : "UNSECURED");
        }
        boolean approved = pledges.stream().allMatch(pledge -> "APPROVED".equals(pledge.verificationStatus()));
        BigDecimal total = pledges.stream().map(PledgeFact::allocatedAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        boolean adequate = approved && total.compareTo(application.amount()) >= 0;
        String collateral = !approved ? "PENDING" : adequate ? "ADEQUATE" : "INADEQUATE";
        String security = adequate || "SECURED".equals(category) ? "SECURED" : "UNSECURED";
        return new Position(collateral, security);
    }

    private static boolean eligibilityClear(CustomerRecord customer) {
        return customer.status() == CustomerStatus.ACTIVE
                && customer.kycStatus() == KycStatus.VERIFIED
                && !customer.blacklisted()
                && !customer.complianceHold();
    }

    private static boolean complianceClear(CustomerRecord customer, DecisionOutcome decision) {
        return !customer.complianceHold()
                && !customer.blacklisted()
                && decision != DecisionOutcome.COMPLIANCE_HOLD;
    }

    private static Instant expiry(ApprovalLevel level, Instant now) {
        if (level.expiryHours() == 0) {
            return now.minusSeconds(1);
        }
        return now.plus(level.expiryHours(), ChronoUnit.HOURS);
    }

    private void assertStaff(AuthenticatedUser actor, UUID branchId) {
        if (actor.getPrincipalType() != PrincipalType.STAFF) {
            throw new ResourceNotFoundException("Loan approval");
        }
        if (!actor.unrestrictedBranches() && (branchId == null || !actor.branchIds().contains(branchId))) {
            throw new ForbiddenException("You cannot access an approval outside your branches");
        }
    }

    private LoanApproval require(UUID id) {
        return approvals.findApproval(id).orElseThrow(() -> new ResourceNotFoundException("Loan approval"));
    }

    private static ConflictException closed(LoanApproval approval) {
        if (approval.status() == ApprovalStatus.SUPERSEDED) {
            return new ConflictException("The application or credit assessment changed. Request approval again");
        }
        if (approval.status() == ApprovalStatus.EXPIRED) {
            return new ConflictException("This approval has expired");
        }
        return new ConflictException("This approval is already " + approval.status().name().toLowerCase(Locale.ROOT));
    }

    private static String exception(String exceptionType, String reason, boolean strict) {
        if (exceptionType == null || exceptionType.isBlank()) {
            return null;
        }
        if (!strict) {
            return null;
        }
        String type = exceptionType.trim().toUpperCase(Locale.ROOT);
        if (!EXCEPTION_TYPE.matcher(type).matches()) {
            throw ValidationException.field("exceptionType", "exceptionType must be uppercase letters, digits, or underscores");
        }
        if (reason == null || reason.isBlank()) {
            throw ValidationException.field("reason", "reason is required");
        }
        return type;
    }

    private static String correlation() {
        String current = CorrelationIds.current();
        return CorrelationIds.isValid(current) ? current : UUID.randomUUID().toString();
    }

    private static String trim(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String trimmed = reason.trim();
        return trimmed.length() <= 500 ? trimmed : trimmed.substring(0, 500);
    }

    private String json(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Approval event could not be written", ex);
        }
    }

    private static LoanApproval copy(
            LoanApproval current,
            ApprovalStage stage,
            int sequence,
            ApprovalStatus status,
            String decision,
            String reason,
            UUID decidedBy,
            Instant decidedAt,
            Instant expiresAt) {
        return new LoanApproval(
                current.id(),
                current.reference(),
                current.applicationId(),
                current.applicationRevision(),
                current.creditAssessmentId(),
                current.creditResultId(),
                current.matrixId(),
                current.matrixVersionId(),
                current.exceptionId(),
                current.amount(),
                current.currencyCode(),
                stage,
                sequence,
                status,
                decision,
                reason,
                decidedBy,
                decidedAt,
                expiresAt,
                current.automated(),
                current.requestedBy(),
                current.correlationId(),
                current.version(),
                current.createdAt(),
                current.updatedAt());
    }

    private record Position(String collateralStatus, String securityClassification) {
    }
}
