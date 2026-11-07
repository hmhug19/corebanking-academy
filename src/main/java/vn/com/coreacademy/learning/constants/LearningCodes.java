package vn.com.coreacademy.learning.constants;

import java.util.Set;

/** Mã nghiệp vụ ổn định của Learning; giữ nguyên giá trị JSON/DB khi đổi tên hằng số Java. */
public final class LearningCodes {
    private LearningCodes() {
    }

    public static final class CatalogStatus {
        public static final String DRAFT = "draft";
        public static final String PUBLISHED = "published";
        public static final String PAUSED = "paused";
        public static final String ARCHIVED = "archived";
        public static final String PATTERN = DRAFT + "|" + PUBLISHED + "|" + PAUSED + "|" + ARCHIVED;

        private CatalogStatus() {
        }
    }

    public static final class CourseStatus {
        public static final String ACTIVE = "active";
        public static final String PAUSED = "paused";
        public static final String ARCHIVED = "archived";
        public static final String PATTERN = ACTIVE + "|" + PAUSED + "|" + ARCHIVED;

        private CourseStatus() {
        }
    }

    public static final class RevisionState {
        public static final String DRAFT = "draft";
        public static final String PUBLISHED = "published";
        public static final Set<String> VALUES = Set.of(DRAFT, PUBLISHED);

        private RevisionState() {
        }
    }

    public static final class Requirement {
        public static final String MANDATORY = "mandatory";
        public static final String OPTIONAL = "optional";
        public static final String PATTERN = MANDATORY + "|" + OPTIONAL;
        public static final Set<String> VALUES = Set.of(MANDATORY, OPTIONAL);

        private Requirement() {
        }
    }

    public static final class Level {
        public static final String BASIC = "basic";
        public static final String INTERMEDIATE = "intermediate";
        public static final String ADVANCED = "advanced";
        public static final String PATTERN = BASIC + "|" + INTERMEDIATE + "|" + ADVANCED;
        public static final Set<String> VALUES = Set.of(BASIC, INTERMEDIATE, ADVANCED);

        private Level() {
        }
    }

    public static final class LessonType {
        public static final String CONTENT = "content";
        public static final String QUIZ = "quiz";
        public static final String PATTERN = CONTENT + "|" + QUIZ;

        private LessonType() {
        }
    }

    public static final class BlockType {
        public static final String VIDEO = "video";
        public static final String PDF = "pdf";
        public static final String TEXT = "text";
        public static final String PROCESS = "process";
        public static final String IMAGE = "image";
        public static final String ATTACHMENT = "attachment";
        public static final String PATTERN = VIDEO + "|" + PDF + "|" + TEXT + "|" + PROCESS + "|" + IMAGE + "|" +
            ATTACHMENT;

        private BlockType() {
        }
    }

    public static final class BodyFormat {
        public static final String MARKDOWN = "markdown";
        public static final String HTML = "html";
        public static final String PLAIN = "plain";
        public static final String PATTERN = MARKDOWN + "|" + HTML + "|" + PLAIN;

        private BodyFormat() {
        }
    }

    public static final class CompletionMode {
        public static final String MANUAL = "manual";
        public static final String VIDEO_COVERAGE = "video_coverage";
        public static final String PDF_END = "pdf_end";
        public static final String OPEN = "open";
        public static final String PATTERN = MANUAL + "|" + VIDEO_COVERAGE + "|" + PDF_END + "|" + OPEN;

        private CompletionMode() {
        }
    }

    public static final class AssetUsage {
        public static final String PRIMARY = "primary";
        public static final String ATTACHMENT = "attachment";
        public static final String IMAGE = "image";
        public static final String POSTER = "poster";
        public static final String PATTERN = PRIMARY + "|" + ATTACHMENT + "|" + IMAGE + "|" + POSTER;

        private AssetUsage() {
        }
    }

