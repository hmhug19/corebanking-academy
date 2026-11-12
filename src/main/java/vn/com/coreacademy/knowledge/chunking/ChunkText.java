package vn.com.coreacademy.knowledge.chunking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * The text that is embedded and shown to the model is {@code title › section path} followed by the content; the
 * content stored in the database stays the plain text.
 */
public final class ChunkText {
    public static final String SEPARATOR = " › ";

    private ChunkText() {
    }

    /** The headings joined for storage and display; empty for no headings. */
    public static String join(List<String> path) {
        return String.join(SEPARATOR, path);
    }

    /** SHA-256 of the text as lower-case hex, the {@code content_hash} of a chunk. */
    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is always available", exception);
        }
    }

    /** {@code title › path} then the content on the next line; the path is left out when it is blank. */
    public static String withContext(String title, String path, String content) {
        String head = path == null || path.isBlank() ? title : title + SEPARATOR + path;

        return head + "\n" + content;
    }
}
