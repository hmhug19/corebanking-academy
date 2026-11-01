package vn.com.coreacademy.learning.service;

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

    Optional<QuizRef> find(Long quizId);

    /**
     * ID các khóa mà học viên đã thi bài thi cuối khóa (PUBLISHED, chưa xóa) ít nhất một lượt đã chấm và chưa đạt lượt
     * nào. Khóa chưa có bài thi cuối, hoặc học viên chưa thi, không nằm trong tập. Tập nhỏ: tối đa một khóa mỗi bài
     * thi cuối học viên từng thi.
     */
    Set<Long> failedFinalExamCourseIds(String userId);
}
