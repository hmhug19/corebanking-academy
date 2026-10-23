package vn.com.coreacademy.knowledge.extraction;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum KnowledgeFileType {
    PDF("pdf", "application/pdf", Set.of("application/pdf")),
    MARKDOWN("md", "text/markdown", Set.of("text/markdown", "text/x-markdown", "text/plain")),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"));

    private final String extension;
    /** The type backend detected; stored as {@code mime_type}. */
    private final String mimeType;
    /** Declared types browsers legitimately send for this extension (empty or octet-stream are always accepted). */
    private final Set<String> declaredMimeTypes;

    public static Optional<KnowledgeFileType> fromFileName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        for (KnowledgeFileType type : values()) {
            if (type.extension.equals(extension)) {
                return Optional.of(type);
            }
        }

        return Optional.empty();
    }

    public static KnowledgeFileType fromMimeType(String mimeType) {
        for (KnowledgeFileType type : values()) {
            if (type.mimeType.equals(mimeType)) {
                return type;
            }
        }

        throw new IllegalArgumentException("Unknown knowledge mime type: " + mimeType);
    }
}
