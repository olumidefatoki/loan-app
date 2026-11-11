package com.mfb.lending.module.approval;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.approval.application.ApprovalRules;
import com.mfb.lending.module.approval.domain.ApprovalLevel;
import com.mfb.lending.module.approval.domain.ApprovalStage;
import com.mfb.lending.module.approval.domain.MatrixStatus;
import com.mfb.lending.module.approval.domain.MatrixVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalRulesTest {

    @Test
    void requiredStagesStopAtTheLevelThatCoversTheAmount() {
        List<ApprovalLevel> levels = List.of(
                level(ApprovalStage.LOAN_OFFICER, 1, new BigDecimal("100000.00"), false, false),
                level(ApprovalStage.CREDIT_MANAGER, 2, null, true, false),
                level(ApprovalStage.CREDIT_COMMITTEE, 3, null, true, false));

        assertThat(ApprovalRules.requiredLevels(levels, new BigDecimal("50000.00")))
                .extracting(ApprovalLevel::stage)
                .containsExactly(ApprovalStage.LOAN_OFFICER);
        assertThat(ApprovalRules.requiredLevels(levels, new BigDecimal("250000.00")))
                .extracting(ApprovalLevel::stage)
                .containsExactly(ApprovalStage.LOAN_OFFICER, ApprovalStage.CREDIT_MANAGER);
    }

    @Test
    void anAmountAboveEveryCeilingIsRejected() {
        List<ApprovalLevel> levels = List.of(level(ApprovalStage.LOAN_OFFICER, 1, new BigDecimal("1000.00"), true, false));
        assertThatThrownBy(() -> ApprovalRules.requiredLevels(levels, new BigDecimal("5000.00")))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void automationRequiresEveryConfiguredGate() {
        MatrixVersion version = version(null, null, true);
        List<ApprovalLevel> required = List.of(level(ApprovalStage.LOAN_OFFICER, 1, null, false, true));
        ApprovalRules.AutomationGate open = new ApprovalRules.AutomationGate(true, true, true, true, false, null);
        assertThat(ApprovalRules.automated(open, version, required)).isTrue();
        assertThat(ApprovalRules.automated(new ApprovalRules.AutomationGate(false, true, true, true, false, null), version, required)).isFalse();
        assertThat(ApprovalRules.automated(new ApprovalRules.AutomationGate(true, false, true, true, false, null), version, required)).isFalse();
        assertThat(ApprovalRules.automated(new ApprovalRules.AutomationGate(true, true, false, true, false, null), version, required)).isFalse();
        assertThat(ApprovalRules.automated(new ApprovalRules.AutomationGate(true, true, true, false, false, null), version, required)).isFalse();
        assertThat(ApprovalRules.automated(new ApprovalRules.AutomationGate(true, true, true, true, true, null), version, required)).isFalse();
        assertThat(ApprovalRules.automated(open, version, List.of(level(ApprovalStage.CREDIT_MANAGER, 1, null, true, false)))).isFalse();
    }

    @Test
    void manualApprovalStopsOnComplianceAndNeedsAnExceptionForADecline() {
        ApprovalRules.assertManualPermitted("REFER", true, false, null);
        assertThatThrownBy(() -> ApprovalRules.assertManualPermitted("COMPLIANCE_HOLD", false, false, null))
                .isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> ApprovalRules.assertManualPermitted("AUTO_REJECT", true, false, null))
                .isInstanceOf(ConflictException.class);
        ApprovalRules.assertManualPermitted("AUTO_REJECT", true, true, "POLICY_OVERRIDE");
    }

    @Test
    void aMakerCannotCheckTheSameRequest() {
        UUID maker = UUID.randomUUID();
        assertThatThrownBy(() -> ApprovalRules.assertChecker(maker, "maker@mfb.test", maker, "other@mfb.test", null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("request you created");
        assertThatThrownBy(() -> ApprovalRules.assertChecker(UUID.randomUUID(), "maker@mfb.test", UUID.randomUUID(), "maker@mfb.test", null))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("originated");
        assertThatThrownBy(() -> ApprovalRules.assertChecker(maker, "checker@mfb.test", UUID.randomUUID(), "other@mfb.test", maker))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("recommendation");
    }

    @Test
    void authorityStopsAtTheDelegatedLimitAndTheAssignedBranch() {
        UUID actor = UUID.randomUUID();
        UUID branch = UUID.randomUUID();
        ApprovalRules.assertAuthority(Set.of("CREDIT_MANAGER"), "CREDIT_MANAGER", Set.of(branch), false, branch, null, actor,
                new BigDecimal("200000.00"), new BigDecimal("200000.00"));
        assertThatThrownBy(() -> ApprovalRules.assertAuthority(Set.of("CREDIT_MANAGER"), "CREDIT_MANAGER", Set.of(branch), false, branch, null, actor,
                new BigDecimal("200000.00"), new BigDecimal("100000.00")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("delegated approval limit");
        assertThatThrownBy(() -> ApprovalRules.assertAuthority(Set.of("LOAN_OFFICER"), "CREDIT_MANAGER", Set.of(branch), false, branch, null, actor,
                new BigDecimal("10.00"), new BigDecimal("10.00")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("stage");
        assertThatThrownBy(() -> ApprovalRules.assertAuthority(Set.of("CREDIT_MANAGER"), "CREDIT_MANAGER", Set.of(), false, branch, null, actor,
                new BigDecimal("10.00"), new BigDecimal("10.00")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("branches");
    }

    @Test
    void aMaterialChangeMakesTheRecordedApprovalStale() {
        UUID result = UUID.randomUUID();
        assertThat(ApprovalRules.stale(1, 1, new BigDecimal("10.00"), new BigDecimal("10.00"), result, result)).isFalse();
        assertThat(ApprovalRules.stale(1, 2, new BigDecimal("10.00"), new BigDecimal("10.00"), result, result)).isTrue();
        assertThat(ApprovalRules.stale(1, 1, new BigDecimal("10.00"), new BigDecimal("11.00"), result, result)).isTrue();
        assertThat(ApprovalRules.stale(1, 1, new BigDecimal("10.00"), new BigDecimal("10.00"), result, UUID.randomUUID())).isTrue();
    }

    @Test
    void theMostSpecificCoveringMatrixWins() {
        UUID product = UUID.randomUUID();
        MatrixVersion general = version(null, null, false);
        MatrixVersion productOnly = version(product, null, false);
        MatrixVersion exception = version(product, "POLICY_OVERRIDE", false);
        ApprovalRules.MatchFacts facts = new ApprovalRules.MatchFacts(
                new BigDecimal("100.00"), product, "INDIVIDUAL", UUID.randomUUID(), "LOW", "NONE", "UNSECURED", "POLICY_OVERRIDE");
        assertThat(ApprovalRules.select(List.of(general, productOnly, exception), facts).exceptionType()).isEqualTo("POLICY_OVERRIDE");
        assertThat(ApprovalRules.select(List.of(general), facts).exceptionType()).isNull();
    }

    private static ApprovalLevel level(ApprovalStage stage, int sequence, BigDecimal max, boolean manual, boolean automated) {
        return new ApprovalLevel(
                UUID.randomUUID(), UUID.randomUUID(), stage, sequence, stage.name(), BigDecimal.ZERO, max,
                stage == ApprovalStage.CREDIT_COMMITTEE ? 2 : 1, 24, manual, automated, false);
    }

    private static MatrixVersion version(UUID productId, String exceptionType, boolean automated) {
        return new MatrixVersion(
                UUID.randomUUID(), UUID.randomUUID(), 1, MatrixStatus.ACTIVE, productId, null, null, null, null, null,
                exceptionType, automated, BigDecimal.ZERO, null, "tester", Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2026-01-01T00:00:00Z"), "tester");
    }
}
