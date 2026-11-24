package com.mfb.lending.module.complaint.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.mfb.lending.module.complaint.domain.ComplaintModels.Complaint;
import com.mfb.lending.module.complaint.domain.ComplaintModels.ComplaintActivity;

public interface ComplaintStore {

    Complaint insert(Complaint complaint);

    Optional<Complaint> find(UUID id);

    long count(UUID customerId, Set<UUID> branchIds, boolean unrestricted, String status);

    List<Complaint> list(UUID customerId, Set<UUID> branchIds, boolean unrestricted, String status, int limit, int offset);

    List<ComplaintActivity> activities(UUID complaintId);

    void insertActivity(ComplaintActivity activity);

    void assign(UUID id, UUID assigneeId);

    void escalate(UUID id, Instant escalatedAt);

    void resolve(UUID id, String resolution, Instant resolvedAt);

    List<Complaint> breached(Instant now);

    long breachedCount(Instant now);

    String nextReference();

    boolean officerCanManage(UUID userId);
}
