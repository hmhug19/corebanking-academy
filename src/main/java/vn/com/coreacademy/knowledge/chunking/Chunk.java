package vn.com.coreacademy.knowledge.chunking;

import java.util.List;

/**
 * A piece of one document that fits the embedding model; {@code page} is null for formats without pages and is the
 * page of the first line (a chunk may run on into the next page, then {@code lineEnd} is a line of that page).
 * {@code sectionPath} is the chain of headings the chunk sits under, empty before the first heading.
 */
public record Chunk(String content, Integer page, int lineStart, int lineEnd, int tokenEstimate,
                    List<String> sectionPath) {
    public Chunk {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
    }

    /** A chunk outside any section. */
    public Chunk(String content, Integer page, int lineStart, int lineEnd, int tokenEstimate) {
        this(content, page, lineStart, lineEnd, tokenEstimate, List.of());
    }
}
