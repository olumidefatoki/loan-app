package com.mfb.lending.module.loanproduct.application;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ForbiddenException;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.domain.PermissionCodes;
import com.mfb.lending.module.auth.dto.PageResponse;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.loanproduct.domain.CustomerSegment;
import com.mfb.lending.module.loanproduct.domain.LoanProductRecord;
import com.mfb.lending.module.loanproduct.domain.LoanProductVersionRecord;
import com.mfb.lending.module.loanproduct.domain.ProductCategory;
import com.mfb.lending.module.loanproduct.domain.ProductComponents;
import com.mfb.lending.module.loanproduct.domain.ProductConfiguration;
import com.mfb.lending.module.loanproduct.domain.ProductStatus;
import com.mfb.lending.module.loanproduct.dto.CalculatorConfigResponse;
import com.mfb.lending.module.loanproduct.dto.CommercialTermsRequest;
import com.mfb.lending.module.loanproduct.dto.CreateLoanProductRequest;
import com.mfb.lending.module.loanproduct.dto.CreateLoanProductVersionRequest;
import com.mfb.lending.module.loanproduct.dto.LoanProductResponse;
import com.mfb.lending.module.loanproduct.dto.LoanProductSummaryResponse;
import com.mfb.lending.module.loanproduct.dto.LoanProductVersionResponse;
import com.mfb.lending.module.loanproduct.dto.ProductActionRequest;
import com.mfb.lending.module.loanproduct.persistence.LoanProductComponentRepository;
import com.mfb.lending.module.loanproduct.persistence.LoanProductRepository;
import com.mfb.lending.module.loanproduct.persistence.ProductSearch;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class LoanProductService {

    private final LoanProductRepository products;
    private final LoanProductComponentRepository components;
    private final AuditService audit;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public LoanProductService(
            LoanProductRepository products,
            LoanProductComponentRepository components,
            AuditService audit,
            Clock clock,
            TransactionTemplate transactions) {
        this.products = products;
        this.components = components;
        this.audit = audit;
        this.clock = clock;
        this.transactions = transactions;
    }

    public LoanProductResponse create(CreateLoanProductRequest request, AuthenticatedUser actor, ClientContext client) {
        assertPermission(actor, PermissionCodes.LOAN_PRODUCT_MANAGE);
        ProductCategory category = category(request.category());
        ProductConfiguration terms = ProductConfigurationRules.normalise(request.terms(), category);
        UUID id = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        String actorId = actor.getId().toString();
        try {
            transactions.executeWithoutResult(status -> {
                products.insertProduct(new LoanProductRecord(
                        id, request.productCode(), category, ProductStatus.DRAFT, null, null, null, actorId, actorId));
                products.insertVersion(LoanProductVersionRecord.draft(versionId, id, 1, terms, null, actorId));
                components.replace(versionId, terms);
                products.updateProduct(id, ProductStatus.DRAFT, versionId, actorId);
            });
        } catch (DuplicateKeyException ex) {
            throw new ConflictException("A loan product with this code already exists");
        }
        audit(actor, client, "LOAN_PRODUCT_CREATED", id, null, snapshot(request.productCode(), ProductStatus.DRAFT, 1, terms.interestRate().toPlainString()));
        return load(id);
    }

    public PageResponse<LoanProductSummaryResponse> list(
            AuthenticatedUser actor,
            String status,
            String category,
            String segment,
            int page,
            int size) {
        if (page < 0 || page > 10_000) {
            throw ValidationException.field("page", "page must be between 0 and 10000");
        }
        if (size < 1 || size > 100) {
            throw ValidationException.field("size", "size must be between 1 and 100");
        }
        boolean offeredOnly = !canViewUnpublished(actor);
        String statusFilter = enumFilter(ProductStatus.class, status, "status");
        String categoryFilter = enumFilter(ProductCategory.class, category, "category");
        String segmentFilter = enumFilter(CustomerSegment.class, segment, "customerSegment");
        if (offeredOnly && statusFilter != null && !ProductStatus.ACTIVE.name().equals(statusFilter)) {
            return new PageResponse<>(List.of(), page, size, 0);
        }
        ProductSearch search = new ProductSearch(
                offeredOnly ? null : statusFilter,
                categoryFilter,
                segmentFilter,
                offeredOnly,
                clock.instant(),
                size,
                page * size);
        List<LoanProductSummaryResponse> items = products.search(search).stream().map(LoanProductViews::summary).toList();
        return new PageResponse<>(items, page, size, products.count(search));
    }

    public LoanProductResponse get(UUID id, AuthenticatedUser actor) {
        LoanProductResponse response = load(id);
        if (!canViewUnpublished(actor) && !offered(response)) {
            throw new ResourceNotFoundException("Loan product");
        }
        return response;
    }

    public LoanProductResponse update(UUID id, CommercialTermsRequest request, AuthenticatedUser actor, ClientContext client) {
        assertPermission(actor, PermissionCodes.LOAN_PRODUCT_MANAGE);
        String actorId = actor.getId().toString();
        Revision revision = transactions.execute(status -> {
            LoanProductRecord product = lock(id);
            assertEditable(product);
            LoanProductVersionRecord version = openVersion(product);
            if (version.status() == ProductStatus.APPROVED) {
                throw ValidationException.field("status", "An approved version can only be activated");
            }
            boolean reopened = false;
            if (version.status() == ProductStatus.PENDING_APPROVAL) {
                if (!ProductWorkflow.submitterMayRevise(version.status(), version.submittedBy(), actorId)) {
                    throw new ForbiddenException("Only the submitter can change a version that is awaiting approval");
                }
                products.reopenDraft(version.id(), actorId);
                if (product.status() == ProductStatus.PENDING_APPROVAL) {
                    products.updateProduct(product.id(), ProductStatus.DRAFT, product.currentVersionId(), actorId);
                }
                reopened = true;
            }
            ProductConfiguration terms = ProductConfigurationRules.normalise(request, product.category());
            LoanProductVersionRecord draft = LoanProductVersionRecord.draft(
                    version.id(), version.productId(), version.versionNumber(), terms, version.changeReason(), actorId);
            if (products.updateDraft(draft) != 1) {
                throw new ConflictException("The draft version changed before it could be saved");
            }
            components.replace(version.id(), terms);
            return new Revision(version.interestRate().toPlainString(), terms.interestRate().toPlainString(), version.versionNumber(), reopened);
        });
        audit(actor, client, "LOAN_PRODUCT_UPDATED", id,
                Map.of("interestRate", revision.previousRate(), "versionNumber", revision.versionNumber()),
                Map.of("interestRate", revision.rate(), "versionNumber", revision.versionNumber(), "reopened", revision.reopened()));
        return load(id);
    }

    public LoanProductResponse openVersion(UUID id, CreateLoanProductVersionRequest request, AuthenticatedUser actor, ClientContext client) {
        assertPermission(actor, PermissionCodes.LOAN_PRODUCT_MANAGE);
        String actorId = actor.getId().toString();
        String reason = blankToNull(request.changeReason());
        Opened opened = transactions.execute(status -> {
            LoanProductRecord product = lock(id);
            assertEditable(product);
            products.findUnpublished(id).ifPresent(existing -> {
                throw new ConflictException(existing.status() == ProductStatus.DRAFT
                        ? "A draft version is already open"
                        : "A product version is already awaiting approval");
            });
            ProductConfiguration terms = ProductConfigurationRules.normalise(request.terms(), product.category());
            int versionNumber = products.nextVersionNumber(id);
            UUID versionId = UUID.randomUUID();
            products.insertVersion(LoanProductVersionRecord.draft(versionId, id, versionNumber, terms, reason, actorId));
            components.replace(versionId, terms);
            return new Opened(versionNumber, terms.interestRate().toPlainString());
        });
        audit(actor, client, "LOAN_PRODUCT_VERSION_CREATED", id, null,
                Map.of("versionNumber", opened.versionNumber(), "interestRate", opened.rate()));
        return load(id);
    }

    public List<LoanProductVersionResponse> versions(UUID id, AuthenticatedUser actor) {
        if (!canViewUnpublished(actor)) {
            throw new ForbiddenException("You do not have permission to view product history");
        }
        if (products.findProduct(id).isEmpty()) {
            throw new ResourceNotFoundException("Loan product");
        }
        return products.listVersions(id).stream()
                .map(version -> LoanProductViews.version(version, components.load(version.id())))
                .toList();
    }

    public LoanProductResponse submit(UUID id, AuthenticatedUser actor, ClientContext client) {
        assertPermission(actor, PermissionCodes.LOAN_PRODUCT_MANAGE);
        String actorId = actor.getId().toString();
        transactions.executeWithoutResult(status -> {
            LoanProductRecord product = lock(id);
            assertEditable(product);
            LoanProductVersionRecord version = openVersion(product);
            ProductWorkflow.assertSubmittable(version.status());
            products.markSubmitted(version.id(), actorId, clock.instant());
            if (product.status() == ProductStatus.DRAFT) {
                products.updateProduct(product.id(), ProductStatus.PENDING_APPROVAL, product.currentVersionId(), actorId);
            }
        });
        audit(actor, client, "LOAN_PRODUCT_SUBMITTED", id, null, Map.of("submittedBy", actorId));
        return load(id);
    }

    public LoanProductResponse approve(UUID id, AuthenticatedUser actor, ClientContext client) {
        assertPermission(actor, PermissionCodes.LOAN_PRODUCT_APPROVE);
        String actorId = actor.getId().toString();
        transactions.executeWithoutResult(status -> {
            LoanProductRecord product = lock(id);
            assertEditable(product);
            LoanProductVersionRecord version = openVersion(product);
            ProductWorkflow.assertApprovable(version.status());
            ProductWorkflow.assertDistinctChecker(version.submittedBy(), actorId, "approve this product");
            products.markApproved(version.id(), actorId, clock.instant());
            if (product.status() != ProductStatus.ACTIVE && product.status() != ProductStatus.SUSPENDED) {
                products.updateProduct(product.id(), ProductStatus.APPROVED, version.id(), actorId);
            }
        });
        audit(actor, client, "LOAN_PRODUCT_APPROVED", id, null, Map.of("approvedBy", actorId));
        return load(id);
    }

    public LoanProductResponse activate(UUID id, ProductActionRequest request, AuthenticatedUser actor, ClientContext client) {
        assertPermission(actor, PermissionCodes.LOAN_PRODUCT_APPROVE);
        String actorId = actor.getId().toString();
        Instant effectiveFrom = effectiveFrom(request);
        transactions.executeWithoutResult(status -> {
            LoanProductRecord product = lock(id);
            if (product.status() == ProductStatus.DEACTIVATED) {
                throw ValidationException.field("status", "This product is already deactivated");
            }
            LoanProductVersionRecord current = requiredVersion(product.currentVersionId());
            LoanProductVersionRecord unpublished = products.findUnpublished(id).orElse(null);
            LoanProductVersionRecord target;
            if (unpublished != null && unpublished.status() == ProductStatus.APPROVED) {
                target = unpublished;
            } else if (current.status() == ProductStatus.SUSPENDED) {
                target = current;
                ProductWorkflow.assertReactivatable(current.status());
            } else {
                ProductWorkflow.assertActivatable(unpublished == null ? current.status() : unpublished.status());
                target = current;
            }
            ProductWorkflow.assertDistinctChecker(target.submittedBy(), actorId, "activate this product");
            Instant starts = effectiveFrom;
            if (!current.id().equals(target.id())
                    && (current.status() == ProductStatus.ACTIVE || current.status() == ProductStatus.SUSPENDED)) {
                if (current.effectiveFrom() != null && effectiveFrom.isBefore(current.effectiveFrom())) {
                    throw ValidationException.field("effectiveFrom", "Effective date must be on or after the current version");
                }
                products.markSuperseded(current.id(), effectiveFrom, actorId);
            } else if (target.status() == ProductStatus.SUSPENDED && target.effectiveFrom() != null) {
                starts = target.effectiveFrom();
            }
            products.markActivated(target.id(), actorId, starts, clock.instant());
            products.updateProduct(product.id(), ProductStatus.ACTIVE, target.id(), actorId);
        });
        audit(actor, client, "LOAN_PRODUCT_ACTIVATED", id, null, Map.of("activatedBy", actorId, "effectiveFrom", effectiveFrom.toString()));
        return load(id);
    }

    public LoanProductResponse suspend(UUID id, ProductActionRequest request, AuthenticatedUser actor, ClientContext client) {
        assertPermission(actor, PermissionCodes.LOAN_PRODUCT_APPROVE);
        String reason = requiredReason(request);
        String actorId = actor.getId().toString();
        transactions.executeWithoutResult(status -> {
            LoanProductRecord product = lock(id);
            ProductWorkflow.assertSuspendable(product.status());
            LoanProductVersionRecord current = requiredVersion(product.currentVersionId());
            ProductWorkflow.assertSuspendable(current.status());
            ProductWorkflow.assertDistinctChecker(current.submittedBy(), actorId, "suspend this product");
            products.markSuspended(current.id(), actorId, clock.instant());
            products.updateProduct(product.id(), ProductStatus.SUSPENDED, current.id(), actorId);
        });
        audit(actor, client, "LOAN_PRODUCT_SUSPENDED", id, Map.of("status", ProductStatus.ACTIVE.name()), Map.of("reason", reason));
        return load(id);
    }

    public LoanProductResponse deactivate(UUID id, ProductActionRequest request, AuthenticatedUser actor, ClientContext client) {
        assertPermission(actor, PermissionCodes.LOAN_PRODUCT_APPROVE);
        String reason = requiredReason(request);
        String actorId = actor.getId().toString();
        transactions.executeWithoutResult(status -> {
            LoanProductRecord product = lock(id);
            ProductWorkflow.assertDeactivatable(product.status());
            LoanProductVersionRecord current = requiredVersion(product.currentVersionId());
            ProductWorkflow.assertDistinctChecker(current.submittedBy(), actorId, "deactivate this product");
            Instant at = clock.instant();
            products.markDeactivated(current.id(), actorId, at);
            products.findUnpublished(id)
                    .filter(version -> !version.id().equals(current.id()))
                    .ifPresent(version -> {
                        ProductWorkflow.assertDistinctChecker(version.submittedBy(), actorId, "deactivate this product");
                        products.markDeactivated(version.id(), actorId, at);
                    });
            products.updateProduct(product.id(), ProductStatus.DEACTIVATED, current.id(), actorId);
        });
        audit(actor, client, "LOAN_PRODUCT_DEACTIVATED", id, null, Map.of("reason", reason));
        return load(id);
    }

    public CalculatorConfigResponse calculator(UUID id, AuthenticatedUser actor) {
        if (actor == null) {
            throw new ForbiddenException("You do not have permission to perform this action");
        }
        LoanProductRecord product = products.findProduct(id).orElseThrow(() -> new ResourceNotFoundException("Loan product"));
        LoanProductVersionRecord version = requiredVersion(product.currentVersionId());
        if (!ProductConfigurationRules.offeredToCustomers(
                product.status(), version.status(), version.effectiveFrom(), version.effectiveTo(), clock.instant())) {
            throw new ResourceNotFoundException("Loan product");
        }
        return LoanProductViews.calculator(product, version, components.load(version.id()).fees());
    }

    private LoanProductResponse load(UUID id) {
        LoanProductRecord product = products.findProduct(id).orElseThrow(() -> new ResourceNotFoundException("Loan product"));
        LoanProductVersionRecord version = requiredVersion(product.currentVersionId());
        LoanProductVersionRecord pending = products.findUnpublished(id)
                .filter(candidate -> !candidate.id().equals(version.id()))
                .orElse(null);
        return LoanProductViews.detail(product, version, components.load(version.id()), pending);
    }

    private LoanProductRecord lock(UUID id) {
        return products.lockProduct(id).orElseThrow(() -> new ResourceNotFoundException("Loan product"));
    }

    private LoanProductVersionRecord requiredVersion(UUID id) {
        return products.findVersion(id).orElseThrow(() -> new ResourceNotFoundException("Loan product version"));
    }

    private LoanProductVersionRecord openVersion(LoanProductRecord product) {
        return products.findUnpublished(product.id())
                .orElseThrow(() -> ValidationException.field("status", "Create a new version before changing this product"));
    }

    private void assertEditable(LoanProductRecord product) {
        if (product.status() == ProductStatus.DEACTIVATED) {
            throw ValidationException.field("status", "This product is already deactivated");
        }
    }

    private boolean offered(LoanProductResponse response) {
        LoanProductVersionResponse version = response.currentVersion();
        return ProductConfigurationRules.offeredToCustomers(
                ProductStatus.valueOf(response.status()),
                ProductStatus.valueOf(version.status()),
                version.effectiveFrom(),
                version.effectiveTo(),
                clock.instant());
    }

    private Instant effectiveFrom(ProductActionRequest request) {
        Instant now = clock.instant();
        Instant value = request == null || request.effectiveFrom() == null ? now : request.effectiveFrom();
        if (value.isBefore(now.minus(5, ChronoUnit.MINUTES))) {
            throw ValidationException.field("effectiveFrom", "Effective date must be the current time or later");
        }
        if (value.isAfter(now.plus(366, ChronoUnit.DAYS))) {
            throw ValidationException.field("effectiveFrom", "Effective date must fall within 366 days");
        }
        return value;
    }

    private static String requiredReason(ProductActionRequest request) {
        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw ValidationException.field("reason", "reason is required");
        }
        String reason = request.reason().trim();
        if (reason.length() > 500) {
            throw ValidationException.field("reason", "reason must be at most 500 characters");
        }
        return reason;
    }

    private static void assertPermission(AuthenticatedUser actor, String permission) {
        if (!actor.permissions().contains(permission)) {
            throw new ForbiddenException("You do not have permission to perform this action");
        }
    }

    private static boolean canViewUnpublished(AuthenticatedUser actor) {
        return actor.permissions().contains(PermissionCodes.LOAN_PRODUCT_VIEW)
                || actor.permissions().contains(PermissionCodes.LOAN_PRODUCT_MANAGE)
                || actor.permissions().contains(PermissionCodes.LOAN_PRODUCT_APPROVE);
    }

    private static ProductCategory category(String raw) {
        if (raw == null || raw.isBlank()) {
            throw ValidationException.field("category", "category is required");
        }
        try {
            return ProductCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field("category", "Unsupported category");
        }
    }

    private static <E extends Enum<E>> String enumFilter(Class<E> type, String raw, String field) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw ValidationException.field(field, "Unsupported " + field);
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static Map<String, Object> snapshot(String code, ProductStatus status, int versionNumber, String interestRate) {
        return Map.of("productCode", code, "status", status.name(), "versionNumber", versionNumber, "interestRate", interestRate);
    }

    private void audit(
            AuthenticatedUser actor,
            ClientContext client,
            String action,
            UUID id,
            Map<String, Object> before,
            Map<String, Object> after) {
        audit.record(action, "loan_product", id.toString(), actor.getId().toString(), client, before, after);
    }

    private record Revision(String previousRate, String rate, int versionNumber, boolean reopened) {
    }

    private record Opened(int versionNumber, String rate) {
    }
}
