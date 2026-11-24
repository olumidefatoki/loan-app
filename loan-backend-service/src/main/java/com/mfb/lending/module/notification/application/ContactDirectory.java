package com.mfb.lending.module.notification.application;

import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.module.notification.domain.NotificationModels.Contact;

public interface ContactDirectory {

    Optional<Contact> find(UUID customerId);
}
