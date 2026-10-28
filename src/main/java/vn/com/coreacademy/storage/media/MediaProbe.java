package vn.com.coreacademy.storage.media;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.springframework.http.MediaType;

/** Kiểm nội dung thực; duration/pageCount do server xác định, không lấy metadata FE làm bằng chứng. */
@Slf4j
@RequiredArgsConstructor
public class MediaProbe {
    private final Settings properties;

    public record Settings(String ffprobe, String ffmpeg, Duration probeTimeout) {
    }

    public static final class Rejected extends RuntimeException {
        public Rejected(String code) {
            super(code);
        }
    }
    private final ObjectMapper json;

    public record Checked(String mime, BigDecimal duration, Integer pages, Path playback) implements AutoCloseable {
        @Override
        public void close() {
            deleteTemporaryFile(playback);
        }
    }

    public Checked check(Path file, String name, String expectedMime, long maxBytes, long maxDurationSeconds) throws IOException {
        String mime = detect(file, name);
        if (Files.size(file) <= 0 || Files.size(file) > maxBytes) {
            throw new Rejected("ASSET_INVALID");
        }
        if (expectedMime != null && !mime.equals(expectedMime)) {
            throw new Rejected("UPLOAD_FILE_MISMATCH");
        }
        if (MediaType.APPLICATION_PDF_VALUE.equals(mime)) {
            try (var document = Loader.loadPDF(file.toFile())) {
                if (document.isEncrypted() || document.getNumberOfPages() < 1) {
                    throw new Rejected("ASSET_INVALID");
                }
                return new Checked(mime, null, document.getNumberOfPages(), null);
            } catch (IOException exception) {
                // PDF hỏng hoặc cần mật khẩu là lỗi file; không retry như lỗi hạ tầng S3.
                throw new Rejected("ASSET_INVALID");
            }
        }
        if (!mime.startsWith(MediaFormats.VIDEO_PREFIX)) {
            return new Checked(mime, null, null, null);
        }
        String details = run(List.of(properties.ffprobe(), "-v", "error", "-protocol_whitelist", "file,pipe",
            "-show_entries", "format=duration:stream=codec_type,codec_name,pix_fmt", "-of", "json", file.toString()));
        JsonNode parsed = json.readTree(details);
        boolean video = false;
        for (JsonNode stream : parsed.path("streams")) {
            String codec = stream.path("codec_name").asText();
            if ("video".equals(stream.path("codec_type").asText())) {
                video = true;
                boolean supported = MediaFormats.VIDEO_MP4.equals(mime)
                    ? "h264".equals(codec) && "yuv420p".equals(stream.path("pix_fmt").asText())
                    : List.of("vp8", "vp9").contains(codec);
                if (!supported) {
                    throw new Rejected("ASSET_CODEC_UNSUPPORTED");
                }
            } else if ("audio".equals(stream.path("codec_type").asText())
                && !(MediaFormats.VIDEO_MP4.equals(mime) ? "aac".equals(codec) : List.of("opus",
                    "vorbis").contains(codec))) {
                throw new Rejected("ASSET_CODEC_UNSUPPORTED");
            }
        }
        BigDecimal duration;
        try {
            duration = new BigDecimal(parsed.path("format").path("duration").asText());
        } catch (NumberFormatException exception) {
            throw new Rejected("ASSET_INVALID");
        }
        if (!video || duration.signum() <= 0 ||
            duration.compareTo(BigDecimal.valueOf(maxDurationSeconds)) > 0) {
            throw new Rejected("ASSET_INVALID");
        }
        Path playback = null;
        if (MediaFormats.VIDEO_MP4.equals(mime)) {
            playback = Files.createTempFile(file.getParent(), "academy-playback-", ".mp4");
            try {
                run(List.of(properties.ffmpeg(), "-v", "error", "-nostdin", "-y", "-protocol_whitelist", "file,pipe",
                    "-i", file.toString(), "-map", "0:v:0", "-map", "0:a:0?", "-c", "copy",
                    "-movflags", "+faststart", playback.toString()));
            } catch (IOException | RuntimeException exception) {
                deleteTemporaryFile(playback);
                throw exception;
            }
        }
        return new Checked(mime, duration, null, playback);
    }

    private String run(List<String> command) throws IOException {
        Path output = Files.createTempFile("academy-probe-", ".log");
        Process process = null;
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(properties.probeTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("Media probe timed out");
            }
            if (Files.size(output) > 1_000_000L || process.exitValue() != 0) {
                throw new Rejected("ASSET_INVALID");
            }
            return Files.readString(output);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Media probe interrupted", exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            deleteTemporaryFile(output);
        }
    }

    public String detect(Path path, String name) throws IOException {
        byte[] header;
        try (InputStream input = Files.newInputStream(path)) {
            header = input.readNBytes(16);
        }
        String hex = HexFormat.of().formatHex(header);
        String lower = name.toLowerCase(Locale.ROOT);
        if (hex.startsWith("255044462d") && lower.endsWith(".pdf")) {
            return MediaType.APPLICATION_PDF_VALUE;
        }
        if (hex.startsWith("89504e470d0a1a0a") && lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG_VALUE;
        }
        if (hex.startsWith("ffd8ff") && (lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
            return MediaType.IMAGE_JPEG_VALUE;
        }
        if (header.length >= 12 && new String(header, 0, 4, StandardCharsets.US_ASCII).equals("RIFF")
            && new String(header, 8, 4, StandardCharsets.US_ASCII).equals("WEBP")
            && lower.endsWith(".webp")) {
            return MediaFormats.IMAGE_WEBP;
        }
        if (header.length >= 8 && new String(header, 4, 4, StandardCharsets.US_ASCII).equals("ftyp")
            && lower.endsWith(".mp4")) {
            return MediaFormats.VIDEO_MP4;
        }
        if (hex.startsWith("1a45dfa3") && lower.endsWith(".webm")) {
            return MediaFormats.VIDEO_WEBM;
        }
        if (hex.startsWith("504b0304")) {
            try (ZipFile zip = new ZipFile(path.toFile())) {
                if (zip.getEntry("[Content_Types].xml") != null) {
                    if (lower.endsWith(".docx") && zip.getEntry("word/document.xml") != null) {
                        return MediaFormats.WORD;
                    }
                    if (lower.endsWith(".xlsx") && zip.getEntry("xl/workbook.xml") != null) {
                        return MediaFormats.EXCEL;
                    }
                    if (lower.endsWith(".pptx") && zip.getEntry("ppt/presentation.xml") != null) {
                        return MediaFormats.POWERPOINT;
                    }
                }
            } catch (ZipException exception) {
                throw new Rejected("ASSET_INVALID");
            }
        }
        throw new Rejected("ASSET_INVALID");
    }

    private static void deleteTemporaryFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException exception) {
            // Dọn file tạm không được che lỗi gốc hoặc biến kết quả đã lưu thành thất bại.
            log.error("Media probe cleanup failed; inspect temporary directory");
        }
    }
}
