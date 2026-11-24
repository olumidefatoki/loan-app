package com.mfb.lending.module.notification.application;

public interface NotificationPublisher {

    PublishResult publish(NotificationEvent event);

    void publishOtp(OtpNotice notice);

    static NotificationPublisher noop() {
        return new NotificationPublisher() {
            @Override
            public PublishResult publish(NotificationEvent event) {
                return new PublishResult(0, 0);
            }

            @Override
            public void publishOtp(OtpNotice notice) {
            }
        };
    }
}
