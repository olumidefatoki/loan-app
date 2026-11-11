package com.mfb.lending.module.approval.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.approval.domain.ApprovalDelegation;
import com.mfb.lending.module.approval.domain.ApprovalLevel;
import com.mfb.lending.module.approval.domain.ApprovalMatrix;
import com.mfb.lending.module.approval.domain.ApprovalStage;
import com.mfb.lending.module.approval.domain.MatrixStatus;
import com.mfb.lending.module.approval.domain.MatrixVersion;
import com.mfb.lending.module.approval.dto.ApprovalMatrixResponse;
import com.mfb.lending.module.approval.dto.DelegationResponse;
import com.mfb.lending.module.approval.dto.GrantDelegationRequest;
import com.mfb.lending.module.approval.dto.LevelRequest;
import com.mfb.lending.module.approval.dto.SaveApprovalMatrixRequest;
import com.mfb.lending.module.approval.persistence.ApprovalRepository;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ApprovalMatrixService {

    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_]{1,39}$");
    private static final Set<String> SEGMENTS = Set.of("INDIVIDUAL", "SME");
    private static final Set<String> RISKS = Set.of("UNASSESSED", "LOW", "MEDIUM", "HIGH", "UNACCEPTABLE");
    private static final Set<String> COLLATERAL = Set.of("NONE", "PENDING", "ADEQUATE", "INADEQUATE");
    private static final Set<String> SECURITY = Set.of("SECURED", "UNSECURED");

    private final ApprovalRepository approvals;
    private final ApprovalViews views;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public ApprovalMatrixService(
            ApprovalRepository approvals,
            ApprovalViews views,
            AuditService audit,
            Clock clock,
            TransactionTemplate transactions) {
        this.approvals = approvals;
        this.views = views;
        this.audit = audit;
        this.clock = clock;
        this.transactions = transactions;
    }

    public ApprovalMatrixResponse create(SaveApprovalMatrixRequest request, AuthenticatedUser actor, ClientContext client) {
        ApprovalMatrixResponse response = transactions.execute(status -> createLocked(request, actor));
        audit.record("APPROVAL_MATRIX_CREATED", "approval_matrix", response.id().toString(), actor.getUsername(), client, Map.of(
                "code", response.code()));
        return response;
    }

    public List<ApprovalMatrixResponse> list() {
        return approvals.listMatrices().stream().map(this::assemble).toList();
    }

    public ApprovalMatrixResponse get(UUID id) {
        return assemble(requireMatrix(id));
    }

    public ApprovalMatrixResponse update(UUID id, SaveApprovalMatrixRequest request, AuthenticatedUser actor, ClientContext client) {
        ApprovalMatrixResponse response = transactions.execute(status -> updateLocked(id, request, actor));
        audit.record("APPROVAL_MATRIX_UPDATED", "approval_matrix", id.toString(), actor.getUsername(), client, Map.of(
                "code", response.code()));
        return response;
    }

    public void delete(UUID id, AuthenticatedUser actor, ClientContext client) {
        transactions.executeWithoutResult(status -> deleteLocked(id));
        audit.record("APPROVAL_MATRIX_DELETED", "approval_matrix", id.toString(), actor.getUsername(), client, Map.of());
    }

    public ApprovalMatrixResponse activate(UUID id, AuthenticatedUser actor, ClientContext client) {
        ApprovalMatrixResponse response = transactions.execute(status -> activateLocked(id, actor));
        audit.record("APPROVAL_MATRIX_ACTIVATED", "approval_matrix", id.toString(), actor.getUsername(), client, Map.of(
                "code", response.code()));
        return response;
    }

    public DelegationResponse grant(GrantDelegationRequest request, AuthenticatedUser actor, ClientContext client) {
        DelegationResponse response = transactions.execute(status -> grantLocked(request, actor));
        audit.record("APPROVAL_DELEGATION_GRANTED", "approval_delegation", response.id().toString(), actor.getUsername(), client, Map.of(
                "userId", response.userId().toString(),
                "stage", response.stage(),
                "maxAmount", response.maxAmount().toPlainString()));
        return response;
    }

    public List<DelegationResponse> delegations(UUID userId) {
        return approvals.listDelegations(userId).stream().map(views::delegation).toList();
    }

    public void revoke(UUID id, AuthenticatedUser actor, ClientContext client) {
        transactions.executeWithoutResult(status -> {
            if (approvals.findDelegation(id).isEmpty()) {
                throw new ResourceNotFoundException("Approval delegation");
            }
            if (!approvals.revokeDelegation(id)) {
                throw new ConflictException("This delegation is already inactive");
            }
        });
        audit.record("APPROVAL_DELEGATION_REVOKED", "approval_delegation", id.toString(), actor.getUsername(), client, Map.of());
    }

    private ApprovalMatrixResponse createLocked(SaveApprovalMatrixRequest request, AuthenticatedUser actor) {
        String code = code(request.code());
        if (approvals.findMatrixByCode(code).isPresent()) {
            throw new ConflictException("An approval matrix with this code already exists");
        }
        Checked checked = check(request);
        UUID matrixId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        approvals.insertMatrix(new ApprovalMatrix(
                matrixId, code, checked.name(), checked.description(), MatrixStatus.DRAFT, null,
                null, null, actor.getUsername(), actor.getUsername()));
        approvals.insertVersion(version(versionId, matrixId, 1, MatrixStatus.DRAFT, checked, null, null, actor.getUsername()));
        approvals.replaceLevels(versionId, levels(versionId, checked.levels()));
        approvals.updateMatrix(matrixId, checked.name(), checked.description(), MatrixStatus.DRAFT, versionId, actor.getUsername());
        return assemble(requireMatrix(matrixId));
    }

    private ApprovalMatrixResponse updateLocked(UUID id, SaveApprovalMatrixRequest request, AuthenticatedUser actor) {
        ApprovalMatrix matrix = requireMatrix(id);
        if (!matrix.code().equals(code(request.code()))) {
            throw ValidationException.field("code", "The matrix code cannot be changed");
        }
        Checked checked = check(request);
        MatrixVersion draft = approvals.findDraft(id).orElse(null);
        if (draft == null) {
            int number = approvals.listVersions(id).stream().mapToInt(MatrixVersion::versionNumber).max().orElse(0) + 1;
            UUID versionId = UUID.randomUUID();
            approvals.insertVersion(version(versionId, id, number, MatrixStatus.DRAFT, checked, null, null, actor.getUsername()));
            approvals.replaceLevels(versionId, levels(versionId, checked.levels()));
        } else {
            approvals.updateDraft(version(draft.id(), id, draft.versionNumber(), MatrixStatus.DRAFT, checked, null, null, draft.createdBy()));
            approvals.replaceLevels(draft.id(), levels(draft.id(), checked.levels()));
        }
        approvals.updateMatrix(id, checked.name(), checked.description(), matrix.status(), matrix.currentVersionId(), actor.getUsername());
        return assemble(requireMatrix(id));
    }

    private void deleteLocked(UUID id) {
        ApprovalMatrix matrix = requireMatrix(id);
        boolean published = approvals.listVersions(id).stream().anyMatch(version -> version.status() != MatrixStatus.DRAFT);
        if (matrix.status() != MatrixStatus.DRAFT || published || approvals.countApprovals(id) > 0) {
            throw new ConflictException("Only an unused draft matrix can be deleted");
        }
        approvals.deleteMatrix(id);
    }

    private ApprovalMatrixResponse activateLocked(UUID id, AuthenticatedUser actor) {
        ApprovalMatrix matrix = requireMatrix(id);
        MatrixVersion draft = approvals.findDraft(id).orElseThrow(() -> new ConflictException("There is no draft to activate"));
        if (matrix.currentVersionId() != null) {
            approvals.findVersion(matrix.currentVersionId())
                    .filter(version -> version.status() == MatrixStatus.ACTIVE)
                    .ifPresent(version -> approvals.supersedeVersion(version.id()));
        }
        Instant now = clock.instant();
        approvals.activateVersion(draft.id(), actor.getUsername(), now);
        approvals.updateMatrix(id, matrix.name(), matrix.description(), MatrixStatus.ACTIVE, draft.id(), actor.getUsername());
        return assemble(requireMatrix(id));
    }

    private DelegationResponse grantLocked(GrantDelegationRequest request, AuthenticatedUser actor) {
        ApprovalStage stage = ApprovalStage.parse(request.stage(), "stage");
        String role = request.roleCode().trim().toUpperCase(Locale.ROOT);
        if (!approvals.roleExists(role)) {
            throw ValidationException.field("roleCode", "Unknown role");
        }
        if (approvals.emailOf(request.userId()).isEmpty()) {
            throw ValidationException.field("userId", "Unknown user");
        }
        if (!approvals.userHasRole(request.userId(), role)) {
            throw ValidationException.field("userId", "The user does not hold this role");
        }
        if (request.branchId() != null) {
            if (!approvals.branchExists(request.branchId())) {
                throw ValidationException.field("branchId", "Unknown branch");
            }
            if (!approvals.userCoversBranch(request.userId(), request.branchId())) {
                throw ValidationException.field("branchId", "The user is not assigned to this branch");
            }
        }
        BigDecimal maxAmount = money(request.maxAmount(), "maxAmount", true);
        if (request.validUntil() != null && !request.validUntil().isAfter(request.validFrom())) {
            throw ValidationException.field("validUntil", "validUntil must be after validFrom");
        }
        ApprovalDelegation delegation = new ApprovalDelegation(
                UUID.randomUUID(),
                request.userId(),
                null,
                role,
                stage,
                request.branchId(),
                maxAmount,
                "NGN",
                request.validFrom(),
                request.validUntil(),
                true,
                request.reason().trim(),
                actor.getId(),
                clock.instant());
        approvals.insertDelegation(delegation);
        return views.delegation(approvals.findDelegation(delegation.id()).orElseThrow());
    }

    private ApprovalMatrixResponse assemble(ApprovalMatrix matrix) {
        List<com.mfb.lending.module.approval.dto.MatrixVersionResponse> versions = approvals.listVersions(matrix.id()).stream()
                .map(version -> views.version(version, approvals.listLevels(version.id())))
                .toList();
        return views.matrix(matrix, versions);
    }

    private ApprovalMatrix requireMatrix(UUID id) {
        return approvals.findMatrix(id).orElseThrow(() -> new ResourceNotFoundException("Approval matrix"));
    }

    private Checked check(SaveApprovalMatrixRequest request) {
        String name = request.name().trim();
        String description = request.description() == null || request.description().isBlank() ? null : request.description().trim();
        if (description != null && description.length() > 2000) {
            throw ValidationException.field("description", "description must be at most 2000 characters");
        }
        BigDecimal amountMin = money(request.amountMin(), "amountMin", true);
        BigDecimal amountMax = money(request.amountMax(), "amountMax", false);
        if (amountMax != null && amountMax.compareTo(amountMin) < 0) {
            throw ValidationException.field("amountMax", "amountMax must be at least amountMin");
        }
        if (request.productId() != null && !approvals.productExists(request.productId())) {
            throw ValidationException.field("productId", "Unknown loan product");
        }
        if (request.branchId() != null && !approvals.branchExists(request.branchId())) {
            throw ValidationException.field("branchId", "Unknown branch");
        }
        List<LevelRequest> levels = request.levels();
        Set<Integer> sequences = new HashSet<>();
        Set<String> stages = new HashSet<>();
        boolean coversCeiling = false;
        for (LevelRequest level : levels) {
            ApprovalStage stage = ApprovalStage.parse(level.stage(), "stage");
            if (!stages.add(stage.name())) {
                throw ValidationException.field("stage", "Each stage can appear once in a version");
            }
            if (level.sequence() < 1 || !sequences.add(level.sequence())) {
                throw ValidationException.field("sequence", "Level sequences must be unique and start at 1 or higher");
            }
            String role = level.roleCode().trim().toUpperCase(Locale.ROOT);
            if (!approvals.roleExists(role)) {
                throw ValidationException.field("roleCode", "Unknown role");
            }
            if (level.quorum() < 1) {
                throw ValidationException.field("quorum", "quorum must be at least 1");
            }
            if (stage == ApprovalStage.CREDIT_COMMITTEE && level.quorum() < 2) {
                throw ValidationException.field("quorum", "A credit committee needs at least two votes");
            }
            if (level.expiryHours() < 0 || level.expiryHours() > 8760) {
                throw ValidationException.field("expiryHours", "expiryHours must be between 0 and 8760");
            }
            if (level.manualRequired() && level.automated()) {
                throw ValidationException.field("automated", "A level cannot be both manual and automated");
            }
            BigDecimal min = money(level.minAmount(), "minAmount", false);
            BigDecimal max = money(level.maxAmount(), "maxAmount", false);
            if (min != null && max != null && max.compareTo(min) < 0) {
                throw ValidationException.field("maxAmount", "maxAmount must be at least minAmount");
            }
            if (max == null || (amountMax != null && max.compareTo(amountMax) >= 0)) {
                coversCeiling = true;
            }
        }
        if (!coversCeiling) {
            throw ValidationException.field("levels", "A level must cover the top of the matrix amount band");
        }
        return new Checked(
                name,
                description,
                blankToNull(request.customerSegment(), SEGMENTS, "customerSegment"),
                blankToNull(request.riskRating(), RISKS, "riskRating"),
                blankToNull(request.collateralStatus(), COLLATERAL, "collateralStatus"),
                blankToNull(request.securityClassification(), SECURITY, "securityClassification"),
                exceptionType(request.exceptionType()),
                request.productId(),
                request.branchId(),
                request.automatedAllowed(),
                amountMin,
                amountMax,
                levels);
    }

    private List<ApprovalLevel> levels(UUID versionId, List<LevelRequest> requests) {
        return requests.stream()
                .map(level -> new ApprovalLevel(
                        UUID.randomUUID(),
                        versionId,
                        ApprovalStage.parse(level.stage(), "stage"),
                        level.sequence(),
                        level.roleCode().trim().toUpperCase(Locale.ROOT),
                        money(level.minAmount(), "minAmount", false),
                        money(level.maxAmount(), "maxAmount", false),
                        level.quorum(),
                        level.expiryHours(),
                        level.manualRequired(),
                        level.automated(),
                        level.escalateOnExpiry()))
                .toList();
    }

    private MatrixVersion version(
            UUID id,
            UUID matrixId,
            int number,
            MatrixStatus status,
            Checked checked,
            String activatedBy,
            Instant activatedAt,
            String createdBy) {
        return new MatrixVersion(
                id,
                matrixId,
                number,
                status,
                checked.productId(),
                checked.segment(),
                checked.branchId(),
                checked.risk(),
                checked.collateral(),
                checked.security(),
                checked.exceptionType(),
                checked.automated(),
                checked.amountMin(),
                checked.amountMax(),
                activatedBy,
                activatedAt,
                clock.instant(),
                createdBy);
    }

    private static String code(String value) {
        String code = value.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(code).matches()) {
            throw ValidationException.field("code", "code must be 2 to 40 uppercase letters, digits, or underscores");
        }
        return code;
    }

    private static String exceptionType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String type = value.trim().toUpperCase(Locale.ROOT);
        if (!CODE.matcher(type).matches()) {
            throw ValidationException.field("exceptionType", "exceptionType must be uppercase letters, digits, or underscores");
        }
        return type;
    }

    private static String blankToNull(String value, Set<String> allowed, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String token = value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(token)) {
            throw ValidationException.field(field, "Unknown " + field);
        }
        return token;
    }

    private static BigDecimal money(BigDecimal value, String field, boolean required) {
        if (value == null) {
            if (required) {
                throw ValidationException.field(field, field + " is required");
            }
            return null;
        }
        if (value.signum() < 0 || value.scale() > 2) {
            throw ValidationException.field(field, field + " must be a non-negative amount with at most two decimal places");
        }
        return value;
    }

    private record Checked(
            String name,
            String description,
            String segment,
            String risk,
            String collateral,
            String security,
            String exceptionType,
            UUID productId,
            UUID branchId,
            boolean automated,
            BigDecimal amountMin,
            BigDecimal amountMax,
            List<LevelRequest> levels
    ) {
    }
}
