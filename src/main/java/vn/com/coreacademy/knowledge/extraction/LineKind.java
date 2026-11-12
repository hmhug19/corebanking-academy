package vn.com.coreacademy.knowledge.extraction;

/**
 * What a line is, so the chunker can keep a heading with its text and never cut inside a table or a code block:
 * consecutive {@code TABLE_ROW} lines are one table, consecutive {@code CODE} lines (fences included) are one block.
 */
public enum LineKind {
    HEADING,
    PARAGRAPH,
    LIST_ITEM,
    TABLE_ROW,
    CODE
}
