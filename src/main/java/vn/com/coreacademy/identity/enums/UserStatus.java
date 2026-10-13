package vn.com.coreacademy.identity.enums;

public enum UserStatus {
    ACTIVE("active"), LOCKED("locked"), DISABLED("disabled");

    private final String value;
    UserStatus(String value) { this.value = value; }
    public String getValue() { return value; }
    public static UserStatus fromValue(String value) {
        for (UserStatus status : values()) if (status.value.equals(value)) return status;
        throw new IllegalArgumentException("Unknown user status: " + value);
    }
}
