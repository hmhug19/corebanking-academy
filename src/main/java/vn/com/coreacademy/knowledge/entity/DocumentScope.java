package vn.com.coreacademy.knowledge.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** Who may find a document: only its uploader, the uploader's unit with its child units, or everyone. */
@Getter
@RequiredArgsConstructor
public enum DocumentScope {
    PERSONAL("personal"), UNIT("unit"), GLOBAL("global");

    private final String value;

    public static DocumentScope fromValue(String value) {
        for (DocumentScope scope : values()) {
            if (scope.value.equals(value)) {
                return scope;
            }
        }

        throw new IllegalArgumentException("Unknown document scope: " + value);
    }
}
