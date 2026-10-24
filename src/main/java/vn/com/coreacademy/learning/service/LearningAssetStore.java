package vn.com.coreacademy.learning.service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.storage.service.ObjectStorage;
import vn.com.coreacademy.storage.service.UploadStaging;

/** Kiểm tra tài nguyên Learning bằng file tạm, sau đó upload vào kho S3 private. */
@Component
@Slf4j
@RequiredArgsConstructor
public class LearningAssetStore {
    private final UploadStaging staging;
    private final ObjectStorage storage;

    public record Stored(String key, String name, String mime, long size, String checksum) {
    }

    /** Upload S3 xong trước transaction metadata; file staging được dọn dù upload thành công hay thất bại. */
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
        String key = "learning/" + UUID.randomUUID();
        boolean attempted = false;
        try (InputStream input = file.getInputStream(); var staged = staging.stage(input)) {
            Path path = staged.path();
            String mime = detect(path, name);
            long limit = mime.startsWith("video/") ? 500_000_000 : mime.startsWith("image/") ? 5_000_000
                : "application/pdf".equals(mime) ? 20_000_000 : 10_000_000;
            if (staged.size() > limit || (mime.startsWith("video/")
                && (duration == null || duration.signum() <= 0 || duration.compareTo(new BigDecimal("86400")) > 0))
                || ("application/pdf".equals(mime) && (pageCount == null || pageCount < 1))) {
                throw new BusinessException(LearningResponseCode.ASSET_INVALID);
            }
            attempted = true;
            storage.put(key, path, mime, staged.checksum());
            return new Stored(key, name, mime, staged.size(), staged.checksum());
        } catch (IOException | RuntimeException exception) {
            // PUT có thể đã tới server trước timeout; chỉ dọn key UUID của lượt upload này.
            if (attempted) {
                try {
                    storage.delete(key);
                } catch (RuntimeException cleanup) {
                    log.error("S3 upload cleanup failed; reconcile objectKey={}", key);
                    exception.addSuppressed(cleanup);
                }
            }
            throw exception;
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

    public void discard(String key) {
        storage.delete(key);
    }
}
