package com.mfb.lending.module.customer.dto;

import java.util.UUID;

public record AddressResponse(
        UUID id,
        String addressType,
        String line1,
        String line2,
        String city,
        String state,
        String countryCode,
        String postalCode
) {
}
