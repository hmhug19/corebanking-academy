package vn.com.coreacademy.knowledge.extraction;

/**
 * One line of a document as the user sees it: {@code page} is null for formats without pages, {@code line} is 1-based
 * (restarting on every PDF page), blank lines are kept so the numbers stay true.
 */
public record TextLine(Integer page, int line, String text) {
}
