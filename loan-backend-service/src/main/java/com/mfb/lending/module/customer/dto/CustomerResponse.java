package com.mfb.lending.module.customer.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record CustomerResponse(
        UUID id,
        String customerReference,
        String customerType,
        String status,
        String category,
        String displayName,
        String email,
        String phoneNumber,
        UUID userId,
        UUID branchId,
        String kycStatus,
        String riskClassification,
        boolean blacklisted,
        String blacklistReason,
        boolean complianceHold,
        String complianceHoldReason,
        boolean disbursementAllowed,
        List<String> disbursementBlockedReasons,
        IndividualProfileResponse individual,
        SmeProfileResponse sme,
        List<AddressResponse> addresses,
        List<PartyResponse> directors,
        List<PartyResponse> beneficialOwners,
        List<PartyResponse> authorisedRepresentatives,
        Instant createdAt,
        Instant updatedAt
) {
}
