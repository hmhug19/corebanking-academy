package vn.com.coreacademy.assessment.constants;

/** Giới hạn contract của Quiz Engine; DTO, service và SQL CHECK cùng tham chiếu các giá trị này. */
public final class AssessmentLimits {
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 10;
    public static final int MAX_QUESTION_TEXT = 10_000;
    public static final int MAX_POOL_SIZE = 500;
    public static final int MAX_QUESTION_COUNT = 200;
    public static final int MAX_TIME_LIMIT_MINUTES = 240;
    public static final int MAX_GRACE_SECONDS = 300;
    public static final int MAX_ATTEMPTS = 20;
    public static final int MAX_RETAKE_WAIT_HOURS = 720;
    public static final int AUTO_SUBMIT_BATCH = 100;
    public static final int CONTENT_PREVIEW_LENGTH = 200;
    public static final int MAX_BULK_IDS = 100;
    public static final int MAX_REASON_LENGTH = 2000;
    public static final int MAX_EXTRA_ATTEMPTS = 10;
    public static final int MAX_EXPORT_ROWS = 10_000;
    public static final int MAX_IMPORT_ROWS = 1000;
    public static final int MAX_IMPORT_ERRORS = 200;
    public static final long MAX_IMPORT_FILE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_IMPORT_EXPANDED_BYTES = 20L * 1024 * 1024;
    public static final int MAX_IMPORT_ZIP_ENTRIES = 100;

    private AssessmentLimits() {
    }
}
