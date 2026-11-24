package com.mfb.lending.module.compliance.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.compliance.domain.ComplianceModels.CaseActivity;
import com.mfb.lending.module.compliance.domain.ComplianceModels.ComplianceAlert;
import com.mfb.lending.module.compliance.domain.ComplianceModels.ComplianceCase;

public interface ComplianceStore {

    void insertAlert(ComplianceAlert alert);

    ComplianceCase insertCase(ComplianceCase complianceCase);

    Optional<ComplianceAlert> findAlert(UUID id);

    Optional<ComplianceCase> findCase(UUID id);

    long countAlerts(String status, String alertType);

    List<ComplianceAlert> listAlerts(String status, String alertType, int limit, int offset);

    long countCases(String status, String caseType);

    List<ComplianceCase> listCases(String status, String caseType, int limit, int offset);

    List<CaseActivity> activities(UUID caseId);

    void insertActivity(CaseActivity activity);

    void assign(UUID id, UUID assigneeId, String status);

    void markInReview(UUID id);

    void escalate(UUID id, Instant escalatedAt);

    void resolve(ComplianceCase decided);

    void updateAlertStatus(UUID alertId, String status);

    boolean officerCanWork(UUID userId);

    void updateRisk(UUID customerId, String classification, int score, String actor);

    long openCases();

    String nextReference();
}
