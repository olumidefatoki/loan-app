package com.mfb.lending.module.collections.application;

import java.util.UUID;

public interface BranchDirectory {

    boolean exists(UUID branchId);
}
