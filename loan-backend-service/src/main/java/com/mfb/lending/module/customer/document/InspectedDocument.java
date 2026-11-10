package com.mfb.lending.module.customer.document;

public record InspectedDocument(String contentType, String checksumSha256, long sizeBytes, String fileName) {
}
