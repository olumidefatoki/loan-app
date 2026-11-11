package com.mfb.lending.module.loanapplication.application;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ConflictException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.common.exception.ValidationException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.document.DocumentInspector;
import com.mfb.lending.module.customer.document.InspectedDocument;
import com.mfb.lending.module.customer.document.ObjectStorage;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import com.mfb.lending.module.loanapplication.domain.ApplicationDocumentRecord;
import com.mfb.lending.module.loanapplication.domain.ApplicationRecord;
import com.mfb.lending.module.loanapplication.dto.ApplicationDocumentResponse;
import com.mfb.lending.module.loanapplication.persistence.LoanApplicationRepository;
import com.mfb.lending.module.loanproduct.domain.ProductDocumentRequirement;
import com.mfb.lending.module.loanproduct.persistence.LoanProductComponentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class LoanApplicationDocumentService {

    private static final String LOCKED =
            "Submitted applications cannot be modified. Ask for the application to be returned for correction.";

    private final LoanApplicationRepository applications;
    private final LoanProductComponentRepository components;
    private final CustomerRepository customers;
    private final DocumentInspector inspector;
    private final ObjectStorage storage;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public LoanApplicationDocumentService(
            LoanApplicationRepository applications,
            LoanProductComponentRepository components,
            CustomerRepository customers,
            DocumentInspector inspector,
            ObjectStorage storage,
            AuditService audit,
            TransactionTemplate transactions) {
        this.applications = applications;
        this.components = components;
        this.customers = customers;
        this.inspector = inspector;
        this.storage = storage;
        this.audit = audit;
        this.transactions = transactions;
    }

    public ApplicationDocumentResponse upload(
            UUID applicationId,
            String documentCode,
            MultipartFile file,
            AuthenticatedUser actor,
            ClientContext client) {
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.DOCUMENT_REJECTED, HttpStatus.BAD_REQUEST, "A document is required");
        }
        String code = code(documentCode);
        byte[] body = bytes(file);
        InspectedDocument inspected = inspector.inspect(file.getOriginalFilename(), file.getContentType(), body);
        UUID documentId = UUID.randomUUID();
        String key = "loan-applications/" + applicationId + "/" + documentId;
        storage.put(key, body);
        Stored stored;
        try {
            stored = transactions.execute(status -> store(applicationId, code, documentId, key, inspected, actor));
        } catch (RuntimeException ex) {
            storage.delete(key);
            throw ex;
        }
        if (stored.previousKey() != null) {
            storage.delete(stored.previousKey());
        }
        audit.record("LOAN_APPLICATION_DOCUMENT_STORED", "loan_application", applicationId.toString(), actor.getUsername(), client, Map.of(
                "documentId", documentId.toString(),
                "documentCode", code,
                "checksumSha256", inspected.checksumSha256()));
        return stored.response();
    }

    public List<ApplicationDocumentResponse> list(UUID applicationId, AuthenticatedUser actor) {
        ApplicationRecord application = applications.find(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        CustomerRecord customer = customers.findById(application.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        ApplicationAccess.assertCanView(actor, application, customer);
        return applications.listDocuments(applicationId).stream().map(LoanApplicationDocumentService::response).toList();
    }

    private Stored store(
            UUID applicationId,
            String code,
            UUID documentId,
            String key,
            InspectedDocument inspected,
            AuthenticatedUser actor) {
        ApplicationRecord application = applications.lock(applicationId)
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        CustomerRecord customer = customers.findById(application.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Loan application"));
        ApplicationAccess.assertCanMutate(actor, application, customer);
        if (!ApplicationWorkflow.editable(application.status(), application.correctionOpen())) {
            throw new ConflictException(LOCKED);
        }
        String documentName = documentName(application.productVersionId(), code);
        Optional<String> previous = applications.supersedeDocument(applicationId, code);
        ApplicationDocumentRecord document = new ApplicationDocumentRecord(
                documentId,
                applicationId,
                code,
                documentName,
                inspected.fileName(),
                inspected.contentType(),
                inspected.sizeBytes(),
                inspected.checksumSha256(),
                key,
                null);
        applications.insertDocument(document, actor.getUsername());
        int revision = application.revisionNumber();
        if (application.correctionOpen()) {
            revision = revision + 1;
            applications.update(application.withRevision(revision, actor.getUsername()));
        }
        applications.insertEvent(UUID.randomUUID(), applicationId, application.correctionOpen() ? "REVISED" : "DOCUMENT_UPLOADED",
                "{\"documentCode\":\"" + code + "\",\"revision\":" + revision + "}", actor.getId());
        ApplicationDocumentRecord saved = applications.listDocuments(applicationId).stream()
                .filter(item -> item.id().equals(documentId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Document"));
        return new Stored(response(saved), previous.orElse(null));
    }

    private String documentName(UUID versionId, String code) {
        List<ProductDocumentRequirement> requirements = components.load(versionId).documents();
        for (ProductDocumentRequirement requirement : requirements) {
            if (requirement.documentCode().equals(code)) {
                return requirement.documentName();
            }
        }
        if (!requirements.isEmpty()) {
            throw ValidationException.field("documentCode", "This product does not ask for " + code);
        }
        return code;
    }

    private static String code(String value) {
        if (value == null || value.isBlank()) {
            throw ValidationException.field("documentCode", "documentCode is required");
        }
        String code = value.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("^[A-Z][A-Z0-9_]{0,39}$")) {
            throw ValidationException.field("documentCode", "documentCode must be an uppercase code");
        }
        return code;
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.DOCUMENT_REJECTED, HttpStatus.BAD_REQUEST, "The document could not be read");
        }
    }

    private static ApplicationDocumentResponse response(ApplicationDocumentRecord document) {
        return new ApplicationDocumentResponse(
                document.id(),
                document.applicationId(),
                document.documentCode(),
                document.documentName(),
                document.fileName(),
                document.contentType(),
                document.sizeBytes(),
                document.checksumSha256(),
                document.uploadedAt());
    }

    private record Stored(ApplicationDocumentResponse response, String previousKey) {
    }
}
