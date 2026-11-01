package vn.com.coreacademy.learning.constants;

import java.math.BigDecimal;

/** Giới hạn nghiệp vụ dùng chung DTO/service; số byte là hệ thập phân, riêng part là 8 MiB. */
public final class LearningLimits {
    public static final int DASHBOARD_DEADLINES = 5;
    public static final int MAX_LESSONS_PER_COURSE = 500;
    public static final int MAX_BLOCKS_PER_COURSE = 2_000;
    public static final int MAX_LEARNERS_PER_ASSIGNMENT = 500;
    public static final int MAX_GROUP_MEMBERS = 500;
    public static final int MAX_LEADERBOARD_LEARNERS = 5_000;
    public static final int MAX_LEADERBOARD_PAGE_SIZE = 100;
    public static final int MAX_ASSIGNMENT_TARGETS = 100;
    public static final int MAX_NOTES_PER_LESSON = 500;
    public static final int MAX_BOOKMARKS_PER_LESSON = 500;
    public static final int MAX_NOTE_LENGTH = 4_000;
    public static final int MAX_BOOKMARK_LABEL_LENGTH = 200;
    public static final int MAX_SAVED_PAGE_SIZE = 100;
    public static final int MAX_EXPORT_ROWS = 10_000;
    public static final int MAX_UPLOAD_PARTS = 10_000;
    public static final int UPLOAD_PART_BYTES = 8 * 1024 * 1024;
    public static final long MAX_VIDEO_BYTES = 500_000_000L;
    public static final long MAX_PDF_BYTES = 20_000_000L;
    public static final long MAX_IMAGE_BYTES = 5_000_000L;
    public static final long MAX_ATTACHMENT_BYTES = 10_000_000L;
    public static final long MAX_RENDITION_BYTES = 550_000_000L;
    public static final int MAX_MEDIA_DURATION_SECONDS = 86_400;
    public static final String MAX_MEDIA_DURATION_TEXT = "" + MAX_MEDIA_DURATION_SECONDS;
    public static final long MAX_PROBE_OUTPUT_BYTES = 1_000_000L;
    public static final BigDecimal MAX_PLAYBACK_RATE = new BigDecimal("2");
    public static final BigDecimal COVERAGE_GRACE_SECONDS = new BigDecimal("0.5");
    public static final BigDecimal DEFAULT_VIDEO_THRESHOLD = new BigDecimal("80");

    private LearningLimits() {
    }
}