    public static final class AssetStatus {
        public static final String UPLOADING = "uploading";
        public static final String COMPLETING = "completing";
        public static final String PROCESSING = "processing";
        public static final String READY = "ready";
        public static final String FAILED = "failed";
        public static final String CANCELING = "canceling";
        public static final String CANCELED = "canceled";
        public static final String EXPIRED = "expired";
        public static final String DELETED = "deleted";
        public static final Set<String> SEARCHABLE_VALUES = Set.of(UPLOADING, COMPLETING, PROCESSING, READY, FAILED,
            CANCELING, CANCELED, EXPIRED);

        private AssetStatus() {
        }
    }

    public static final class MediaKind {
        public static final String VIDEO = "video";
        public static final String DOCUMENT = "document";
        public static final String IMAGE = "image";
        public static final String ATTACHMENT = "attachment";
        public static final Set<String> VALUES = Set.of(VIDEO, DOCUMENT, IMAGE, ATTACHMENT);

        private MediaKind() {
        }
    }

    public static final class ProgressStatus {
        public static final String NOT_STARTED = "not_started";
        public static final String IN_PROGRESS = "in_progress";
        public static final String COMPLETED = "completed";
        public static final String WITHDRAWN = "withdrawn";
        public static final Set<String> SEARCHABLE_VALUES = Set.of(NOT_STARTED, IN_PROGRESS, COMPLETED);

        private ProgressStatus() {
        }
    }

    public static final class EnrollmentMode {
        public static final String CONTINUE = "continue";
        public static final String RETAKE = "retake";
        public static final String PATTERN = CONTINUE + "|" + RETAKE;

        private EnrollmentMode() {
        }
    }

    public static final class EnrollmentOrigin {
        public static final String ASSIGNED = "assigned";
        public static final String SELF = "self";

        private EnrollmentOrigin() {
        }
    }

    public static final class TargetType {
        public static final String USER = "user";
        public static final String TEAM = "team";
        public static final String ORG = "org";
        public static final String LEARNING_GROUP = "learning_group";
        public static final String PATTERN = USER + "|" + TEAM + "|" + ORG + "|" + LEARNING_GROUP;

        private TargetType() {
        }
    }

    public static final class GroupStatus {
        public static final String ACTIVE = "active";

        private GroupStatus() {
        }
    }

    public static final class ShareStatus {
        public static final String ACTIVE = "active";
        public static final String REVOKED = "revoked";

        private ShareStatus() {
        }
    }

    public static final class AssignmentStatus {
        public static final String ACTIVE = "active";
        /** Cả đợt giao đã bị thu hồi; không sửa lịch hoặc nhắc học thêm. */
        public static final String REVOKED = "revoked";

        private AssignmentStatus() {
        }
    }

    public static final class RecipientStatus {
        public static final String ACTIVE = "active";
        public static final String REVOKED = "revoked";

        private RecipientStatus() {
        }
    }

    public static final class ReminderStatus {
        public static final String PENDING = "pending";
        public static final String SENT = "sent";
        public static final String CANCELLED = "cancelled";

        private ReminderStatus() {
        }
    }

    public static final class ReminderChannel {
        public static final String IN_APP = "in_app";

        private ReminderChannel() {
        }
    }

    public static final class LeaseState {
        public static final String ACTIVE = "active";
        public static final String RELEASED = "released";

        private LeaseState() {
        }
    }

    public static final class ProgressEvent {
        public static final String OPEN = "OPEN";
        public static final String PLAY = "PLAY";
        public static final String HEARTBEAT = "HEARTBEAT";
        public static final String PAUSE = "PAUSE";
        public static final String SEEK = "SEEK";
        public static final String PDF_PAGE = "PDF_PAGE";
        public static final String COMPLETE = "COMPLETE";
        public static final String CLOSE = "CLOSE";
        public static final String PATTERN = OPEN + "|" + PLAY + "|" + HEARTBEAT + "|" + PAUSE + "|" + SEEK + "|" +
            PDF_PAGE + "|" + COMPLETE + "|" + CLOSE;

