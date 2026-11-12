package vn.com.coreacademy.knowledge.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.io.InputStream;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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

    /** SHA-256 (hex) of the stored file, read in blocks so a large file is never held in memory. */
    public String sha256(String key) {
        try (InputStream in = Files.newInputStream(resolve(key))) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] block = new byte[8192];
            for (int read = in.read(block); read >= 0; read = in.read(block)) {
                digest.update(block, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot read knowledge file", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is always available", exception);
        }
    }

    public void discard(String key) {
        try {
            Files.deleteIfExists(resolve(key));
        } catch (IOException exception) {
            throw new UncheckedIOException("Cannot delete knowledge file", exception);
        }
    }
}
