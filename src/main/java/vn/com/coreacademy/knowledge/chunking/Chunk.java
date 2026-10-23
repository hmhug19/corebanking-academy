package vn.com.coreacademy.knowledge.chunking;

/** A piece of one document that fits the embedding model; {@code page} is null for formats without pages. */
public record Chunk(String content, Integer page, int lineStart, int lineEnd, int tokenEstimate) {
}
