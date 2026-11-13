package vn.com.coreacademy.storage.media;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
    /** Ffprobe JSON and ffmpeg errors stay far below this; more means the tool is not reading a media file. */
    private static final long MAX_OUTPUT_BYTES = 1_000_000L;
    /** How much of a failed tool's output reaches the log. */
    private static final int LOG_OUTPUT_CHARS = 500;

    private final Settings properties;

    /** The x264 speed/size trade-off when nothing else is configured. */
    public static final String DEFAULT_PRESET = "veryfast";
    /** Threads per transcode when nothing else is configured: each one costs memory (about 290 MB at 2, 410 MB at 8). */
    public static final int DEFAULT_THREADS = 2;

    /**
     * @param threads decoder and encoder threads of one transcode; the wall time of an AV1 or HEVC source is
     *                almost inversely proportional to it
     * @param preset  x264 preset of a transcode (a remux copies the streams)
     */
    public record Settings(String ffprobe, String ffmpeg, Duration probeTimeout, Duration transcodeTimeout,
                           int threads, String preset) {
        public Settings {
            if (threads < 1) throw new IllegalArgumentException("threads must be positive");
        }

        public Settings(String ffprobe, String ffmpeg, Duration probeTimeout, Duration transcodeTimeout) {
            this(ffprobe, ffmpeg, probeTimeout, transcodeTimeout, DEFAULT_THREADS, DEFAULT_PRESET);
        }

        public Settings(String ffprobe, String ffmpeg, Duration probeTimeout) {
            this(ffprobe, ffmpeg, probeTimeout, Duration.ofHours(6));
        }
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
            "-show_entries", "format=duration,format_name:stream=index,codec_type,codec_name,pix_fmt,duration,width,height,level,channels:stream_disposition=attached_pic",
            "-of", "json", file.toString()));
        JsonNode parsed = json.readTree(details);
        JsonNode video = null;
        JsonNode audio = null;
        for (JsonNode stream : parsed.path("streams")) {
            if (video == null && "video".equals(stream.path("codec_type").asText())
                && stream.path("disposition").path("attached_pic").asInt() == 0) {
                video = stream;
            } else if (audio == null && "audio".equals(stream.path("codec_type").asText())) {
                audio = stream;
            }
        }
        BigDecimal duration = durationOf(parsed);
        if (video == null || duration.signum() <= 0 ||
            duration.compareTo(BigDecimal.valueOf(maxDurationSeconds)) > 0) {
            throw new Rejected("ASSET_INVALID");
        }
        boolean mp4 = MediaFormats.VIDEO_MP4.equals(mime);
        boolean compatible = mp4
            ? "h264".equals(video.path("codec_name").asText()) && "yuv420p".equals(video.path("pix_fmt").asText())
                && video.path("width").asInt() <= 1920 && video.path("height").asInt() <= 1080
                && video.path("level").asInt() > 0 && video.path("level").asInt() <= 42
                && (audio == null || "aac".equals(audio.path("codec_name").asText()) && audio.path("channels").asInt() <= 2)
            : List.of("vp8", "vp9").contains(video.path("codec_name").asText())
                && (audio == null || List.of("opus", "vorbis").contains(audio.path("codec_name").asText()));
        if (!mp4 && !compatible) {
            throw new Rejected("ASSET_CODEC_UNSUPPORTED");
        }
        Path playback = null;
        if (mp4) {
            playback = Files.createTempFile(file.getParent(), "academy-playback-", ".mp4");
            try {
                List<String> command = mp4Command(file, playback, video, audio, duration, maxBytes, compatible);
                try {
                    run(command, properties.transcodeTimeout());
                } catch (Rejected exception) {
                    if (!compatible) throw exception;
                    // A valid codec can still have timestamps or stream headers that cannot be remuxed.
                    run(mp4Command(file, playback, video, audio, duration, maxBytes, false),
                        properties.transcodeTimeout());
                }
                if (Files.size(playback) == 0) throw new Rejected("ASSET_INVALID");
                JsonNode rendition = json.readTree(run(List.of(properties.ffprobe(), "-v", "error",
                    "-protocol_whitelist", "file,pipe", "-select_streams", "v:0", "-show_entries",
                    "format=duration:stream=duration", "-of", "json", playback.toString())));
                BigDecimal playbackDuration = durationOf(rendition);
                if (!rendition.path("streams").isArray() || rendition.path("streams").isEmpty()
                    || playbackDuration.signum() <= 0) throw new Rejected("ASSET_INVALID");
                // Progress is measured against the file learners play, including normalized timestamps.
                duration = playbackDuration;
            } catch (IOException | RuntimeException exception) {
                deleteTemporaryFile(playback);
                throw exception;
            }
        }
        return new Checked(mime, duration, null, playback);
    }

    List<String> mp4Command(Path source, Path destination, JsonNode video, JsonNode audio,
                                   BigDecimal duration, long maxBytes, boolean copy) {
        List<String> command = new ArrayList<>(List.of(properties.ffmpeg(), "-v", "error", "-nostdin", "-y",
            // Decoder threads otherwise follow the host's core count (AV1 keeps a frame buffer per thread),
            // which is memory the container may not have.
            "-protocol_whitelist", "file,pipe", "-threads", Integer.toString(properties.threads()),
            "-i", source.toString(),
            "-map", "0:" + video.path("index").asInt()));
        if (audio != null) command.addAll(List.of("-map", "0:" + audio.path("index").asInt()));
        command.addAll(List.of("-map_metadata", "-1", "-map_chapters", "-1", "-sn", "-dn"));
        if (copy) {
            command.addAll(List.of("-c", "copy"));
        } else {
            // Bound rendition growth relative to the accepted source limit, leaving room for the MP4 container.
            long budget = BigDecimal.valueOf(maxBytes).multiply(new BigDecimal("7.2"))
                .divide(duration, 0, RoundingMode.DOWN).min(BigDecimal.valueOf(4_000_000)).max(BigDecimal.valueOf(8_000))
                .longValue();
            long audioRate = audio == null ? 0 : Math.min(128_000, budget / 4);
            long videoRate = budget - audioRate;
            command.addAll(List.of("-c:v", "libx264", "-preset", properties.preset(),
                "-threads", Integer.toString(properties.threads()), "-pix_fmt", "yuv420p",
                "-vf", "scale=w='min(1920,iw)':h='min(1080,ih)':force_original_aspect_ratio=decrease:force_divisible_by=2",
                "-fpsmax", "60", "-b:v", Long.toString(videoRate), "-maxrate", Long.toString(videoRate),
                "-bufsize", Long.toString(videoRate * 2)));
            if (audio != null) command.addAll(List.of("-c:a", "aac", "-b:a", Long.toString(audioRate), "-ac", "2", "-ar", "48000"));
        }
        command.addAll(List.of("-movflags", "+faststart", destination.toString()));
        return command;
    }

    private BigDecimal durationOf(JsonNode details) {
        BigDecimal duration = BigDecimal.ZERO;
        List<JsonNode> candidates = new ArrayList<>();
        candidates.add(details.path("format").path("duration"));
        details.path("streams").forEach(stream -> candidates.add(stream.path("duration")));
        for (JsonNode value : candidates) {
            try {
                duration = duration.max(new BigDecimal(value.asText()));
            } catch (NumberFormatException ignored) {
                // Fragmented MP4s may have a duration only on a stream.
            }
        }
        return duration;
    }

    private String run(List<String> command) throws IOException {
        return run(command, properties.probeTimeout());
    }

    /**
     * Exit codes that say the machine failed, not the file: the kernel killed the tool (SIGKILL, as the
     * container's OOM killer does, or SIGTERM), or ffmpeg ran out of memory (ENOMEM) or disk (ENOSPC).
     * Java reports a process killed by a signal as 128 + signal; ffmpeg exits with its negative AVERROR
     * truncated to a byte.
     */
    private static final Set<Integer> INFRASTRUCTURE_EXITS = Set.of(128 + 9, 128 + 15, 256 - 12, 256 - 28);

    /** Whether a failed run should be retried rather than reported as a bad file. */
    static boolean infrastructureFailure(int exitValue) {
        return INFRASTRUCTURE_EXITS.contains(exitValue);
    }

    String run(List<String> command, Duration timeout) throws IOException {
        Path output = Files.createTempFile("academy-probe-", ".log");
        Process process = null;
        String tool = Path.of(command.get(0)).getFileName().toString();
        try {
            process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(output.toFile()).start();
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                log.warn("Media tool timed out tool={} timeout={}", tool, timeout);
                throw new IllegalStateException("Media probe timed out");
            }
            int exit = process.exitValue();
            if (infrastructureFailure(exit)) {
                // An OOM-killed ffmpeg must not turn a good video into a permanently "invalid" file.
                log.warn("Media tool stopped by the system tool={} exit={} output={}", tool, exit, tail(output));
                throw new IllegalStateException("Media tool stopped by the system");
            }
            if (Files.size(output) > MAX_OUTPUT_BYTES || exit != 0) {
                log.warn("Media tool rejected the file tool={} exit={} output={}", tool, exit, tail(output));
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

    private static String tail(Path output) {
        try (var channel = Files.newByteChannel(output)) {
            long size = channel.size();
            var buffer = ByteBuffer.allocate((int) Math.min(size, LOG_OUTPUT_CHARS));
            channel.position(size - buffer.capacity());
            while (buffer.hasRemaining() && channel.read(buffer) >= 0) {
                // Read the whole tail.
            }
            String text = new String(buffer.array(), 0, buffer.position(), StandardCharsets.UTF_8)
                .replaceAll("\\s+", " ").strip();
            return size > buffer.capacity() ? "..." + text : text;
        } catch (IOException | RuntimeException exception) {
            return "<unreadable>";
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
        if (lower.endsWith(".mp4")) {
            if (header.length >= 8 && new String(header, 4, 4, StandardCharsets.US_ASCII).equals("ftyp")) {
                return MediaFormats.VIDEO_MP4;
            }
            String format = json.readTree(run(List.of(properties.ffprobe(), "-v", "error",
                "-protocol_whitelist", "file,pipe", "-show_entries", "format=format_name", "-of", "json",
                path.toString()))).path("format").path("format_name").asText();
            if (List.of(format.split(",")).contains("mp4")) return MediaFormats.VIDEO_MP4;
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