        private ProgressEvent() {
        }
    }

    public static final class PlaybackState {
        public static final String PLAYING = "PLAYING";

        private PlaybackState() {
        }
    }

    public static final class UploadPartStatus {
        public static final String UPLOADING = "uploading";
        public static final String UPLOADED = "uploaded";
        public static final String RETRY = "retry";

        private UploadPartStatus() {
        }
    }

    /** Phạm vi bảng xếp hạng; team và org trùng giá trị với TargetType để dùng chung truy vấn học viên. */
    public static final class LeaderboardScope {
        public static final String TEAM = "team";
        public static final String ORG = "org";
        public static final String ALL = "all";
        public static final String PATTERN = TEAM + "|" + ORG + "|" + ALL;

        private LeaderboardScope() {
        }
    }

    /** Hoạt động đầu tiên trong ngày, ghi ở learning_activity_days.first_source. */
    public static final class ActivitySource {
        public static final String SESSION = "session";
        public static final String BLOCK_PROGRESS = "block_progress";
        public static final String QUIZ = "quiz";

        private ActivitySource() {
        }
    }

    /** Loại nguồn của dòng sổ XP, ghi ở xp_ledger.source_kind. */
    public static final class XpSource {
        public static final String LESSON = "LESSON";
        public static final String QUIZ_PASS = "QUIZ_PASS";
        public static final String QUIZ_FIRST_TRY_BONUS = "QUIZ_FIRST_TRY_BONUS";
        public static final String COURSE = "COURSE";
        public static final String COMMUNITY = "COMMUNITY";
        public static final String ADJUSTMENT = "ADJUSTMENT";

        private XpSource() {
        }
    }

    /** Loại neo của ghi chú và đánh dấu, ghi ở anchor_type; đánh dấu chỉ nhận pdf và video. */
    public static final class AnchorType {
        public static final String NONE = "none";
        public static final String PDF = "pdf";
        public static final String VIDEO = "video";
        public static final String PATTERN = NONE + "|" + PDF + "|" + VIDEO;
        public static final String BOOKMARK_PATTERN = PDF + "|" + VIDEO;

        private AnchorType() {
        }
    }

    /** Tab của trang Đã lưu (tham số kind). Mỗi dòng trả về mang kind số ít: note hoặc bookmark. */
    public static final class SavedKind {
        public static final String NOTES = "notes";
        public static final String BOOKMARKS = "bookmarks";
        public static final String PATTERN = NOTES + "|" + BOOKMARKS;
        public static final String NOTE_ITEM = "note";
        public static final String BOOKMARK_ITEM = "bookmark";

        private SavedKind() {
        }
    }

    /** Loại phản hồi bài học, ghi ở lesson_feedback.kind; gửi qua POST chỉ nhận comment, question và report. */
    public static final class FeedbackKind {
        public static final String RATING = "rating";
        public static final String COMMENT = "comment";
        public static final String QUESTION = "question";
        public static final String REPORT = "report";
        public static final String SENDABLE_PATTERN = COMMENT + "|" + QUESTION + "|" + REPORT;

        private FeedbackKind() {
        }
    }

    /** Trạng thái xử lý của câu hỏi và báo nội dung, ghi ở lesson_feedback.status; nhận xét và đánh giá không có. */
    public static final class FeedbackStatus {
        public static final String NEW = "new";
        public static final String ANSWERED = "answered";
        public static final String RESOLVED = "resolved";

        private FeedbackStatus() {
        }
    }

    public static final class SearchMode {
        public static final String MANAGE = "manage";
        public static final String MINE = "mine";
        public static final String CATALOG = "catalog";

        private SearchMode() {
        }
    }

    public static final class DeadlineStatus {
        public static final String NOT_ACTIVE = "not_active";
        public static final String NO_DEADLINE = "no_deadline";
        public static final String COMPLETED_LATE = "completed_late";
        public static final String COMPLETED_ON_TIME = "completed_on_time";
        public static final String OVERDUE_ACTIVE = "overdue_active";
        public static final String ON_TIME_ACTIVE = "on_time_active";

