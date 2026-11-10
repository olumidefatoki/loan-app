package com.mfb.lending.module.loanapplication.persistence;

import java.util.Collection;
import java.util.UUID;

import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.loanapplication.domain.ApplicationStatus;

public record ApplicationSearch(
        UUID customerId,
        Collection<UUID> branchIds,
        boolean restrictBranches,
        ApplicationStatus status,
        UUID productId,
        CustomerKind segment,
        String query,
        Collection<ApplicationStatus> excludedStatuses,
        String sortColumn,
        boolean ascending,
        int limit,
        int offset
) {
}
