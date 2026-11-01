package vn.com.coreacademy.learning.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Cổng để Learning kiểm tra bài kiểm tra mà một bài học loại quiz trỏ tới. Learning khai báo, Assessment cài đặt,
 * nên Learning không import lớp nào của Assessment.
 */
public interface LearningQuizDirectory {
    /** Thông tin tối thiểu của đề: khóa sở hữu, trạng thái (DRAFT/PUBLISHED/CLOSED) và cờ xóa mềm. */
    record QuizRef(Long quizId, Long courseId, String status, boolean deleted) {
        public boolean published() {
            return !deleted && "PUBLISHED".equals(status);
        }
    }

    /**
     * Điểm của một đề đã xuất bản với một học viên. bestScore/finalScore/passed đúng bằng AttemptSummary của màn giới
     * thiệu đề (finalScore và passed theo scoreMethod của đề); null khi học viên chưa có lượt chấm nào.
     */
    record QuizScore(Long quizId, Long courseId, String title, String quizType, BigDecimal bestScore,
                     BigDecimal finalScore, boolean passed, LastAttempt last) {
    }

    /** Lượt chấm gần nhất của một đề; passed là kết quả của chính lượt đó, không theo scoreMethod. */
    record LastAttempt(Long attemptId, BigDecimal scorePercent, Boolean passed, Instant submittedAt) {
    }

    Optional<QuizRef> find(Long quizId);

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
