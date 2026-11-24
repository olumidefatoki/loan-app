package com.mfb.lending.module.collections.application;

import java.util.UUID;

import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import com.mfb.lending.module.auth.persistence.SecurityProfileRepository;
import com.mfb.lending.module.auth.persistence.UserRepository;
import org.springframework.stereotype.Component;

@Component
public class StaffOfficerDirectory implements OfficerDirectory {

    private final UserRepository users;
    private final SecurityProfileRepository profiles;

    public StaffOfficerDirectory(UserRepository users, SecurityProfileRepository profiles) {
        this.users = users;
        this.profiles = profiles;
    }

    @Override
    public void requireCollector(UUID officerId) {
        UserRecord user = users.findById(officerId).orElseThrow(() -> new ResourceNotFoundException("Collection officer"));
        if (user.principalType() != PrincipalType.STAFF || user.status() != UserStatus.ACTIVE) {
            throw ValidationException.field("officerId", "The officer must be an active staff user");
        }
        if (!profiles.load(officerId).permissions().contains(PermissionCodes.COLLECTION_MANAGE)) {
            throw ValidationException.field("officerId", "The officer cannot manage collections");
        }
    }
}
