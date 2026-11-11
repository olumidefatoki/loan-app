package com.mfb.lending.module.credit.application;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.credit.domain.CreditRule;
import com.mfb.lending.module.credit.domain.DecisionOutcome;
import com.mfb.lending.module.credit.domain.DecisionThresholds;
import com.mfb.lending.module.credit.domain.ModelSegment;
import com.mfb.lending.module.credit.domain.ModelStatus;
import com.mfb.lending.module.credit.domain.RiskBand;
import com.mfb.lending.module.credit.domain.RuleType;
import com.mfb.lending.module.credit.domain.ScoringModelRecord;
import com.mfb.lending.module.credit.domain.ScoringVersionRecord;
import com.mfb.lending.module.credit.domain.ScoringWeights;
import com.mfb.lending.module.credit.dto.CreateScoringModelRequest;
import com.mfb.lending.module.credit.dto.CreditRuleResponse;
import com.mfb.lending.module.credit.dto.SaveCreditRuleRequest;
import com.mfb.lending.module.credit.dto.ScoringModelResponse;
import com.mfb.lending.module.credit.dto.ScoringSpecRequest;
import com.mfb.lending.module.credit.persistence.CreditPolicyRepository;
import com.mfb.lending.module.loanproduct.persistence.LoanProductRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CreditPolicyService {

    private final CreditPolicyRepository policies;
    private final LoanProductRepository products;
    private final CreditViews views;
    private final AuditService audit;
    private final java.time.Clock clock;
    private final TransactionTemplate transactions;

    public CreditPolicyService(
            CreditPolicyRepository policies,
            LoanProductRepository products,
            CreditViews views,
            AuditService audit,
            java.time.Clock clock,
            TransactionTemplate transactions) {
        this.policies = policies;
        this.products = products;
        this.views = views;
        this.audit = audit;
        this.clock = clock;
        this.transactions = transactions;
    }

    public ScoringModelResponse createModel(CreateScoringModelRequest request, AuthenticatedUser actor, ClientContext client) {
        ScoringModelResponse response = transactions.execute(status -> createModelLocked(request, actor));
        audit.record("CREDIT_MODEL_CREATED", "credit_scoring_model", response.id().toString(), actor.getUsername(), client, Map.of(
                "code", response.code()));
        return response;
    }

    public List<ScoringModelResponse> listModels() {
        return policies.listModels().stream()
                .map(model -> views.model(model, current(model), null))
                .toList();
    }

    public ScoringModelResponse getModel(UUID id) {
        ScoringModelRecord model = requireModel(id);
        return views.model(model, current(model), policies.listVersions(id));
    }

    public ScoringModelResponse updateModel(UUID id, ScoringSpecRequest request, AuthenticatedUser actor, ClientContext client) {
        ScoringModelResponse response = transactions.execute(status -> updateModelLocked(id, request, actor));
        audit.record("CREDIT_MODEL_UPDATED", "credit_scoring_model", id.toString(), actor.getUsername(), client, Map.of(
                "policyVersion", request.policyVersion()));
        return response;
    }

    public ScoringModelResponse addVersion(UUID id, ScoringSpecRequest request, AuthenticatedUser actor, ClientContext client) {
        ScoringModelResponse response = transactions.execute(status -> addVersionLocked(id, request, actor));
        audit.record("CREDIT_MODEL_VERSION_CREATED", "credit_scoring_model", id.toString(), actor.getUsername(), client, Map.of(
                "policyVersion", request.policyVersion()));
        return response;
    }

    public ScoringModelResponse activate(UUID id, AuthenticatedUser actor, ClientContext client) {
        ScoringModelResponse response = transactions.execute(status -> activateLocked(id, actor));
        audit.record("CREDIT_MODEL_ACTIVATED", "credit_scoring_model", id.toString(), actor.getUsername(), client, Map.of(
                "versionId", response.currentVersion().id().toString(),
                "policyVersion", response.currentVersion().policyVersion()));
        return response;
    }

    public void deleteModel(UUID id, AuthenticatedUser actor, ClientContext client) {
        transactions.executeWithoutResult(status -> deleteModelLocked(id));
        audit.record("CREDIT_MODEL_DELETED", "credit_scoring_model", id.toString(), actor.getUsername(), client, Map.of());
    }

    public CreditRuleResponse createRule(SaveCreditRuleRequest request, AuthenticatedUser actor, ClientContext client) {
        CreditRuleResponse response = transactions.execute(status -> {
            CreditRule rule = rule(UUID.randomUUID(), null, request);
            try {
                policies.insertRule(rule, actor.getUsername());
            } catch (DuplicateKeyException ex) {
                throw new ConflictException("A credit rule with this code already exists");
            }
            return views.rule(policies.findRule(rule.id()).orElseThrow());
        });
        audit.record("CREDIT_RULE_CREATED", "credit_assessment_rule", response.id().toString(), actor.getUsername(), client, Map.of(
                "code", response.code()));
        return response;
    }

    public List<CreditRuleResponse> listRules() {
        return policies.listRules().stream().map(views::rule).toList();
    }

    public CreditRuleResponse getRule(UUID id) {
        return views.rule(requireRule(id));
    }

    public CreditRuleResponse updateRule(UUID id, SaveCreditRuleRequest request, AuthenticatedUser actor, ClientContext client) {
        CreditRuleResponse response = transactions.execute(status -> {
            CreditRule existing = requireRule(id);
            if (!existing.code().equals(normalizeCode(request.code()))) {
                throw ValidationException.field("code", "Rule code cannot change");
            }
            CreditRule rule = rule(id, existing, request);
            policies.updateRule(rule, actor.getUsername());
            return views.rule(requireRule(id));
        });
        audit.record("CREDIT_RULE_UPDATED", "credit_assessment_rule", id.toString(), actor.getUsername(), client, Map.of(
                "code", response.code()));
        return response;
    }

    public void deleteRule(UUID id, AuthenticatedUser actor, ClientContext client) {
        transactions.executeWithoutResult(status -> {
            CreditRule rule = requireRule(id);
            if (policies.exceptionCount(rule.code()) > 0) {
                throw new ConflictException("The rule is referenced by a policy exception");
            }
            policies.deleteRule(id);
        });
        audit.record("CREDIT_RULE_DELETED", "credit_assessment_rule", id.toString(), actor.getUsername(), client, Map.of());
    }

    private ScoringModelResponse createModelLocked(CreateScoringModelRequest request, AuthenticatedUser actor) {
        String code = normalizeCode(request.code());
        ModelSegment segment = ModelSegment.parse(request.segment());
        validateProduct(segment, request.productId());
        ScoringWeights weights = new ScoringWeights(request.components(), request.categoricalScores());
        ScoringSpecValidator.validate(weights, request.riskCategories(), request.decisionThresholds(), request.policyVersion());
        if (policies.findModelByCode(code).isPresent()) {
            throw new ConflictException("A scoring model with this code already exists");
        }
        UUID modelId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        policies.insertModel(new ScoringModelRecord(
                modelId, code, request.name().trim(), segment, segment == ModelSegment.PRODUCT ? request.productId() : null,
                ModelStatus.DRAFT, null, null, null, actor.getUsername(), actor.getUsername()));
        policies.insertVersion(version(versionId, modelId, 1, ModelStatus.DRAFT, weights, request.riskCategories(),
                request.decisionThresholds(), request.policyVersion(), request.changeReason(), null, actor.getUsername()));
        policies.updateModelHead(modelId, request.name().trim(), ModelStatus.DRAFT, versionId, actor.getUsername());
        return getModel(modelId);
    }

    private ScoringModelResponse updateModelLocked(UUID id, ScoringSpecRequest request, AuthenticatedUser actor) {
        ScoringModelRecord model = requireModel(id);
        ScoringVersionRecord draft = policies.findDraft(id)
                .orElseThrow(() -> new ConflictException("Create a new version to change an active scoring model"));
        ScoringWeights weights = new ScoringWeights(request.components(), request.categoricalScores());
        ScoringSpecValidator.validate(weights, request.riskCategories(), request.decisionThresholds(), request.policyVersion());
        ScoringVersionRecord updated = version(
                draft.id(), model.id(), draft.versionNumber(), ModelStatus.DRAFT, weights, request.riskCategories(),
                request.decisionThresholds(), request.policyVersion(), request.changeReason(), null, draft.createdBy());
        if (policies.updateDraft(updated) != 1) {
            throw new ConflictException("The scoring version is no longer a draft");
        }
        return getModel(id);
    }

    private ScoringModelResponse addVersionLocked(UUID id, ScoringSpecRequest request, AuthenticatedUser actor) {
        requireModel(id);
        if (policies.findDraft(id).isPresent()) {
            throw new ConflictException("Activate or delete the current draft before adding another version");
        }
        ScoringWeights weights = new ScoringWeights(request.components(), request.categoricalScores());
        ScoringSpecValidator.validate(weights, request.riskCategories(), request.decisionThresholds(), request.policyVersion());
        policies.insertVersion(version(
                UUID.randomUUID(),
                id,
                policies.nextVersionNumber(id),
                ModelStatus.DRAFT,
                weights,
                request.riskCategories(),
                request.decisionThresholds(),
                request.policyVersion(),
                request.changeReason(),
                null,
                actor.getUsername()));
        return getModel(id);
    }

    private ScoringModelResponse activateLocked(UUID id, AuthenticatedUser actor) {
        ScoringModelRecord model = requireModel(id);
        ScoringVersionRecord draft = policies.findDraft(id)
                .orElseThrow(() -> new ConflictException("There is no draft scoring version to activate"));
        ScoringSpecValidator.validate(draft.weights(), draft.riskCategories(), draft.thresholds(), draft.policyVersion());
        policies.retireActive(id);
        if (policies.activate(draft.id(), clock.instant()) != 1) {
            throw new ConflictException("The scoring version is no longer a draft");
        }
        policies.updateModelHead(model.id(), model.name(), ModelStatus.ACTIVE, draft.id(), actor.getUsername());
        return getModel(id);
    }

    private void deleteModelLocked(UUID id) {
        ScoringModelRecord model = requireModel(id);
        if (model.status() != ModelStatus.DRAFT) {
            throw new ConflictException("Only a draft scoring model can be deleted");
        }
        if (policies.assessmentCount(id) > 0) {
            throw new ConflictException("The scoring model is already used by an assessment");
        }
        policies.clearCurrentVersion(id);
        policies.deleteDraftVersions(id);
        if (policies.deleteDraftModel(id) != 1) {
            throw new ConflictException("Only a draft scoring model can be deleted");
        }
    }

    private ScoringVersionRecord current(ScoringModelRecord model) {
        if (model.currentVersionId() == null) {
            return null;
        }
        return policies.findVersion(model.currentVersionId()).orElse(null);
    }

    private ScoringModelRecord requireModel(UUID id) {
        return policies.findModel(id).orElseThrow(() -> new ResourceNotFoundException("Credit scoring model"));
    }

    private CreditRule requireRule(UUID id) {
        return policies.findRule(id).orElseThrow(() -> new ResourceNotFoundException("Credit assessment rule"));
    }

    private void validateProduct(ModelSegment segment, UUID productId) {
        if (segment == ModelSegment.PRODUCT && productId == null) {
            throw ValidationException.field("productId", "productId is required for a product scoring model");
        }
        if (segment != ModelSegment.PRODUCT && productId != null) {
            throw ValidationException.field("productId", "productId is only used by a product scoring model");
        }
        if (productId != null && products.findProduct(productId).isEmpty()) {
            throw new ResourceNotFoundException("Loan product");
        }
    }

    private CreditRule rule(UUID id, CreditRule existing, SaveCreditRuleRequest request) {
        String segment = request.segment().trim().toUpperCase(Locale.ROOT);
        if (!segment.equals("ANY") && !segment.equals("INDIVIDUAL") && !segment.equals("SME")) {
            throw ValidationException.field("segment", "segment must be ANY, INDIVIDUAL, or SME");
        }
        RuleType type;
        try {
            type = RuleType.valueOf(request.ruleType().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("ruleType", "Unknown rule type");
        }
        DecisionOutcome outcome;
        try {
            outcome = DecisionOutcome.valueOf(request.failureOutcome().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("failureOutcome", "failureOutcome must be AUTO_REJECT, COMPLIANCE_HOLD, or PENDING_DATA");
        }
        if (outcome != DecisionOutcome.AUTO_REJECT
                && outcome != DecisionOutcome.COMPLIANCE_HOLD
                && outcome != DecisionOutcome.PENDING_DATA) {
            throw ValidationException.field("failureOutcome", "failureOutcome must be AUTO_REJECT, COMPLIANCE_HOLD, or PENDING_DATA");
        }
        if (outcome == DecisionOutcome.COMPLIANCE_HOLD && request.exceptionAllowed()) {
            throw ValidationException.field("exceptionAllowed", "A compliance hold cannot be waived");
        }
        if (request.threshold() != null && request.threshold().signum() < 0) {
            throw ValidationException.field("threshold", "threshold cannot be negative");
        }
        boolean active = request.active() == null ? existing == null || existing.active() : request.active();
        return new CreditRule(
                id,
                normalizeCode(request.code()),
                request.name().trim(),
                request.description().trim(),
                segment,
                request.productId(),
                type,
                request.mandatory(),
                request.exceptionAllowed(),
                request.threshold(),
                outcome,
                request.policyVersion().trim(),
                active);
    }

    private static ScoringVersionRecord version(
            UUID id,
            UUID modelId,
            int number,
            ModelStatus status,
            ScoringWeights weights,
            List<RiskBand> risks,
            DecisionThresholds thresholds,
            String policyVersion,
            String changeReason,
            java.time.Instant activatedAt,
            String actor) {
        return new ScoringVersionRecord(
                id, modelId, number, status, weights, risks, thresholds, policyVersion.trim(),
                changeReason, activatedAt, null, actor);
    }

    private static String normalizeCode(String code) {
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_]{2,40}")) {
            throw ValidationException.field("code", "code must be 2 to 40 uppercase letters, digits, or underscores");
        }
        return normalized;
    }
}
