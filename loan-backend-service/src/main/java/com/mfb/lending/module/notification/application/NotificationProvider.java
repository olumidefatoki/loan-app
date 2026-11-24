package com.mfb.lending.module.notification.application;

public interface NotificationProvider {

    String channel();

    String code();

    DeliveryResult send(OutboundNotice notice);
}
