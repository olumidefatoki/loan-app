package com.mfb.lending.module.customer.application;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.common.exception.ResourceNotFoundException;
import com.mfb.lending.module.audit.application.AuditService;
import com.mfb.lending.module.auth.domain.ClientContext;
import com.mfb.lending.module.auth.security.AuthenticatedUser;
import com.mfb.lending.module.customer.document.DocumentInspector;
import com.mfb.lending.module.customer.document.InspectedDocument;
import com.mfb.lending.module.customer.document.ObjectStorage;
import com.mfb.lending.module.customer.domain.CustomerRecord;
import com.mfb.lending.module.customer.domain.DocumentRecord;
import com.mfb.lending.module.customer.domain.DocumentType;
import com.mfb.lending.module.customer.dto.DocumentResponse;
import com.mfb.lending.module.customer.persistence.CustomerDocumentRepository;
import com.mfb.lending.module.customer.persistence.CustomerRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;

@Service
public class CustomerDocumentService {

    private final CustomerRepository customers;
    private final CustomerDocumentRepository documents;
    private final DocumentInspector inspector;
    private final ObjectStorage storage;
    private final AuditService audit;
    private final TransactionTemplate transactions;

    public CustomerDocumentService(
            CustomerRepository customers,
            CustomerDocumentRepository documents,
            DocumentInspector inspector,
            ObjectStorage storage,
            AuditService audit,
            TransactionTemplate transactions) {
        this.customers = customers;
        this.documents = documents;
        this.inspector = inspector;
        this.storage = storage;
        this.audit = audit;
        this.transactions = transactions;
    }

    public DocumentResponse upload(UUID customerId, DocumentType type, MultipartFile file, AuthenticatedUser actor, ClientContext client) {
        require(customerId, actor, true);
        if (file == null || file.isEmpty()) {
            throw new ApiException(ErrorCode.DOCUMENT_REJECTED, HttpStatus.BAD_REQUEST, "A document is required");
        }
        byte[] body = bytes(file);
        InspectedDocument inspected = inspector.inspect(file.getOriginalFilename(), file.getContentType(), body);
        UUID documentId = UUID.randomUUID();
        String key = "customers/" + customerId + "/" + documentId;
        storage.put(key, body);
        DocumentRecord document = new DocumentRecord(
                documentId,
                customerId,
                type,
                inspected.fileName(),
                inspected.contentType(),
                inspected.sizeBytes(),
                inspected.checksumSha256(),
                key,
                null);
        try {
            transactions.executeWithoutResult(status -> {
                require(customerId, actor, true);
                documents.insert(document, actor.getUsername());
            });
        } catch (RuntimeException ex) {
            storage.delete(key);
            throw ex;
        }
        audit.record("CUSTOMER_DOCUMENT_STORED", "customer", customerId.toString(), actor.getUsername(), client, Map.of(
                "documentId", documentId.toString(),
                "documentType", type.name(),
                "checksumSha256", inspected.checksumSha256()));
        return response(documents.find(customerId, documentId).orElseThrow(() -> new ResourceNotFoundException("Document")));
    }

    public List<DocumentResponse> list(UUID customerId, AuthenticatedUser actor) {
        require(customerId, actor, false);
        return documents.list(customerId).stream().map(CustomerDocumentService::response).toList();
    }

    public DocumentRecord download(UUID customerId, UUID documentId, AuthenticatedUser actor) {
        require(customerId, actor, false);
        return documents.find(customerId, documentId).orElseThrow(() -> new ResourceNotFoundException("Document"));
    }

    public byte[] content(DocumentRecord document) {
        return storage.get(document.storageKey());
    }

    private CustomerRecord require(UUID customerId, AuthenticatedUser actor, boolean mutate) {
        CustomerRecord customer = customers.findById(customerId).orElseThrow(() -> new ResourceNotFoundException("Customer"));
        if (mutate) {
            CustomerAccess.assertCanMutate(actor, customer);
        } else {
            CustomerAccess.assertCanView(actor, customer);
        }
        return customer;
    }

    private static byte[] bytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.DOCUMENT_REJECTED, HttpStatus.BAD_REQUEST, "The document could not be read");
        }
    }

    private static DocumentResponse response(DocumentRecord document) {
        return new DocumentResponse(
                document.id(),
                document.type().name(),
                document.fileName(),
                document.contentType(),
                document.sizeBytes(),
                document.checksumSha256(),
                "CLEAN",
                document.createdAt());
    }
}
