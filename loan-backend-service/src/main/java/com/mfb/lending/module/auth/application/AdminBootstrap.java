package com.mfb.lending.module.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;

import com.mfb.lending.config.AuthProperties;
import com.mfb.lending.module.auth.domain.CustomerType;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import com.mfb.lending.module.auth.persistence.RoleRepository;
import com.mfb.lending.module.auth.persistence.UserRepository;
import com.mfb.lending.module.auth.security.AesGcmCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class AdminBootstrap {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrap.class);

    private final AuthProperties properties;
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwordEncoder;
    private final AesGcmCipher cipher;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public AdminBootstrap(
            AuthProperties properties,
            UserRepository users,
            RoleRepository roles,
            PasswordEncoder passwordEncoder,
            AesGcmCipher cipher,
            Clock clock,
            TransactionTemplate transactions) {
        this.properties = properties;
        this.users = users;
        this.roles = roles;
        this.passwordEncoder = passwordEncoder;
        this.cipher = cipher;
        this.clock = clock;
        this.transactions = transactions;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void seed() {
        String password = properties.getBootstrap().getPassword();
        if (password == null || password.isBlank()) {
            return;
        }
        String email = properties.getBootstrap().getEmail().trim().toLowerCase(Locale.ROOT);
        String phone = properties.getBootstrap().getPhoneNumber().trim();
        if (users.findByEmail(email).isPresent()) {
            return;
        }
        PasswordPolicy.check(password, email, phone);
        String passwordHash = passwordEncoder.encode(password);
        String encryptedSecret = cipher.encrypt(properties.getBootstrap().getMfaSecret().trim());
        transactions.executeWithoutResult(status -> insert(email, phone, passwordHash, encryptedSecret));
        log.info("Bootstrap administrator created for {}", email);
    }

    private void insert(String email, String phone, String passwordHash, String encryptedSecret) {
        if (users.existsByEmail(email)) {
            return;
        }
        Instant now = clock.instant();
        var userId = java.util.UUID.randomUUID();
        users.insert(new UserRecord(
                userId,
                PrincipalType.STAFF,
                CustomerType.NONE,
                properties.getBootstrap().getFullName(),
                null,
                email,
                phone,
                passwordHash,
                UserStatus.ACTIVE,
                true,
                true,
                0,
                null,
                now,
                now.plus(properties.getPassword().getStaffMaxAge()),
                true,
                encryptedSecret,
                null,
                now,
                now,
                "bootstrap",
                "bootstrap"));
        var role = roles.findByCode(PermissionCodes.SYSTEM_ADMIN_ROLE)
                .orElseThrow(() -> new IllegalStateException("System administrator role is not configured"));
        roles.assignRole(userId, role.id(), "bootstrap");
    }
}
