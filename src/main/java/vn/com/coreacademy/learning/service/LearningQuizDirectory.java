package vn.com.coreacademy.learning.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Cổng để Learning kiểm tra bài kiểm tra mà một bài học loại quiz trỏ tới. Learning khai báo, Assessment cài đặt,
 * nên Learning không import lớp nào của Assessment.
 */
public interface LearningQuizDirectory {
    /**
     * Thông tin tối thiểu của đề: khóa sở hữu, trạng thái (DRAFT/PUBLISHED/CLOSED), cờ xóa mềm, loại đề và cấu hình
     * làm bài (điểm đạt, số lượt thi, cho thi lại sau khi đạt) để chụp lại vào dòng bài thi cuối khóa.
     */
    record QuizRef(Long quizId, Long courseId, String status, boolean deleted, String quizType,
                   BigDecimal passPercent, Integer maxAttempts, boolean allowRetakeAfterPass) {
        public boolean published() {
            return !deleted && "PUBLISHED".equals(status);
        }

        public boolean finalExam() {
            return "FINAL_EXAM".equals(quizType);
        }
    }

    /**
     * Điểm của một đề đã xuất bản với một học viên. bestScore/finalScore/passed đúng bằng AttemptSummary của màn giới
     * thiệu đề (finalScore và passed theo scoreMethod của đề); null khi học viên chưa có lượt chấm nào.
     */
    record QuizScore(Long quizId, Long courseId, String title, String quizType, BigDecimal bestScore,
                     BigDecimal finalScore, boolean passed, LastAttempt last) {
        public boolean finalExam() {
            return "FINAL_EXAM".equals(quizType);
        }
    }

    /** Lượt chấm gần nhất của một đề; passed là kết quả của chính lượt đó, không theo scoreMethod. */
    record LastAttempt(Long attemptId, BigDecimal scorePercent, Boolean passed, Instant submittedAt) {
    }

    Optional<QuizRef> find(Long quizId);

    /** Chỉ xét lượt đã chấm đạt của chính lần ghi danh, không lấy điểm từ chu kỳ học cũ. */
    boolean hasPassed(String userId, Long enrollmentId, Long quizId);

    boolean hasGradedAttempt(String userId, Long enrollmentId, Long quizId);

    /**
     * Số bài kiểm tra khác nhau (PUBLISHED, chưa xóa) mà mỗi học viên đã đạt: có ít nhất một lượt đã chấm với
     * passed = true. Một truy vấn gom theo học viên; học viên chưa đạt bài nào không có trong kết quả và userIds rỗng
     * trả bản đồ rỗng mà không truy vấn.
     */
    Map<String, Integer> passedQuizCounts(Collection<String> userIds);

    /**
     * ID các khóa mà học viên đã thi bài thi cuối khóa (PUBLISHED, chưa xóa) ít nhất một lượt đã chấm và chưa đạt lượt
     * nào. Khóa chưa có bài thi cuối, hoặc học viên chưa thi, không nằm trong tập. Tập nhỏ: tối đa một khóa mỗi bài
     * thi cuối học viên từng thi.
     */
    Set<Long> failedFinalExamCourseIds(String userId);

    /**
     * Điểm của userId trên mọi đề PUBLISHED, chưa xóa của các khóa courseIds, mỗi đề một phần tử theo thứ tự quizId.
     * Hai truy vấn bất kể số khóa; courseIds rỗng trả danh sách rỗng mà không truy vấn.
     */
    List<QuizScore> scores(String userId, Collection<Long> courseIds);
}
