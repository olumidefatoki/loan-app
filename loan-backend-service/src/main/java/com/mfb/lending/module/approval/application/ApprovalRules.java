package com.mfb.lending.module.approval.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.approval.domain.ApprovalLevel;
import com.mfb.lending.module.approval.domain.MatrixVersion;

public final class ApprovalRules {

    private ApprovalRules() {
    }

    public static List<ApprovalLevel> requiredLevels(List<ApprovalLevel> levels, BigDecimal amount) {
        List<ApprovalLevel> ordered = levels.stream()
                .sorted(Comparator.comparingInt(ApprovalLevel::sequence))
                .toList();
        int cover = -1;
        for (int index = 0; index < ordered.size(); index++) {
            ApprovalLevel level = ordered.get(index);
            boolean aboveFloor = level.minAmount() == null || amount.compareTo(level.minAmount()) >= 0;
            boolean withinCeiling = level.maxAmount() == null || amount.compareTo(level.maxAmount()) <= 0;
            if (aboveFloor && withinCeiling) {
                cover = index;
                break;
            }
        }
        if (cover < 0) {
            throw ValidationException.field("amount", "No approval level covers this amount");
        }
        List<ApprovalLevel> required = new ArrayList<>();
        for (int index = 0; index <= cover; index++) {
            ApprovalLevel level = ordered.get(index);
            if (level.minAmount() == null || amount.compareTo(level.minAmount()) >= 0) {
                required.add(level);
            }
        }
        return List.copyOf(required);
    }

    public static MatrixVersion select(List<MatrixVersion> versions, MatchFacts facts) {
        return versions.stream()
                .filter(version -> matches(version, facts))
                .max(Comparator.comparingInt(ApprovalRules::specificity).thenComparing(MatrixVersion::createdAt))
                .orElse(null);
    }

    public static boolean matches(MatrixVersion version, MatchFacts facts) {
        if (facts.amount().compareTo(version.amountMin()) < 0) {
            return false;
        }
        if (version.amountMax() != null && facts.amount().compareTo(version.amountMax()) > 0) {
            return false;
        }
        if (!same(version.productId(), facts.productId())) {
            return false;
        }
        if (!same(version.customerSegment(), facts.segment())) {
            return false;
        }
        if (!same(version.branchId(), facts.branchId())) {
            return false;
        }
        if (!same(version.riskRating(), facts.riskRating())) {
            return false;
        }
        if (!same(version.collateralStatus(), facts.collateralStatus())) {
            return false;
        }
        if (!same(version.securityClassification(), facts.securityClassification())) {
            return false;
        }
        return version.exceptionType() == null
                || version.exceptionType().equals(facts.exceptionType());
    }

    public static int specificity(MatrixVersion version) {
        int score = 0;
        if (version.productId() != null) {
            score += 16;
        }
        if (version.customerSegment() != null) {
            score += 8;
        }
        if (version.branchId() != null) {
            score += 4;
        }
        if (version.riskRating() != null) {
            score += 2;
        }
        if (version.collateralStatus() != null) {
            score += 2;
        }
        if (version.securityClassification() != null) {
            score += 2;
        }
        if (version.exceptionType() != null) {
            score += 32;
        }
        return score;
    }

    public static boolean automated(AutomationGate gate, MatrixVersion version, List<ApprovalLevel> required) {
        if (!gate.productAllowsAutomation() || !version.automatedAllowed() || required.isEmpty()) {
            return false;
        }
        if (!gate.eligibilityClear() || !gate.creditPermitsApproval() || !gate.complianceClear() || gate.blockingFindings()) {
            return false;
        }
        if (gate.exceptionType() != null) {
            return false;
        }
        return required.stream().allMatch(level -> level.automated() && !level.manualRequired());
    }

    public static void assertManualPermitted(String decision, boolean complianceClear, boolean blockingFindings, String exceptionType) {
        if (!complianceClear) {
            throw new ConflictException("Compliance checks are not clear");
        }
        if ("COMPLIANCE_HOLD".equals(decision)) {
            throw new ConflictException("A compliance hold cannot be approved");
        }
        if ("PENDING_DATA".equals(decision) || decision == null) {
            throw new ConflictException("The credit assessment is not ready for approval");
        }
        if ("AUTO_REJECT".equals(decision) && exceptionType == null) {
            throw new ConflictException("A rejected assessment needs an exception type before approval");
        }
        if (blockingFindings && exceptionType == null) {
            throw new ConflictException("A blocking credit finding needs an exception type before approval");
        }
        if (!"AUTO_APPROVE".equals(decision) && !"REFER".equals(decision) && !"AUTO_REJECT".equals(decision)) {
            throw new ConflictException("The credit assessment does not permit approval");
        }
    }

    public static void assertChecker(UUID actorId, String actorUsername, UUID requestedBy, String applicationCreatedBy, UUID recommendedBy) {
        if (requestedBy != null && requestedBy.equals(actorId)) {
            throw new ForbiddenException("You cannot decide an approval request you created");
        }
        if (applicationCreatedBy != null && applicationCreatedBy.equalsIgnoreCase(actorUsername)) {
            throw new ForbiddenException("You cannot decide an application you originated");
        }
        if (recommendedBy != null && recommendedBy.equals(actorId)) {
            throw new ForbiddenException("You cannot decide a credit recommendation you made");
        }
    }

    public static void assertAuthority(
            Set<String> roles,
            String stageRole,
            Set<UUID> branches,
            boolean unrestrictedBranches,
            UUID branchId,
            UUID assigneeId,
            UUID actorId,
            BigDecimal amount,
            BigDecimal delegatedLimit) {
        if (!unrestrictedBranches && (branchId == null || !branches.contains(branchId))) {
            throw new ForbiddenException("You cannot approve outside your assigned branches");
        }
        if (assigneeId != null && !assigneeId.equals(actorId)) {
            throw new ForbiddenException("This approval is assigned to another user");
        }
        if (!roles.contains(stageRole)) {
            throw new ForbiddenException("You are not authorised for this approval stage");
        }
        if (delegatedLimit == null || amount.compareTo(delegatedLimit) > 0) {
            throw new ForbiddenException("The amount exceeds your delegated approval limit");
        }
    }

    public static boolean stale(int recordedRevision, int currentRevision, BigDecimal recordedAmount, BigDecimal currentAmount, UUID recordedResult, UUID currentResult) {
        if (recordedRevision != currentRevision || recordedAmount.compareTo(currentAmount) != 0) {
            return true;
        }
        return currentResult != null && !currentResult.equals(recordedResult);
    }

    public static boolean quorumMet(int votes, int quorum) {
        return votes >= quorum;
    }

    private static boolean same(Object configured, Object actual) {
        return configured == null || configured.equals(actual);
    }

    public record MatchFacts(
            BigDecimal amount,
            UUID productId,
            String segment,
            UUID branchId,
            String riskRating,
            String collateralStatus,
            String securityClassification,
            String exceptionType
    ) {
    }

    public record AutomationGate(
            boolean productAllowsAutomation,
            boolean eligibilityClear,
            boolean creditPermitsApproval,
            boolean complianceClear,
            boolean blockingFindings,
            String exceptionType
    ) {
    }
}
