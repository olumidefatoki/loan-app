package com.mfb.lending.module.customer.document;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.config.CustomerProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class DocumentInspector {

    private final long maxBytes;
    private final MalwareScanner scanner;

    public DocumentInspector(CustomerProperties properties, MalwareScanner scanner) {
        this.maxBytes = properties.getDocuments().getMaxBytes();
        this.scanner = scanner;
    }

    public InspectedDocument inspect(String fileName, String declaredContentType, byte[] body) {
        if (body == null || body.length == 0) {
            reject("A document is required");
        }
        if (body.length > maxBytes) {
            reject("Document exceeds the size limit");
        }
        String detected = detect(body);
        if (detected == null) {
            reject("Only PDF, JPEG, and PNG documents are accepted");
        }
        if (declaredContentType != null && !declaredContentType.isBlank()) {
            String declared = declaredContentType.toLowerCase(Locale.ROOT);
            int separator = declared.indexOf(';');
            if (separator >= 0) {
                declared = declared.substring(0, separator).trim();
            }
            if (!detected.equals(declared)) {
                reject("The file content does not match its content type");
            }
        }
        scanner.scan(body, fileName);
        return new InspectedDocument(detected, sha256(body), body.length, safeName(fileName, detected));
    }

    static String detect(byte[] body) {
        if (startsWith(body, "%PDF".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) {
            return "application/pdf";
        }
        if (body.length >= 3 && (body[0] & 0xff) == 0xff && (body[1] & 0xff) == 0xd8 && (body[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        byte[] png = new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (startsWith(body, png)) {
            return "image/png";
        }
        return null;
    }

    private static boolean startsWith(byte[] body, byte[] prefix) {
        if (body.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (body[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    static String sha256(byte[] body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    static String safeName(String fileName, String contentType) {
        String base = fileName == null ? "" : fileName.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        if (slash >= 0) {
            base = base.substring(slash + 1);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]", "_");
        if (base.isBlank() || base.equals(".") || base.equals("..")) {
            base = switch (contentType) {
                case "image/jpeg" -> "document.jpg";
                case "image/png" -> "document.png";
                default -> "document.pdf";
            };
        }
        if (base.length() > 120) {
            base = base.substring(base.length() - 120);
        }
        return base;
    }

    private static void reject(String message) {
        throw new ApiException(ErrorCode.DOCUMENT_REJECTED, HttpStatus.BAD_REQUEST, message);
    }
}
