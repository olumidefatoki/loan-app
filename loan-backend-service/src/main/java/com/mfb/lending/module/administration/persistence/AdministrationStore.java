package com.mfb.lending.module.administration.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.administration.domain.AdministrationModels.Branch;
import com.mfb.lending.module.administration.domain.AdministrationModels.BusinessCalendar;
import com.mfb.lending.module.administration.domain.AdministrationModels.ChangeRequest;
import com.mfb.lending.module.administration.domain.AdministrationModels.FeatureFlag;
import com.mfb.lending.module.administration.domain.AdministrationModels.PublicHoliday;
import com.mfb.lending.module.administration.domain.AdministrationModels.SystemConfiguration;

public interface AdministrationStore {

    boolean databaseReady();

    List<Branch> listBranches(int limit, int offset);

    long countBranches();

    Optional<Branch> findBranch(UUID id);

    Optional<Branch> findBranchByCode(String code);

    Branch insertBranch(Branch branch, String actor);

    void updateBranch(Branch branch, String actor);

    void deleteBranch(UUID id);

    List<SystemConfiguration> listConfigurations(int limit, int offset);

    long countConfigurations();

    Optional<SystemConfiguration> findConfiguration(UUID id);

    Optional<SystemConfiguration> findConfigurationByKey(String key);

    SystemConfiguration upsertConfiguration(String key, String value, String description, String valueType, boolean sensitive, String actor);

    void deleteConfiguration(UUID id);

    ChangeRequest insertChange(ChangeRequest request);

    Optional<ChangeRequest> findChange(UUID id);

    List<ChangeRequest> listChanges(String status, int limit, int offset);

    long countChanges(String status);

    void decideChange(UUID id, String status, UUID decidedBy, String decidedByName, String reason, Instant decidedAt);

    long pendingApprovals();

    List<BusinessCalendar> listCalendars();

    Optional<BusinessCalendar> findCalendar(UUID id);

    BusinessCalendar insertCalendar(BusinessCalendar calendar, String actor);

    void updateCalendar(BusinessCalendar calendar, String actor);

    void deleteCalendar(UUID id);

    List<PublicHoliday> holidays(UUID calendarId);

    PublicHoliday insertHoliday(PublicHoliday holiday, String actor);

    void deleteHoliday(UUID calendarId, UUID holidayId);

    boolean holiday(UUID calendarId, LocalDate date);

    List<FeatureFlag> listFlags();

    Optional<FeatureFlag> findFlag(UUID id);

    Optional<FeatureFlag> findFlagByKey(String key);

    FeatureFlag upsertFlag(String key, boolean enabled, String description, boolean sensitive, String actor);

    void deleteFlag(UUID id);
}
