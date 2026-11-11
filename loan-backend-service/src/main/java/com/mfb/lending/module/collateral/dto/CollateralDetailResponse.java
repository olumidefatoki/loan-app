package com.mfb.lending.module.collateral.dto;

import java.util.List;

public record CollateralDetailResponse(
        CollateralResponse collateral,
        List<ValuationResponse> valuations,
        List<StoredDocumentResponse> documents,
        InsuranceResponse insurance,
        List<EncumbranceResponse> encumbrances,
        List<AllocationSummaryResponse> allocations,
        List<ReleaseResponse> releases
) {
}
