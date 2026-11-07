package vn.com.coreacademy.learning.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;

/**
 * Dữ liệu cho chuỗi ngày học: số bài học hoàn thành và bài kiểm tra đã nộp mỗi ngày Asia/Ho_Chi_Minh, và lịch nghỉ
 * chung. Chỉ đọc. Mở bài hay mở trang không để lại dòng nào ở các bảng này nên không tạo ngày đủ điều kiện.
 */
@Repository
@RequiredArgsConstructor
public class LearningStreakRepository {
    private final EntityManager entityManager;

    /** Số bài học hoàn thành theo ngày; mỗi lần học hoàn thành một bài là một dòng tiến độ. */
    public Map<LocalDate, Integer> lessonsPerDay(String userId) {
        return perDay("""
            select (p.completed_at at time zone 'Asia/Ho_Chi_Minh')::date, count(*)
            from learning_hub.lesson_progress p
            join learning_hub.course_enrollments e on e.enrollment_id = p.enrollment_id
            where e.user_id = :user and p.status = :completed and p.completed_at is not null
            group by 1
            """, userId, true);
    }

    /** Số bài kiểm tra đã nộp theo ngày (nộp tay hoặc tự nộp); lượt bị hủy không tính. */
    public Map<LocalDate, Integer> quizzesPerDay(String userId) {
        return perDay("""
            select (a.submitted_at at time zone 'Asia/Ho_Chi_Minh')::date, count(*)
            from learning_hub.quiz_attempts a
            where a.user_id = :user and a.status in ('SUBMITTED', 'AUTO_SUBMITTED') and a.submitted_at is not null
            group by 1
            """, userId, false);
    }

    /** Lịch chung: ngày → HOLIDAY hoặc MAKEUP_WORKDAY. */
    public Map<LocalDate, String> calendar() {
        List<?> rows = entityManager.createNativeQuery("""
            select calendar_date, day_type from learning_hub.working_calendar_days
            """).getResultList();
        return rows.stream().map(Object[].class::cast)
            .collect(Collectors.toMap(row -> ((Date) row[0]).toLocalDate(), row -> (String) row[1]));
    }

    private Map<LocalDate, Integer> perDay(String sql, String userId, boolean withStatus) {
        var query = entityManager.createNativeQuery(sql).setParameter("user", userId);
        if (withStatus) {
            query.setParameter("completed", ProgressStatus.COMPLETED);
        }
        List<?> rows = query.getResultList();
        return rows.stream().map(Object[].class::cast).collect(Collectors.toMap(
            row -> ((Date) row[0]).toLocalDate(), row -> ((Number) row[1]).intValue()));
    }
}
