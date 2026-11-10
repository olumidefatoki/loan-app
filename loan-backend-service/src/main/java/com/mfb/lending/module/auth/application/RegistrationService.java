package com.mfb.lending.module.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.config.AuthProperties;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.CustomerType;
import com.mfb.lending.module.auth.domain.OtpChannel;
import com.mfb.lending.module.auth.domain.OtpPurpose;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import com.mfb.lending.module.auth.dto.RegisterCustomerRequest;
import com.mfb.lending.module.auth.dto.RegistrationResponse;
import com.mfb.lending.module.auth.persistence.RoleRepository;
import com.mfb.lending.module.auth.persistence.UserRepository;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RegistrationService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final OtpService otps;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final RateLimiter rateLimiter;
    private final AuthProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public RegistrationService(
            UserRepository users,
            RoleRepository roles,
            OtpService otps,
            PasswordEncoder passwordEncoder,
            AuditService audit,
            RateLimiter rateLimiter,
            AuthProperties properties,
            Clock clock,
            TransactionTemplate transactions) {
        this.users = users;
        this.roles = roles;
        this.otps = otps;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
        this.clock = clock;
        this.transactions = transactions;
    }

    public RegistrationResponse register(RegisterCustomerRequest request, ClientContext client) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String phone = request.phoneNumber().trim();
        String fullName = request.fullName().trim();
        CustomerType customerType = CustomerType.valueOf(request.customerType());
        String businessName = request.businessName() == null ? null : request.businessName().trim();
        if (customerType == CustomerType.SME && (businessName == null || businessName.isBlank())) {
            throw ValidationException.field("businessName", "businessName is required for an SME customer");
        }
        if (customerType == CustomerType.INDIVIDUAL) {
            businessName = null;
        }
        PasswordPolicy.check(request.password(), email, phone);
        rateLimiter.assertAllowed(
                "register",
                client.ipAddress(),
                email,
                properties.getRateLimit().getRegisterMaxAttempts(),
                properties.getRateLimit().getRegisterWindow());
        String passwordHash = passwordEncoder.encode(request.password());
        String storedBusinessName = businessName;
        return transactions.execute(status -> insert(email, phone, fullName, customerType, storedBusinessName, passwordHash, client));
    }

    private RegistrationResponse insert(
            String email,
            String phone,
            String fullName,
            CustomerType customerType,
            String businessName,
            String passwordHash,
            ClientContext client) {
        if (users.existsByEmail(email) || users.existsByPhone(phone)) {
            throw new ConflictException("An account with this email or phone number already exists");
        }
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();
        UserRecord user = new UserRecord(
                id,
                PrincipalType.CUSTOMER,
                customerType,
                fullName,
                businessName,
                email,
                phone,
                passwordHash,
                UserStatus.PENDING_VERIFICATION,
                false,
                false,
                0,
                null,
                now,
                null,
                false,
                null,
                null,
                now,
                now,
                "self",
                "self");
        try {
            users.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("An account with this email or phone number already exists");
        }
        var role = roles.findByCode(PermissionCodes.CUSTOMER_ROLE)
                .orElseThrow(() -> new IllegalStateException("Customer role is not configured"));
        roles.assignRole(id, role.id(), "self");
        otps.issueInTransaction(user, OtpPurpose.EMAIL_VERIFICATION, OtpChannel.EMAIL, email, client);
        otps.issueInTransaction(user, OtpPurpose.PHONE_VERIFICATION, OtpChannel.SMS, phone, client);
        audit.record("CUSTOMER_REGISTERED", "system_user", id.toString(), id.toString(), client,
                Map.of("customerType", customerType.name()));
        return new RegistrationResponse(id, UserStatus.PENDING_VERIFICATION.name(), List.of("EMAIL", "PHONE"));
    }
}
