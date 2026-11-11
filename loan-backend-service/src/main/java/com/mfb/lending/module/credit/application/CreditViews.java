package com.mfb.lending.module.credit.application;

import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mfb.lending.module.credit.domain.AssessmentFacts;
import com.mfb.lending.module.credit.domain.CalculationSet;
import com.mfb.lending.module.credit.domain.CreditAssessmentRecord;
import com.mfb.lending.module.credit.domain.CreditBureauCheck;
import com.mfb.lending.module.credit.domain.CreditComment;
import com.mfb.lending.module.credit.domain.CreditPolicyException;
import com.mfb.lending.module.credit.domain.CreditQueueItem;
import com.mfb.lending.module.credit.domain.CreditResultRecord;
import com.mfb.lending.module.credit.domain.CreditRule;
import com.mfb.lending.module.credit.domain.DecisionReason;
import com.mfb.lending.module.credit.domain.ScoreComponentResult;
import com.mfb.lending.module.credit.domain.ScoringModelRecord;
import com.mfb.lending.module.credit.domain.ScoringVersionRecord;
import com.mfb.lending.module.credit.dto.BureauCheckResponse;
import com.mfb.lending.module.credit.dto.CommentResponse;
import com.mfb.lending.module.credit.dto.CreditAssessmentResponse;
import com.mfb.lending.module.credit.dto.CreditQueueItemResponse;
import com.mfb.lending.module.credit.dto.CreditReportResponse;
import com.mfb.lending.module.credit.dto.CreditResultSummary;
import com.mfb.lending.module.credit.dto.CreditRuleResponse;
import com.mfb.lending.module.credit.dto.PolicyExceptionResponse;
import com.mfb.lending.module.credit.dto.ScoringModelResponse;
import com.mfb.lending.module.credit.dto.ScoringVersionResponse;
import org.springframework.stereotype.Component;

@Component
public class CreditViews {

    private final ObjectMapper objectMapper;

    public CreditViews(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public CreditAssessmentResponse assessment(
            CreditAssessmentRecord record,
            List<DecisionReason> reasons) {
        return new CreditAssessmentResponse(
                record.id(),
                record.reference(),
                record.applicationId(),
                record.customerId(),
                record.productId(),
                record.productVersionId(),
                record.segment(),
                record.status().name(),
                record.decision() == null ? null : record.decision().name(),
                record.score(),
                record.riskCategory(),
                record.scoringModelId(),
                record.scoringModelVersionId(),
                record.policyVersion(),
                read(record.inputsJson(), AssessmentFacts.class),
                record.calculationsJson() == null ? null : read(record.calculationsJson(), CalculationSet.class),
                reasons,
                record.analystRecommendation() == null ? null : record.analystRecommendation().name(),
                record.analystJustification(),
                record.assignedTo(),
                record.revision(),
                record.createdAt(),
                record.updatedAt());
    }

    public CreditReportResponse report(
            CreditAssessmentResponse assessment,
            List<ScoreComponentResult> components,
            List<CreditResultRecord> results,
            List<CreditBureauCheck> bureauChecks,
            List<CreditComment> comments,
            List<CreditPolicyException> exceptions) {
        return new CreditReportResponse(
                assessment,
                components,
                results.stream().map(this::result).toList(),
                bureauChecks.stream().map(this::bureau).toList(),
                comments.stream().map(this::comment).toList(),
                exceptions.stream().map(this::exception).toList());
    }

    public CreditQueueItemResponse queueItem(CreditQueueItem item) {
        return new CreditQueueItemResponse(
                item.id(),
                item.reference(),
                item.applicationId(),
                item.customerId(),
                item.customerName(),
                item.segment(),
                item.status().name(),
                item.decision() == null ? null : item.decision().name(),
                item.score(),
                item.riskCategory(),
                item.assignedTo(),
                item.updatedAt());
    }

    public ScoringModelResponse model(ScoringModelRecord model, ScoringVersionRecord current, List<ScoringVersionRecord> versions) {
        return new ScoringModelResponse(
                model.id(),
                model.code(),
                model.name(),
                model.segment().name(),
                model.productId(),
                model.status().name(),
                current == null ? null : version(current),
                versions == null ? List.of() : versions.stream().map(this::version).toList());
    }

    public ScoringVersionResponse version(ScoringVersionRecord version) {
        return new ScoringVersionResponse(
                version.id(),
                version.versionNumber(),
                version.status().name(),
                version.weights(),
                version.riskCategories(),
                version.thresholds(),
                version.policyVersion(),
                version.changeReason(),
                version.activatedAt());
    }

    public CreditRuleResponse rule(CreditRule rule) {
        return new CreditRuleResponse(
                rule.id(),
                rule.code(),
                rule.name(),
                rule.description(),
                rule.segment(),
                rule.productId(),
                rule.ruleType().name(),
                rule.mandatory(),
                rule.exceptionAllowed(),
                rule.threshold(),
                rule.failureOutcome().name(),
                rule.policyVersion(),
                rule.active());
    }

    public BureauCheckResponse bureau(CreditBureauCheck check) {
        return new BureauCheckResponse(
                check.id(),
                check.providerCode(),
                check.status().name(),
                check.identificationMatched(),
                check.bureauScore(),
                check.existingExposure(),
                check.failureReason(),
                check.requestedAt(),
                check.completedAt());
    }

    private CreditResultSummary result(CreditResultRecord result) {
        return new CreditResultSummary(
                result.id(),
                result.modelId(),
                result.modelVersionId(),
                result.policyVersion(),
                result.score(),
                result.riskCategory(),
                result.decision().name(),
                result.explanation(),
                result.evaluatedAt());
    }

    private CommentResponse comment(CreditComment comment) {
        return new CommentResponse(comment.id(), comment.authorId(), comment.body(), comment.createdAt());
    }

    private PolicyExceptionResponse exception(CreditPolicyException exception) {
        return new PolicyExceptionResponse(
                exception.id(),
                exception.ruleCode(),
                exception.status(),
                exception.justification(),
                exception.requestedBy(),
                exception.requestedAt(),
                exception.decidedBy(),
                exception.decidedAt(),
                exception.decisionNote());
    }

    private <T> T read(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Credit assessment payload could not be read", ex);
        }
    }
}
