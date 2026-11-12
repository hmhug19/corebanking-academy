package vn.com.coreacademy.knowledge.extraction;

import java.util.List;

/**
 * One line of a document as the user sees it: {@code page} is null for formats without pages, {@code line} is 1-based
 * (restarting on every PDF page), blank lines are kept so the numbers stay true. {@code sectionPath} is the chain of
 * headings the line sits under (a heading's own path ends with itself; empty before the first heading) and
 * {@code kind} says what sort of line it is.
 */
public record TextLine(Integer page, int line, String text, List<String> sectionPath, LineKind kind) {
    public TextLine {
        sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath);
        kind = kind == null ? LineKind.PARAGRAPH : kind;
    }

    /** A plain paragraph outside any section. */
    public TextLine(Integer page, int line, String text) {
        this(page, line, text, List.of(), LineKind.PARAGRAPH);
    }
}
