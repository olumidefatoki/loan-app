package com.mfb.lending.module.customer.document;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

import com.mfb.lending.common.exception.ApiException;
import com.mfb.lending.common.exception.ErrorCode;
import com.mfb.lending.config.CustomerProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class LocalPrivateObjectStorage implements ObjectStorage {

    private static final Set<PosixFilePermission> OWNER_ONLY = EnumSet.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);

    private final Path root;

    public LocalPrivateObjectStorage(CustomerProperties properties) {
        this.root = Path.of(properties.getDocuments().getDirectory()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            ownerOnly(root);
        } catch (IOException ex) {
            throw new IllegalStateException("Customer document directory could not be created", ex);
        }
    }

    @Override
    public void put(String key, byte[] body) {
        Path path = resolve(key);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, body);
            ownerOnly(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Document could not be stored", ex);
        }
    }

    @Override
    public byte[] get(String key) {
        Path path = resolve(key);
        if (!Files.isRegularFile(path)) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "The document is not available");
        }
        try {
            return Files.readAllBytes(path);
        } catch (IOException ex) {
            throw new ApiException(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "The document is not available");
        }
    }

    @Override
    public void delete(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException ex) {
            throw new IllegalStateException("Document could not be removed", ex);
        }
    }

    private Path resolve(String key) {
        if (key == null || key.isBlank() || key.contains("..") || key.startsWith("/") || key.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Invalid document key");
        }
        Path path = root.resolve(key).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("Invalid document key");
        }
        return path;
    }

    private static void ownerOnly(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, path.toFile().isDirectory()
                    ? OWNER_ONLY
                    : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ex) {
            path.toFile().setReadable(false, false);
            path.toFile().setWritable(false, false);
            path.toFile().setReadable(true, true);
            path.toFile().setWritable(true, true);
        }
    }
}
