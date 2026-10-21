package vn.com.coreacademy.learning.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipFile;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.exception.LearningResponseCode;

/** Kho tệp local riêng, không public static; controller chỉ tải sau khi service kiểm tra quyền. */
@Component
public class LearningAssetStore {
    private final Path root;

    public LearningAssetStore(@Value("${app.learning.asset-dir:./data/learning-assets}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    public record Stored(String key, String name, String mime, long size, String checksum) {
    }

    /** Upload xong trước khi mở transaction metadata; tên đĩa là UUID, không dùng path do client gửi. */
    public Stored store(MultipartFile file, BigDecimal duration, Integer pageCount) throws IOException {
        if (file.isEmpty() || file.getSize() > 500_000_000) {
            throw new BusinessException(LearningResponseCode.ASSET_INVALID);
        }
        String original = file.getOriginalFilename() == null ? "file" : file.getOriginalFilename();
        String name = original.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "_");
        if (name.isBlank() || name.length() > 255) {
            throw new BusinessException(LearningResponseCode.ASSET_INVALID);
        }
        Files.createDirectories(root);
        Path path = root.resolve(UUID.randomUUID().toString());
        boolean retained = false;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(file.getInputStream(), digest)) {
                Files.copy(input, path);
            }
            String mime = detect(path, name);
            long limit = mime.startsWith("video/") ? 500_000_000 : mime.startsWith("image/") ? 5_000_000
                : "application/pdf".equals(mime) ? 20_000_000 : 10_000_000;
            if (Files.size(path) > limit || (mime.startsWith("video/")
                && (duration == null || duration.signum() <= 0 || duration.compareTo(new BigDecimal("86400")) > 0))
                || ("application/pdf".equals(mime) && (pageCount == null || pageCount < 1))) {
                throw new BusinessException(LearningResponseCode.ASSET_INVALID);
            }
            retained = true;
            return new Stored(path.getFileName().toString(), name, mime, Files.size(path),
                HexFormat.of().formatHex(digest.digest()));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        } finally {
            if (!retained) {
                Files.deleteIfExists(path);
            }
        }
    }

    private String detect(Path path, String name) throws IOException {
        byte[] header;
        try (InputStream input = Files.newInputStream(path)) {
            header = input.readNBytes(16);
        }
        String hex = HexFormat.of().formatHex(header);
        String lower = name.toLowerCase(Locale.ROOT);
        if (hex.startsWith("255044462d") && lower.endsWith(".pdf")) {
            return "application/pdf";
        }
        if (hex.startsWith("89504e470d0a1a0a") && lower.endsWith(".png")) {
            return "image/png";
        }
        if (hex.startsWith("ffd8ff") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
            return "image/jpeg";
        }
        if (header.length >= 12 && new String(header, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
            && new String(header, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")
            && lower.endsWith(".webp")) {
            return "image/webp";
        }
        if (header.length >= 8 && new String(header, 4, 4, StandardCharsets.US_ASCII).equals("ftyp")
            && lower.endsWith(".mp4")) {
            return "video/mp4";
        }
        if (hex.startsWith("1a45dfa3") && lower.endsWith(".webm")) {
            return "video/webm";
        }
        if (hex.startsWith("504b0304")) {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                if (zip.getEntry("[Content_Types].xml") != null) {
                    if (lower.endsWith(".docx") && zip.getEntry("word/document.xml") != null) {
                        return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
                    }
                    if (lower.endsWith(".xlsx") && zip.getEntry("xl/workbook.xml") != null) {
                        return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
                    }
                    if (lower.endsWith(".pptx") && zip.getEntry("ppt/presentation.xml") != null) {
                        return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
                    }
                }
            }
        }
        throw new BusinessException(LearningResponseCode.ASSET_INVALID);
    }

    public Resource resource(String key) {
        Path file = resolve(key);
        if (!Files.isRegularFile(file)) {
            throw new BusinessException(LearningResponseCode.NOT_FOUND);
        }
        return new FileSystemResource(file);
    }

    public void discard(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    private Path resolve(String key) {
        if (key == null || !key.matches("[a-f0-9-]{36}")) {
            throw new BusinessException(LearningResponseCode.ASSET_INVALID);
        }
        Path result = root.resolve(key).normalize();
        if (!result.getParent().equals(root)) {
            throw new BusinessException(LearningResponseCode.ASSET_INVALID);
        }
        return result;
    }
}
