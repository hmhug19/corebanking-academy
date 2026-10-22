package vn.com.coreacademy.knowledge.extraction;

import java.util.List;

/** Result of extraction; {@code pageCount} is null for formats without pages. */
public record ExtractedText(List<TextLine> lines, Integer pageCount) {
}
