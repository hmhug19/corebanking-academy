package vn.com.coreacademy.storage.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Tạo file tạm và SHA-256 theo stream; không quyết định định dạng hay giới hạn nghiệp vụ. */
@Component
@Slf4j
public class UploadStaging {
    private final Path directory;

    public UploadStaging(@Value("${app.storage.temp-dir}") String directory) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
    }

    /** Bên gọi đóng input; StagedFile phải dùng try-with-resources để dọn file sau kiểm tra/upload. */
    public StagedFile stage(InputStream source) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }

        Files.createDirectories(directory);
        Path path = Files.createTempFile(directory, "academy-upload-", ".tmp");
        try {
            long size;
            // Không đóng source ở đây vì vòng đời input thuộc bên gọi.
            InputStream input = new DigestInputStream(source, digest);
            try (OutputStream output = Files.newOutputStream(path)) {
                size = input.transferTo(output);
            }
            return new StagedFile(path, size, HexFormat.of().formatHex(digest.digest()));
        } catch (IOException | RuntimeException | Error exception) {
            deleteTemporaryFile(path);
            throw exception;
        }
    }

    public record StagedFile(Path path, long size, String checksum) implements AutoCloseable {
        @Override
        public void close() {
            deleteTemporaryFile(path);
        }
    }

    private static void deleteTemporaryFile(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            // Lỗi dọn staging không được biến một upload đã commit thành lỗi hoặc che lỗi gốc.
            log.error("Temporary upload cleanup failed; inspect staging directory");
        }
    }
}
