package com.mfb.lending.module.customer.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.CustomerType;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.domain.ClientChannel;
import com.mfb.lending.module.customer.domain.ConsentType;
import com.mfb.lending.module.customer.domain.CustomerCategory;
import com.mfb.lending.module.customer.domain.CustomerKind;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.CustomerStatus;
import com.mfb.lending.module.customer.domain.KycStatus;
import com.mfb.lending.module.customer.domain.RiskClassification;
import com.mfb.lending.module.customer.dto.ConsentRequest;
import com.mfb.lending.module.customer.dto.CreateCustomerRequest;
import com.mfb.lending.module.customer.dto.CustomerResponse;
import com.mfb.lending.module.customer.dto.CustomerSummaryResponse;
import com.mfb.lending.module.customer.dto.UpdateCustomerRequest;
import com.mfb.lending.module.customer.persistence.CustomerProfileRepository;
import com.mfb.lending.module.customer.persistence.CustomerQuery;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import com.mfb.lending.module.customer.persistence.LinkedUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CustomerService {

    private final CustomerRepository customers;
    private final CustomerProfileRepository profiles;
    private final CustomerViews views;
    private final CustomerEligibilityService eligibility;
    private final IdentitySeal seal;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public CustomerService(
            CustomerRepository customers,
            CustomerProfileRepository profiles,
            CustomerViews views,
            CustomerEligibilityService eligibility,
            IdentitySeal seal,
            AuditService audit,
            Clock clock,
            TransactionTemplate transactions) {
        this.customers = customers;
        this.profiles = profiles;
        this.views = views;
        this.eligibility = eligibility;
        this.seal = seal;
        this.audit = audit;
        this.clock = clock;
        this.transactions = transactions;
    }

    public CustomerResponse create(CreateCustomerRequest request, AuthenticatedUser actor, ClientContext client, ClientChannel channel) {
        CustomerKind kind = CustomerKind.valueOf(request.customerType());
        boolean self = actor.getPrincipalType() == PrincipalType.CUSTOMER;
        if (self) {
            if (request.branchId() != null) {
                throw ValidationException.field("branchId", "Branch assignment is performed by staff");
            }
            if (request.userId() != null && !request.userId().equals(actor.getId())) {
                throw new ForbiddenException("You cannot create a customer for another account");
            }
        } else if (!actor.permissions().contains(PermissionCodes.CUSTOMER_CREATE)) {
            throw new ForbiddenException("You do not have permission to create a customer");
        }

        UUID userId = self ? actor.getId() : request.userId();
        if (userId != null) {
            LinkedUser linked = customers.findUser(userId).orElseThrow(() -> new ResourceNotFoundException("User"));
            if (linked.principalType() != PrincipalType.CUSTOMER || linked.customerType() == CustomerType.NONE) {
                throw ValidationException.field("userId", "userId must be a customer account");
            }
            if (!linked.customerType().name().equals(kind.name())) {
                throw ValidationException.field("customerType", "customerType must match the linked account");
            }
            if (customers.findByUserId(userId).isPresent()) {
                throw new ConflictException(ErrorCode.DUPLICATE_CUSTOMER, "This account is already linked to a customer");
            }
        }

        UUID branchId = request.branchId();
        if (!self) {
            if (branchId == null) {
                throw ValidationException.field("branchId", "branchId is required");
            }
            CustomerAccess.assertBranch(actor, branchId);
            if (!customers.branchIsActive(branchId)) {
                throw new ResourceNotFoundException("Branch");
            }
        }

        String email = IdentityNumbers.email(request.email(), "email");
        String phone = IdentityNumbers.phone(request.phoneNumber(), "phoneNumber");
        CustomerCategory category = request.category() == null
                ? (kind == CustomerKind.INDIVIDUAL ? CustomerCategory.RETAIL : CustomerCategory.SMALL)
                : CustomerCategory.valueOf(request.category());
        UUID id = UUID.randomUUID();
        String actorName = actor.getUsername();
        try {
            transactions.executeWithoutResult(status -> {
                CustomerRecord customer = new CustomerRecord(
                        id,
                        customers.nextReference(),
                        kind,
                        CustomerStatus.PENDING,
                        category,
                        request.displayName().trim(),
                        email,
                        phone,
                        userId,
                        branchId,
                        KycStatus.NOT_STARTED,
                        RiskClassification.UNASSESSED,
                        false,
                        null,
                        false,
                        null,
                        null,
                        null,
                        actorName,
                        actorName);
                customers.insert(customer);
                saveConsents(id, request.consents(), channel);
            });
        } catch (DuplicateKeyException ex) {
            throw CustomerConflicts.translate(ex);
        }
        CustomerRecord saved = customers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer"));
        audit.record("CUSTOMER_CREATED", "customer", id.toString(), actorName, client, Map.of(
                "customerReference", saved.reference(),
                "customerType", kind.name(),
                "channel", channel.name()));
        return views.detail(saved);
    }

    public PageResponse<CustomerSummaryResponse> search(
            AuthenticatedUser actor,
            String query,
            String customerType,
            String status,
            String category,
            UUID branchId,
            String reference,
            String email,
            String phoneNumber,
            String bvn,
            String nin,
            String cacNumber,
            int page,
            int size) {
        CustomerAccess.assertCanSearch(actor);
        if (page < 0) {
            throw ValidationException.field("page", "page must be zero or greater");
        }
        if (size < 1 || size > 100) {
            throw ValidationException.field("size", "size must be between 1 and 100");
        }
        if (branchId != null) {
            CustomerAccess.assertBranch(actor, branchId);
        }
        String text = query == null || query.isBlank() ? null : query.trim();
        CustomerQuery customerQuery = new CustomerQuery(
                enumName(CustomerKind.class, customerType, "customerType"),
                enumName(CustomerStatus.class, status, "status"),
                enumName(CustomerCategory.class, category, "category"),
                branchId,
                blankToNull(reference) == null ? null : reference.trim().toUpperCase(Locale.ROOT),
                blankToNull(email) == null ? null : email.trim().toLowerCase(Locale.ROOT),
                blankToNull(phoneNumber),
                blankToNull(bvn) == null ? null : seal.hmac(IdentityNumbers.bvn(bvn)),
                blankToNull(nin) == null ? null : seal.hmac(IdentityNumbers.nin(nin)),
                blankToNull(cacNumber) == null ? null : IdentityNumbers.cac(cacNumber),
                text,
                text == null ? null : like(text),
                actor.unrestrictedBranches(),
                List.copyOf(actor.branchIds()),
                size,
                page * size);
        List<CustomerSummaryResponse> items = customers.search(customerQuery).stream().map(views::summary).toList();
        return new PageResponse<>(items, page, size, customers.count(customerQuery));
    }

    public CustomerResponse get(AuthenticatedUser actor, UUID id) {
        CustomerRecord customer = customers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer"));
        CustomerAccess.assertCanView(actor, customer);
        return views.detail(customer);
    }

    public CustomerResponse me(AuthenticatedUser actor) {
        if (actor.getPrincipalType() != PrincipalType.CUSTOMER) {
            throw new ForbiddenException("This endpoint is for customer accounts");
        }
        CustomerRecord customer = customers.findByUserId(actor.getId()).orElseThrow(() -> new ResourceNotFoundException("Customer"));
        return views.detail(customer);
    }

    public CustomerResponse update(UUID id, UpdateCustomerRequest request, AuthenticatedUser actor, ClientContext client) {
        try {
            transactions.executeWithoutResult(status -> applyUpdate(id, request, actor));
        } catch (DuplicateKeyException ex) {
            throw CustomerConflicts.translate(ex);
        }
        CustomerRecord saved = customers.findById(id).orElseThrow(() -> new ResourceNotFoundException("Customer"));
        audit.record("CUSTOMER_UPDATED", "customer", id.toString(), actor.getUsername(), client, Map.of(
                "status", saved.status().name(),
                "category", saved.category().name(),
                "blacklisted", saved.blacklisted(),
                "riskClassification", saved.riskClassification().name()));
        return views.detail(saved);
    }

    private void applyUpdate(UUID id, UpdateCustomerRequest request, AuthenticatedUser actor) {
        CustomerRecord current = customers.lockById(id).orElseThrow(() -> new ResourceNotFoundException("Customer"));
        CustomerAccess.assertCanMutate(actor, current);
        boolean owner = CustomerAccess.isOwner(actor, current);
        if (owner && (request.category() != null || request.branchId() != null || request.status() != null
                || request.blacklistReason() != null || request.riskClassification() != null)) {
            throw new ForbiddenException("You cannot change customer administration fields");
        }
        String displayName = request.displayName() == null ? current.displayName() : request.displayName().trim();
        String email = request.email() == null ? current.email() : IdentityNumbers.email(request.email(), "email");
        String phone = request.phoneNumber() == null ? current.phoneNumber() : IdentityNumbers.phone(request.phoneNumber(), "phoneNumber");
        CustomerCategory category = request.category() == null ? current.category() : CustomerCategory.valueOf(request.category());
        UUID branchId = request.branchId() == null ? current.branchId() : request.branchId();
        if (request.branchId() != null) {
            CustomerAccess.assertBranch(actor, branchId);
            if (!customers.branchIsActive(branchId)) {
                throw new ResourceNotFoundException("Branch");
            }
        }
        CustomerStatus status = request.status() == null ? current.status() : CustomerStatus.valueOf(request.status());
        RiskClassification risk = request.riskClassification() == null
                ? current.riskClassification()
                : RiskClassification.valueOf(request.riskClassification());
        if (request.riskClassification() != null && (current.complianceHold() || eligibility.blockedReasons(current).contains("COMPLIANCE_HOLD"))) {
            throw new ConflictException(ErrorCode.COMPLIANCE_HOLD, "Risk classification cannot be changed while a compliance hold is open");
        }
        boolean blacklisted = current.blacklisted();
        String blacklistReason = current.blacklistReason();
        Instant blacklistedAt = current.blacklisted() ? Instant.now(clock) : null;
        if (status == CustomerStatus.BLACKLISTED) {
            String reason = request.blacklistReason() == null ? current.blacklistReason() : request.blacklistReason().trim();
            if (reason == null || reason.isBlank()) {
                throw ValidationException.field("blacklistReason", "A blacklist reason is required");
            }
            blacklisted = true;
            blacklistReason = reason;
            blacklistedAt = Instant.now(clock);
        } else if (request.status() != null) {
            blacklisted = false;
            blacklistReason = null;
            blacklistedAt = null;
        }
        if (status == CustomerStatus.ACTIVE) {
            assertCanActivate(current, blacklisted);
        }
        customers.update(new CustomerRecord(
                current.id(),
                current.reference(),
                current.kind(),
                status,
                category,
                displayName,
                email,
                phone,
                current.userId(),
                branchId,
                current.kycStatus(),
                risk,
                blacklisted,
                blacklistReason,
                current.complianceHold(),
                current.complianceHoldReason(),
                current.createdAt(),
                current.updatedAt(),
                current.createdBy(),
                actor.getUsername()), blacklistedAt);
    }

    private void assertCanActivate(CustomerRecord current, boolean blacklisted) {
        if (blacklisted || current.complianceHold() || eligibility.blockedReasons(current).contains("COMPLIANCE_HOLD")) {
            throw new ConflictException(ErrorCode.COMPLIANCE_HOLD, "The customer has an unresolved compliance hold");
        }
        if (current.kycStatus() != KycStatus.VERIFIED) {
            throw ValidationException.field("status", "KYC must be verified before the customer can be activated");
        }
        if (current.kind() == CustomerKind.SME && profiles.representatives(current.id()).isEmpty()) {
            throw ValidationException.field("authorisedRepresentatives", "An SME customer needs at least one authorised representative");
        }
    }

    private void saveConsents(UUID customerId, List<ConsentRequest> consents, ClientChannel channel) {
        if (consents == null) {
            return;
        }
        for (ConsentRequest consent : consents) {
            profiles.upsertConsent(
                    UUID.randomUUID(),
                    customerId,
                    ConsentType.valueOf(consent.type()),
                    consent.granted(),
                    consent.version().trim(),
                    channel.name(),
                    consent.granted() ? Instant.now(clock) : null);
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String like(String text) {
        return "%" + text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    }

    private static <E extends Enum<E>> String enumName(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.trim()).name();
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field(field, field + " is invalid");
        }
    }
}
