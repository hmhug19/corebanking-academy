package vn.com.coreacademy.identity.constants;

/** Field limits of access requests; the DTO validation, the service and the SQL column share them. */
public final class AccessRequestLimits {
    public static final int NOTE_MAX = 500;
    public static final int SEARCH_MAX = 100;

    private AccessRequestLimits() {
    }
}
