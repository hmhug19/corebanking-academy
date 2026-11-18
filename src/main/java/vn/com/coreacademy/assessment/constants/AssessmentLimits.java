package vn.com.coreacademy.assessment.constants;

/** Giới hạn contract của Quiz Engine; DTO, service và SQL CHECK cùng tham chiếu các giá trị này. */
public final class AssessmentLimits {
    public static final int MIN_OPTIONS = 2;
    public static final int MAX_OPTIONS = 10;
    public static final int MAX_QUESTION_TEXT = 10_000;
    /** Đủ cho cả ngân hàng một chương trình (gấp đôi một lần nhập Excel); đề RANDOM_POOL bốc từ toàn bộ pool. */
    public static final int MAX_POOL_SIZE = 2000;
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
    /** Số câu hỏi nhanh tối đa cho một lần mở cuối bài; ngắn để không giống một bài thi. */
    public static final int CHECKPOINT_QUESTIONS = 3;
    /** Gợi ý ôn tập: năng lực yếu khi dưới ngưỡng đạt mặc định của đề thi. */
    public static final int REVIEW_WEAK_PERCENT = 70;
    /** Gợi ý ôn tập: chỉ tính câu sai trong số ngày gần đây này. */
    public static final int REVIEW_DAYS = 90;
    public static final int REVIEW_LESSONS_PER_COMPETENCY = 3;
    public static final int REVIEW_DEFAULT_COMPETENCIES = 3;
    public static final int REVIEW_MAX_COMPETENCIES = 10;
    public static final int MAX_EXPORT_ROWS = 10_000;
    public static final int MAX_IMPORT_ROWS = 1000;
    public static final int MAX_IMPORT_ERRORS = 200;
    public static final long MAX_IMPORT_FILE_BYTES = 5L * 1024 * 1024;
    public static final long MAX_IMPORT_EXPANDED_BYTES = 20L * 1024 * 1024;
    public static final int MAX_IMPORT_ZIP_ENTRIES = 100;

    private AssessmentLimits() {
    }
}
