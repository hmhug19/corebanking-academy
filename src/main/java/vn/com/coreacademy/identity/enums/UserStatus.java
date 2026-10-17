package vn.com.coreacademy.identity.enums;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum UserStatus {
    ACTIVE("active"), LOCKED("locked"), INACTIVE("inactive");

    private final String value;

    public static UserStatus fromValue(String value) {
        for (UserStatus status : values()) {
            if (status.value.equals(value)) {
                return status;
            }
        }

        throw new IllegalArgumentException("Unknown user status: " + value);
    }
}
