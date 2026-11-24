package com.mfb.lending.module.collections.application;

import java.util.UUID;

public interface OfficerDirectory {

    void requireCollector(UUID officerId);
}
