package com.mfb.lending.module.auth.application;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.config.AuthProperties;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.CustomerType;
import com.mfb.lending.module.auth.domain.PrincipalType;
import com.mfb.lending.module.auth.domain.RoleRecord;
import com.mfb.lending.module.auth.domain.SecurityProfile;
import com.mfb.lending.module.auth.domain.UserRecord;
import com.mfb.lending.module.auth.domain.UserStatus;
import com.mfb.lending.module.auth.dto.AssignRolesRequest;
import com.mfb.lending.module.auth.dto.CreateStaffRequest;
import com.mfb.lending.module.auth.dto.CreateUserResponse;
import com.mfb.lending.module.auth.dto.MfaSetup;
import com.mfb.lending.module.auth.dto.MessageResponse;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.dto.RoleAssignmentResponse;
import com.mfb.lending.module.auth.dto.SessionResponse;
import com.mfb.lending.module.auth.dto.UpdateUserRequest;
import com.mfb.lending.module.auth.dto.UserResponse;
import com.mfb.lending.module.auth.persistence.BranchAssignmentRepository;
import com.mfb.lending.module.auth.persistence.RefreshTokenRepository;
import com.mfb.lending.module.auth.persistence.RoleRepository;
import com.mfb.lending.module.auth.persistence.SecurityProfileRepository;
import com.mfb.lending.module.auth.persistence.SessionRepository;
import com.mfb.lending.module.auth.persistence.UserRepository;
import com.mfb.lending.module.auth.persistence.UserRepository.UserSearch;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AdminUserService {

    private final UserRepository users;
    private final RoleRepository roles;
    private final SecurityProfileRepository profiles;
    private final BranchAssignmentRepository branches;
    private final SessionRepository sessions;
    private final RefreshTokenRepository refreshTokens;
    private final AuthenticationService authentication;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final AuthProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public AdminUserService(
            UserRepository users,
            RoleRepository roles,
            SecurityProfileRepository profiles,
            BranchAssignmentRepository branches,
            SessionRepository sessions,
            RefreshTokenRepository refreshTokens,
            AuthenticationService authentication,
            PasswordEncoder passwordEncoder,
            AuditService audit,
            AuthProperties properties,
            Clock clock,
            TransactionTemplate transactions) {
        this.users = users;
        this.roles = roles;
        this.profiles = profiles;
        this.branches = branches;
        this.sessions = sessions;
        this.refreshTokens = refreshTokens;
        this.authentication = authentication;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
        this.transactions = transactions;
    }

    public PageResponse<UserResponse> list(AuthenticatedUser actor, String principalType, UUID branchId, int page, int size) {
        if (page < 0) {
            throw ValidationException.field("page", "page must be zero or greater");
        }
        if (size < 1 || size > 100) {
            throw ValidationException.field("size", "size must be between 1 and 100");
        }
        PrincipalType type = parseType(principalType);
        AccessPolicy.assertBranchAllowed(AuthViews.actor(actor), branchId);
        UserSearch search = new UserSearch(
                type,
                branchId,
                actor.getId(),
                List.copyOf(actor.branchIds()),
                actor.unrestrictedBranches(),
                size,
                page * (long) size > Integer.MAX_VALUE ? Integer.MAX_VALUE : page * size);
        List<UserResponse> items = users.search(search).stream()
                .map(user -> AuthViews.user(user, profiles.load(user.id())))
                .toList();
        return new PageResponse<>(items, page, size, users.count(search));
    }

    public UserResponse get(AuthenticatedUser actor, UUID userId) {
        Loaded loaded = load(userId);
        AccessPolicy.assertCanView(AuthViews.actor(actor), AuthViews.subject(loaded.user(), loaded.profile()));
        return AuthViews.user(loaded.user(), loaded.profile());
    }

    public CreateUserResponse create(CreateStaffRequest request, AuthenticatedUser actor, ClientContext client) {
        String email = request.email().trim().toLowerCase(Locale.ROOT);
        String phone = request.phoneNumber().trim();
        PasswordPolicy.check(request.password(), email, phone);
        String passwordHash = passwordEncoder.encode(request.password());
        return transactions.execute(status -> createInTransaction(request, email, phone, passwordHash, actor, client));
    }

    public UserResponse update(UUID userId, UpdateUserRequest request, AuthenticatedUser actor, ClientContext client) {
        return transactions.execute(status -> updateInTransaction(userId, request, actor, client));
    }

    public UserResponse disable(UUID userId, AuthenticatedUser actor, ClientContext client) {
        return update(userId, new UpdateUserRequest(null, null, null, null, UserStatus.DISABLED.name(), null, null), actor, client);
    }

    public RoleAssignmentResponse assignRoles(UUID userId, AssignRolesRequest request, AuthenticatedUser actor, ClientContext client) {
        return transactions.execute(status -> assignInTransaction(userId, request.roleCodes(), actor, client));
    }

    public List<SessionResponse> sessions(AuthenticatedUser actor, UUID userId) {
        Loaded loaded = load(userId);
        AccessPolicy.assertCanView(AuthViews.actor(actor), AuthViews.subject(loaded.user(), loaded.profile()));
        return sessions.listByUser(userId, 50).stream().map(AuthViews::session).toList();
    }

    public MessageResponse revokeSessions(UUID userId, AuthenticatedUser actor, ClientContext client) {
        transactions.executeWithoutResult(status -> {
            Loaded loaded = loadLocked(userId);
            AccessPolicy.assertCanMutate(AuthViews.actor(actor), AuthViews.subject(loaded.user(), loaded.profile()));
            Instant now = clock.instant();
            sessions.revokeAll(userId, now, "ADMIN_REVOKE");
            refreshTokens.revokeUser(userId, now);
            audit.record("SESSION_REVOKED", "system_user", userId.toString(), actor.getId().toString(), client,
                    Map.of("scope", "ALL"));
        });
        return new MessageResponse("Sessions revoked");
    }

    private CreateUserResponse createInTransaction(
            CreateStaffRequest request,
            String email,
            String phone,
            String passwordHash,
            AuthenticatedUser actor,
            ClientContext client) {
        List<RoleRecord> selected = requireRoles(request.roleCodes());
        List<String> permissions = profiles.permissionCodesForRoles(selected.stream().map(RoleRecord::id).toList());
        boolean unrestricted = selected.stream().anyMatch(RoleRecord::unrestrictedBranches);
        BranchSelection selection = requireBranches(request.branchIds(), request.primaryBranchId(), unrestricted);
        for (UUID branchId : selection.branchIds()) {
            AccessPolicy.assertBranchAllowed(AuthViews.actor(actor), branchId);
        }
        UUID id = UUID.randomUUID();
        AccessPolicy.assertCanAssign(
                AuthViews.actor(actor),
                new AccessPolicy.Subject(id, new HashSet<>(selection.branchIds())),
                selected,
                permissions);
        if (users.existsByEmail(email) || users.existsByPhone(phone)) {
            throw new ConflictException("An account with this email or phone number already exists");
        }
        Instant now = clock.instant();
        UserRecord user = new UserRecord(
                id,
                PrincipalType.STAFF,
                CustomerType.NONE,
                request.fullName().trim(),
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
                false,
                null,
                null,
                now,
                now,
                actor.getId().toString(),
                actor.getId().toString());
        try {
            users.insert(user);
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("An account with this email or phone number already exists");
        }
        roles.replaceUserRoles(id, selected.stream().map(RoleRecord::id).toList(), actor.getId().toString());
        branches.replace(id, selection.branchIds(), selection.primaryBranchId());
        MfaSetup mfaSetup = null;
        if (selected.stream().anyMatch(RoleRecord::requiresMfa)) {
            mfaSetup = authentication.enroll(id, actor.getId().toString());
        }
        audit.record("USER_CREATED", "system_user", id.toString(), actor.getId().toString(), client,
                Map.of("roles", selected.stream().map(RoleRecord::code).toList()));
        return new CreateUserResponse(AuthViews.user(users.findById(id).orElseThrow(), profiles.load(id)), mfaSetup);
    }

    private UserResponse updateInTransaction(UUID userId, UpdateUserRequest request, AuthenticatedUser actor, ClientContext client) {
        Loaded loaded = loadLocked(userId);
        AccessPolicy.assertCanMutate(AuthViews.actor(actor), AuthViews.subject(loaded.user(), loaded.profile()));
        UserRecord current = loaded.user();
        String fullName = request.fullName() == null ? current.fullName() : request.fullName().trim();
        String email = request.email() == null ? current.email() : request.email().trim().toLowerCase(Locale.ROOT);
        String phone = request.phoneNumber() == null ? current.phoneNumber() : request.phoneNumber().trim();
        String businessName = request.businessName() == null ? current.businessName() : blankToNull(request.businessName());
        if (fullName.isBlank()) {
            throw ValidationException.field("fullName", "fullName is required");
        }
        if (!email.equals(current.email()) && users.existsByEmail(email)) {
            throw new ConflictException("An account with this email or phone number already exists");
        }
        if (!phone.equals(current.phoneNumber()) && users.existsByPhone(phone)) {
            throw new ConflictException("An account with this email or phone number already exists");
        }
        UserStatus status = request.status() == null ? current.status() : UserStatus.valueOf(request.status());
        if (status == UserStatus.ACTIVE && current.principalType() == PrincipalType.CUSTOMER
                && (!current.emailVerified() || !current.phoneVerified())) {
            throw new ConflictException("The customer must verify email and phone before activation");
        }
        if (request.branchIds() != null) {
            boolean unrestricted = loaded.profile().unrestrictedBranches();
            BranchSelection selection = requireBranches(request.branchIds(), request.primaryBranchId(), unrestricted);
            for (UUID branchId : selection.branchIds()) {
                AccessPolicy.assertBranchAllowed(AuthViews.actor(actor), branchId);
            }
            branches.replace(userId, selection.branchIds(), selection.primaryBranchId());
        }
        users.updateProfile(userId, fullName, businessName, email, phone, actor.getId().toString());
        if (status != current.status()) {
            users.updateStatus(userId, status, actor.getId().toString());
            if (status == UserStatus.DISABLED) {
                Instant now = clock.instant();
                sessions.revokeAll(userId, now, "ACCOUNT_DISABLED");
                refreshTokens.revokeUser(userId, now);
            }
        }
        audit.record("USER_UPDATED", "system_user", userId.toString(), actor.getId().toString(), client,
                Map.of("status", current.status().name()),
                Map.of("status", status.name(), "email", email));
        return AuthViews.user(users.findById(userId).orElseThrow(), profiles.load(userId));
    }

    private RoleAssignmentResponse assignInTransaction(
            UUID userId,
            List<String> roleCodes,
            AuthenticatedUser actor,
            ClientContext client) {
        Loaded loaded = loadLocked(userId);
        List<RoleRecord> selected = requireRoles(roleCodes);
        List<String> permissions = profiles.permissionCodesForRoles(selected.stream().map(RoleRecord::id).toList());
        AccessPolicy.assertCanAssign(
                AuthViews.actor(actor),
                AuthViews.subject(loaded.user(), loaded.profile()),
                selected,
                permissions);
        roles.replaceUserRoles(userId, selected.stream().map(RoleRecord::id).toList(), actor.getId().toString());
        MfaSetup mfaSetup = null;
        if (selected.stream().anyMatch(RoleRecord::requiresMfa) && !loaded.user().mfaEnabled()) {
            mfaSetup = authentication.enroll(userId, actor.getId().toString());
        }
        audit.record("ROLES_ASSIGNED", "system_user", userId.toString(), actor.getId().toString(), client,
                Map.of("roles", selected.stream().map(RoleRecord::code).toList()));
        return new RoleAssignmentResponse(AuthViews.user(users.findById(userId).orElseThrow(), profiles.load(userId)), mfaSetup);
    }

    private List<RoleRecord> requireRoles(List<String> roleCodes) {
        List<String> codes = roleCodes.stream().map(code -> code.trim().toUpperCase(Locale.ROOT)).distinct().toList();
        List<RoleRecord> selected = roles.findByCodes(codes);
        if (selected.size() != codes.size()) {
            throw ValidationException.field("roleCodes", "One or more roles do not exist");
        }
        return selected;
    }

    private BranchSelection requireBranches(List<UUID> requested, UUID primaryBranchId, boolean unrestrictedRole) {
        List<UUID> branchIds = requested == null ? List.of() : requested.stream().distinct().toList();
        if (!unrestrictedRole && branchIds.isEmpty()) {
            throw ValidationException.field("branchIds", "At least one branch is required");
        }
        if (!branchIds.isEmpty() && branches.findActiveBranchIds(branchIds).size() != branchIds.size()) {
            throw ValidationException.field("branchIds", "One or more branches are missing or inactive");
        }
        if (branchIds.size() > 1 && primaryBranchId == null) {
            throw ValidationException.field("primaryBranchId", "primaryBranchId is required when more than one branch is assigned");
        }
        UUID primary = branchIds.isEmpty() ? null : (primaryBranchId == null ? branchIds.getFirst() : primaryBranchId);
        if (primary != null && !branchIds.contains(primary)) {
            throw ValidationException.field("primaryBranchId", "primaryBranchId must be one of branchIds");
        }
        return new BranchSelection(branchIds, primary);
    }

    private Loaded load(UUID userId) {
        UserRecord user = users.findById(userId).orElseThrow(() -> new ResourceNotFoundException("User"));
        return new Loaded(user, profiles.load(userId));
    }

    private Loaded loadLocked(UUID userId) {
        UserRecord user = users.lockById(userId).orElseThrow(() -> new ResourceNotFoundException("User"));
        return new Loaded(user, profiles.load(userId));
    }

    private static PrincipalType parseType(String principalType) {
        if (principalType == null || principalType.isBlank()) {
            return null;
        }
        try {
            return PrincipalType.valueOf(principalType.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("principalType", "principalType must be CUSTOMER or STAFF");
        }
    }

    private static String blankToNull(String value) {
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record Loaded(UserRecord user, SecurityProfile profile) {
    }

    private record BranchSelection(List<UUID> branchIds, UUID primaryBranchId) {
    }
}
