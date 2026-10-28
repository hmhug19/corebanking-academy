package vn.com.coreacademy.assessment.constants;

/** Mã nghiệp vụ của Quiz Engine; giá trị khớp CHECK trong docs/sql/learning_hub_assessment.sql. */
public final class AssessmentCodes {
    private AssessmentCodes() {
    }

    public static final class ItemStatus {
        public static final String ACTIVE = "ACTIVE";
        public static final String INACTIVE = "INACTIVE";
        public static final String PATTERN = ACTIVE + "|" + INACTIVE;

        private ItemStatus() {
        }
    }

    public static final class QuestionType {
        public static final String SINGLE = "SINGLE";
        public static final String MULTIPLE = "MULTIPLE";
        public static final String PATTERN = SINGLE + "|" + MULTIPLE;

        private QuestionType() {
        }
    }

    public static final class Difficulty {
        public static final String EASY = "EASY";
        public static final String MEDIUM = "MEDIUM";
        public static final String HARD = "HARD";
        public static final String PATTERN = EASY + "|" + MEDIUM + "|" + HARD;

        private Difficulty() {
        }
    }

    public static final class QuizType {
        public static final String PRACTICE = "PRACTICE";
        public static final String FINAL_EXAM = "FINAL_EXAM";
        public static final String PATTERN = PRACTICE + "|" + FINAL_EXAM;

        private QuizType() {
        }
    }

    public static final class QuizStatus {
        public static final String DRAFT = "DRAFT";
        public static final String PUBLISHED = "PUBLISHED";
        public static final String CLOSED = "CLOSED";
        public static final String PATTERN = DRAFT + "|" + PUBLISHED + "|" + CLOSED;

        private QuizStatus() {
        }
    }

    public static final class SelectionMode {
        public static final String FIXED = "FIXED";
        public static final String RANDOM_POOL = "RANDOM_POOL";
        public static final String PATTERN = FIXED + "|" + RANDOM_POOL;

        private SelectionMode() {
        }
    }

    public static final class ScoringMode {
        public static final String ALL_OR_NOTHING = "ALL_OR_NOTHING";
        public static final String PARTIAL = "PARTIAL";
        public static final String PATTERN = ALL_OR_NOTHING + "|" + PARTIAL;

        private ScoringMode() {
        }
    }

    public static final class ScoreMethod {
        public static final String BEST = "BEST";
        public static final String LAST = "LAST";
        public static final String PATTERN = BEST + "|" + LAST;

        private ScoreMethod() {
        }
    }

    public static final class ReviewPolicy {
        public static final String NEVER = "NEVER";
        public static final String AFTER_SUBMIT = "AFTER_SUBMIT";
        public static final String AFTER_PASS = "AFTER_PASS";
        public static final String AFTER_CLOSE = "AFTER_CLOSE";
        public static final String PATTERN = NEVER + "|" + AFTER_SUBMIT + "|" + AFTER_PASS + "|" + AFTER_CLOSE;

        private ReviewPolicy() {
        }
    }

    public static final class AttemptStatus {
        public static final String IN_PROGRESS = "IN_PROGRESS";
        public static final String SUBMITTED = "SUBMITTED";
        public static final String AUTO_SUBMITTED = "AUTO_SUBMITTED";
        public static final String VOIDED = "VOIDED";

        private AttemptStatus() {
        }
    }

    public static final class SubmitType {
        public static final String MANUAL = "MANUAL";
        public static final String TIMEOUT = "TIMEOUT";
        public static final String QUIZ_CLOSED = "QUIZ_CLOSED";
        public static final String ADMIN = "ADMIN";

        private SubmitType() {
        }
    }

    /** Tình trạng của một học viên được giao với một đề trong báo cáo (tính, không lưu). */
    public static final class LearnerStatus {
        public static final String NOT_STARTED = "NOT_STARTED";
        public static final String IN_PROGRESS = "IN_PROGRESS";
        public static final String FAILED = "FAILED";
        public static final String PASSED = "PASSED";

        private LearnerStatus() {
        }
    }

    /** Kết quả chấm lại từng lượt; chỉ INCREASED được áp dụng (thay đổi có lợi cho học viên). */
    public static final class RegradeOutcome {
        public static final String INCREASED = "INCREASED";
        public static final String DECREASED = "DECREASED";
        public static final String UNCHANGED = "UNCHANGED";
        public static final String SKIPPED_OPTIONS_CHANGED = "SKIPPED_OPTIONS_CHANGED";
        public static final String SKIPPED_TYPE_CHANGED = "SKIPPED_TYPE_CHANGED";

        private RegradeOutcome() {
        }
    }

    /** Lý do học viên chưa thể bắt đầu lượt mới; FE hiển thị theo mã, không so khớp chuỗi thông báo. */
    public static final class BlockReason {
        public static final String NOT_OPEN = "NOT_OPEN";
        public static final String CLOSED = "CLOSED";
        public static final String NOT_READY = "NOT_READY";
        public static final String LESSONS_REQUIRED = "LESSONS_REQUIRED";
        public static final String PASSED = "PASSED";
        public static final String NO_ATTEMPTS = "NO_ATTEMPTS";
        public static final String WAIT = "WAIT";

        private BlockReason() {
        }
    }

    public static final class AuditAction {
        public static final String COMPETENCY_SAVED = "ASSESSMENT_COMPETENCY_SAVED";
        public static final String QUESTION_CREATED = "ASSESSMENT_QUESTION_CREATED";
        public static final String QUESTION_UPDATED = "ASSESSMENT_QUESTION_UPDATED";
        public static final String QUIZ_CREATED = "ASSESSMENT_QUIZ_CREATED";
        public static final String QUIZ_UPDATED = "ASSESSMENT_QUIZ_UPDATED";
        public static final String QUIZ_POOL_CHANGED = "ASSESSMENT_QUIZ_POOL_CHANGED";
        public static final String QUIZ_PUBLISHED = "ASSESSMENT_QUIZ_PUBLISHED";
        public static final String QUESTIONS_STATUS_CHANGED = "ASSESSMENT_QUESTIONS_STATUS_CHANGED";
        public static final String QUESTIONS_DELETED = "ASSESSMENT_QUESTIONS_DELETED";
        public static final String ATTEMPT_VOIDED = "ASSESSMENT_ATTEMPT_VOIDED";
        public static final String ATTEMPTS_GRANTED = "ASSESSMENT_ATTEMPTS_GRANTED";
        public static final String GRANT_REVOKED = "ASSESSMENT_GRANT_REVOKED";
        public static final String QUIZ_CLOSED = "ASSESSMENT_QUIZ_CLOSED";
        public static final String QUESTIONS_IMPORTED = "ASSESSMENT_QUESTIONS_IMPORTED";
        public static final String ATTEMPTS_REGRADED = "ASSESSMENT_ATTEMPTS_REGRADED";
        public static final String QUIZ_DELETED = "ASSESSMENT_QUIZ_DELETED";
        public static final String COMPETENCY_DELETED = "ASSESSMENT_COMPETENCY_DELETED";
        public static final String ATTEMPT_FORCE_SUBMITTED = "ASSESSMENT_ATTEMPT_FORCE_SUBMITTED";

        private AuditAction() {
        }
    }
}
