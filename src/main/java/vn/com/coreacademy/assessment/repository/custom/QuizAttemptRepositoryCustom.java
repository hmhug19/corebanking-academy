package vn.com.coreacademy.assessment.repository.custom;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import vn.com.coreacademy.assessment.dto.AssessmentViews;

public interface QuizAttemptRepositoryCustom {
    /** quizId bắt buộc và đã được service kiểm quyền quản lý khóa; from/to lọc theo thời điểm bắt đầu. */
    record Filter(Long quizId, String userId, String status, Boolean passed, Instant from, Instant to) {
    }

    Page<AssessmentViews.AttemptListRow> search(Filter filter, Pageable page);

    /**
     * userId luôn là caller do service xác định; quizId/status là filter tùy chọn. gradedOnly chỉ giữ lượt đã chấm
     * (SUBMITTED, AUTO_SUBMITTED) và kết hợp với status theo AND.
     */
    record MineFilter(String userId, Long quizId, String status, boolean gradedOnly) {
    }

    Page<AssessmentViews.MyAttemptRow> searchMine(MineFilter filter, Pageable page);

    /**
     * Khóa có bài thi cuối PUBLISHED (chưa xóa) mà userId có lượt đã chấm (SUBMITTED, AUTO_SUBMITTED) và không lượt
     * nào đạt. Một truy vấn, gom theo khóa.
     */
    Set<Long> failedFinalExamCourseIds(String userId);

    /**
     * Số đề PUBLISHED (chưa xóa) khác nhau mà mỗi người trong userIds có lượt đã chấm đạt. Một truy vấn gom theo
     * người; người chưa đạt đề nào không có trong kết quả.
     */
    Map<String, Integer> passedQuizCounts(Collection<String> userIds);
}
