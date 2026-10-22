package vn.com.coreacademy.knowledge.entity;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum DocumentStatus {
    QUEUED("queued"), PROCESSING("processing"), READY("ready"), FAILED("failed");

    private final String value;

    public static DocumentStatus fromValue(String value) {
        for (DocumentStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }

        throw new IllegalArgumentException("Unknown document status: " + value);
    }
}