        private DeadlineStatus() {
        }
    }

    public static final class MediaMime {
        public static final String VIDEO_MP4 = "video/mp4";
        public static final String VIDEO_WEBM = "video/webm";
        public static final String IMAGE_WEBP = "image/webp";
        public static final String IMAGE_PREFIX = "image/";
        public static final String VIDEO_PREFIX = "video/";
        public static final String OFFICE_PREFIX = "application/vnd.openxmlformats-officedocument.";
        public static final String WORD = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        public static final String EXCEL = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        public static final String POWERPOINT =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation";

        private MediaMime() {
        }
    }

    public static final class ProcessingError {
        public static final String UNAVAILABLE = "LEARNING-MEDIA_PROCESSING_UNAVAILABLE";

        private ProcessingError() {
        }
    }

    public static final class AuditAction {
        public static final String ASSET_DELETED = "LEARNING_ASSET_DELETED";
        public static final String ASSET_UPLOADED = "LEARNING_ASSET_UPLOADED";
        public static final String ASSIGNMENT_REMINDED = "LEARNING_ASSIGNMENT_REMINDED";
        public static final String ASSIGNMENT_RESCHEDULED = "LEARNING_ASSIGNMENT_RESCHEDULED";
        public static final String ASSIGNMENT_REVOKED = "LEARNING_ASSIGNMENT_REVOKED";
        public static final String ASSIGNMENT_WITHDRAWN = "LEARNING_ASSIGNMENT_WITHDRAWN";
        public static final String COURSE_ASSIGNED = "LEARNING_COURSE_ASSIGNED";
        public static final String COURSE_COMPLETED = "LEARNING_COURSE_COMPLETED";
        public static final String COURSE_COPIED = "LEARNING_COURSE_COPIED";
        public static final String COURSE_CREATED = "LEARNING_COURSE_CREATED";
        public static final String COURSE_PUBLISHED = "LEARNING_COURSE_PUBLISHED";
        public static final String COURSE_SHARED = "LEARNING_COURSE_SHARED";
        public static final String COURSE_STATE_CHANGED = "LEARNING_COURSE_STATE_CHANGED";
        public static final String DRAFT_CREATED = "LEARNING_DRAFT_CREATED";
        public static final String DRAFT_SAVED = "LEARNING_DRAFT_SAVED";
        public static final String FIELD_DELETED = "LEARNING_FIELD_DELETED";
        public static final String FIELD_SAVED = "LEARNING_FIELD_SAVED";
        public static final String GROUP_CREATED = "LEARNING_GROUP_CREATED";
        public static final String GROUP_MEMBERS_CHANGED = "LEARNING_GROUP_MEMBERS_CHANGED";
        public static final String MEDIA_RETRY_REQUESTED = "LEARNING_MEDIA_RETRY_REQUESTED";
        public static final String MODULE_SAVED = "LEARNING_MODULE_SAVED";
        public static final String PROGRAM_COPIED = "LEARNING_PROGRAM_COPIED";
        public static final String PROGRAM_DELETED = "LEARNING_PROGRAM_DELETED";
        public static final String PROGRAM_SAVED = "LEARNING_PROGRAM_SAVED";
        public static final String SELF_ENROLLED = "LEARNING_SELF_ENROLLED";
        public static final String SESSION_TRANSFERRED = "LEARNING_SESSION_TRANSFERRED";
        public static final String UPLOAD_CANCEL_REQUESTED = "LEARNING_UPLOAD_CANCEL_REQUESTED";
        public static final String UPLOAD_CREATED = "LEARNING_UPLOAD_CREATED";
        public static final String UPLOAD_QUEUED = "LEARNING_UPLOAD_QUEUED";

        private AuditAction() {
        }
    }
}
