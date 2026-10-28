package vn.com.coreacademy.community.service;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.config.CommunityProperties;
import vn.com.coreacademy.community.exception.CommunityResponseCode;
import vn.com.coreacademy.storage.media.MediaFormats;
import vn.com.coreacademy.storage.media.MediaProbe;

/** Allowlist nghiệp vụ Community; helper probe dùng chung không biết owner, ACL hay trạng thái upload. */
@Component
public class CommunityMediaValidation {
    public static final int PART_SIZE = 8 * 1024 * 1024;
    public static final long MAX_DURATION = 86_400;
    private final MediaProbe probe;

    public CommunityMediaValidation(CommunityProperties properties, ObjectMapper json) {
        probe = new MediaProbe(new MediaProbe.Settings(properties.getFfprobe(), properties.getFfmpeg(),
            properties.getProbeTimeout()), json);
    }

    public void name(String name, String mime, long size) {
        if (name == null || name.isBlank() || name.length() > 255 || name.contains("/") || name.contains("\\")
            || name.codePoints().anyMatch(Character::isISOControl) || size <= 0 || size > limit(mime)) {
            throw new BusinessException(CommunityResponseCode.ASSET_INVALID);
        }
    }

    public long limit(String mime) {
        if (mime == null) {
            return 0;
        }
        return switch (mime) {
            case MediaFormats.VIDEO_MP4, MediaFormats.VIDEO_WEBM -> 500_000_000;
            case "application/pdf" -> 20_000_000;
            case "image/png", "image/jpeg", MediaFormats.IMAGE_WEBP -> 5_000_000;
            case MediaFormats.WORD, MediaFormats.EXCEL, MediaFormats.POWERPOINT -> 10_000_000;
            case "text/plain" -> 1_000_000;
            default -> 0;
        };
    }

    public String type(String mime) {
        return mime.startsWith("video/") ? "video" : mime.startsWith("image/") ? "image"
            : "application/pdf".equals(mime) ? "document" : "attachment";
    }

    public MediaProbe.Checked check(Path file, String name, String mime) throws IOException {
        if ("text/plain".equals(mime)) {
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.endsWith(".jdl") && !lower.endsWith(".txt")) {
                throw new BusinessException(CommunityResponseCode.ASSET_INVALID);
            }
            // UTF-8 strict và byte limit trước readAllBytes; JDL chỉ là tài liệu, tuyệt đối không thực thi.
            long size = Files.size(file);
            name(name, mime, size);
            String value;
            try {
                value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(Files.readAllBytes(file)))
                    .toString();
            } catch (CharacterCodingException exception) {
                throw new BusinessException(CommunityResponseCode.ASSET_INVALID);
            }
            if (value.indexOf('\0') >= 0) {
                throw new BusinessException(CommunityResponseCode.ASSET_INVALID);
            }
            return new MediaProbe.Checked(mime, null, null, null);
        }
        try {
            return probe.check(file, name, mime, limit(mime), MAX_DURATION);
        } catch (MediaProbe.Rejected exception) {
            throw new BusinessException(CommunityResponseCode.ASSET_INVALID);
        }
    }
}
