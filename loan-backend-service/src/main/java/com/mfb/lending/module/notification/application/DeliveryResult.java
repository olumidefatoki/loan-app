package com.mfb.lending.module.notification.application;

public record DeliveryResult(Kind kind, String providerMessageId, String error) {

    public enum Kind {
        ACCEPTED, FAILED, SKIPPED
    }

    public static DeliveryResult accepted(String providerMessageId) {
        return new DeliveryResult(Kind.ACCEPTED, providerMessageId, null);
    }

    public static DeliveryResult failed(String error) {
        return new DeliveryResult(Kind.FAILED, null, error);
    }

    public static DeliveryResult skipped(String error) {
        return new DeliveryResult(Kind.SKIPPED, null, error);
    }
}
