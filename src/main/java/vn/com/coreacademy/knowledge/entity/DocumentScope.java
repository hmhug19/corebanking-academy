package vn.com.coreacademy.knowledge.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Who may find a document: only its uploader, the uploader's unit with its child units, or everyone. */
@Getter
@RequiredArgsConstructor
public enum DocumentScope {
    PERSONAL("personal"), UNIT("unit"), GLOBAL("global"),
    /** The source file of a course generation job: only its uploader reads it; never listed or searched. */
    COURSE_SOURCE("course_source");

    private final String value;

    public static DocumentScope fromValue(String value) {
        for (DocumentScope scope : values()) {
            if (scope.value.equals(value)) {
                return scope;
            }
        }

        throw new IllegalArgumentException("Unknown document scope: " + value);
    }

    /**
     * The scope a client may ask for: blank means none, and a course source is never a client's choice, since it hides
     * the document from every list and search.
     */
    public static DocumentScope fromRequest(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        DocumentScope scope = fromValue(value);
        if (scope == COURSE_SOURCE) {
            throw new IllegalArgumentException("A course source cannot be requested: " + value);
        }

        return scope;
    }
}
