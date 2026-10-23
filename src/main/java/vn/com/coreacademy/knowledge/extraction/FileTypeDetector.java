package vn.com.coreacademy.knowledge.extraction;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.exception.KnowledgeResponseCode;

/**
 * Decides what an uploaded file really is. The browser's MIME type is only a hint that must not contradict the
 * extension; the type backend stores comes from the extension plus the file's own bytes.
 */
@Component
@RequiredArgsConstructor
public class FileTypeDetector {
    private static final byte[] PDF_MAGIC = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};
    private static final String DOCX_BODY_ENTRY = "word/document.xml";

    private final KnowledgeProperties properties;

    /** Extension, size and declared MIME: everything that can be refused before a byte is written to disk. */
    public KnowledgeFileType checkMetadata(String originalName, long size, String declaredMime) {
        KnowledgeFileType type = KnowledgeFileType.fromFileName(originalName)
            .orElseThrow(() -> new BusinessException(KnowledgeResponseCode.FILE_TYPE_UNSUPPORTED));

        if (size <= 0) {
            throw new BusinessException(KnowledgeResponseCode.FILE_INVALID);
        }
        if (size > properties.maxFileSize().toBytes()) {
            throw new BusinessException(KnowledgeResponseCode.FILE_TOO_LARGE);
        }
        if (!declaredMimeAllowed(type, declaredMime)) {
            throw new BusinessException(KnowledgeResponseCode.FILE_INVALID);
        }

        return type;
    }

    /** The bytes on disk must match the type the extension promised. */
    public void checkContent(KnowledgeFileType type, Path file) {
        boolean valid;
        try {
            valid = switch (type) {
                case PDF -> startsWith(file, PDF_MAGIC);
                case DOCX -> startsWith(file, ZIP_MAGIC) && hasDocxBody(file);
                case MARKDOWN -> isStrictUtf8WithoutNul(file);
            };
        } catch (IOException exception) {
            valid = false;
        }
        if (!valid) {
            throw new BusinessException(KnowledgeResponseCode.FILE_INVALID);
        }
    }

    private boolean declaredMimeAllowed(KnowledgeFileType type, String declaredMime) {
        if (declaredMime == null || declaredMime.isBlank()) {
            return true;
        }
        int parameters = declaredMime.indexOf(';');
        String base = (parameters < 0 ? declaredMime : declaredMime.substring(0, parameters)).trim()
            .toLowerCase(Locale.ROOT);

        return base.equals("application/octet-stream") || type.getDeclaredMimeTypes().contains(base);
    }

    private static boolean startsWith(Path file, byte[] magic) throws IOException {
        try (InputStream input = Files.newInputStream(file)) {
            return Arrays.equals(input.readNBytes(magic.length), magic);
        }
    }

    private static boolean hasDocxBody(Path file) throws IOException {
        try (ZipFile zip = new ZipFile(file.toFile())) {
            return zip.getEntry(DOCX_BODY_ENTRY) != null;
        } catch (ZipException exception) {
            return false;
        }
    }

    private static boolean isStrictUtf8WithoutNul(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        for (byte value : bytes) {
            if (value == 0) {
                return false;
            }
        }
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }
}
