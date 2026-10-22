package vn.com.coreacademy.knowledge.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.exception.KnowledgeResponseCode;

/** Uploaded files live under app.knowledge.storage-dir with a UUID name; the client's file name is never a path. */
@Component
public class KnowledgeFileStore {
    private static final String KEY_PATTERN = "[a-f0-9-]{36}";

    private final Path root;

    public KnowledgeFileStore(KnowledgeProperties properties) {
        this.root = properties.storageDir().toAbsolutePath().normalize();
    }

    /** Writes the upload and returns its storage key. */
    public String store(MultipartFile file) {
        String key = UUID.randomUUID().toString();
        try {
            Files.createDirectories(root);
            file.transferTo(resolve(key));
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot store knowledge file", exception);
        }

        return key;
    }

    public Path resolve(String key) {
        if (key == null || !key.matches(KEY_PATTERN)) {
            throw new BusinessException(KnowledgeResponseCode.FILE_INVALID);
        }

        return root.resolve(key);
    }

    public void discard(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot delete knowledge file", exception);
        }
    }
}
