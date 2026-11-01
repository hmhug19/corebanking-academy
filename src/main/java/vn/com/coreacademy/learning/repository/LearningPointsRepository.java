package vn.com.coreacademy.learning.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;

/**
 * Truy vấn gom theo người cho điểm học tập: mỗi phần một truy vấn trên danh sách người, không truy vấn theo từng
 * người. Người không có dữ liệu cho phần đó không xuất hiện trong kết quả. Chỉ đọc bảng của Learning.
 */
@Repository
@RequiredArgsConstructor
public class LearningPointsRepository {
    private final EntityManager entityManager;

    /**
     * Số bài học đã hoàn thành, mỗi bài một lần dù học ở nhiều lần học hoặc nhiều phiên bản: định danh bài là
     * coalesce(source_lesson_id, lesson_id), xem CourseLesson.sourceLessonId.
     */
    public Map<String, Long> completedLessons(Collection<String> userIds) {
        return counts(userIds, """
            select e.user_id, count(distinct coalesce(l.source_lesson_id, l.lesson_id))
            from learning_hub.lesson_progress p
            join learning_hub.course_enrollments e on e.enrollment_id = p.enrollment_id
            join learning_hub.course_lessons l on l.lesson_id = p.lesson_id
            where e.user_id in (:users) and p.status = :completed
            group by e.user_id
            """);
    }

    /**
     * Tổng reward_points của phiên bản đã hoàn thành, mỗi khóa một lần: lấy lần học hoàn thành gần nhất (cycle_no
     * lớn nhất) của từng khóa, nên học lại một khóa không nhân đôi điểm thưởng.
     */
    public Map<String, Long> courseRewards(Collection<String> userIds) {
        return counts(userIds, """
            select c.user_id,
                   sum(coalesce((select max(r.reward_points)
                                 from learning_hub.course_completion_rules r
                                 where r.revision_id = c.revision_id), 0))
            from (
                select distinct on (e.user_id, e.course_id) e.user_id, e.course_id, e.revision_id
                from learning_hub.course_enrollments e
                where e.user_id in (:users) and e.status = :completed
                order by e.user_id, e.course_id, e.cycle_no desc
            ) c
            group by c.user_id
            """);
    }

    /**
     * Số ngày liên tiếp kết thúc ở ngày học gần nhất, chỉ tính khi ngày đó là hôm nay hoặc hôm qua (today là ngày
     * Asia/Ho_Chi_Minh của bên gọi). Một ngày có thể có nhiều dòng (bảng không UNIQUE) nên đếm theo ngày khác nhau.
     */
    public Map<String, Long> streakDays(Collection<String> userIds, LocalDate today) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<?> rows = entityManager.createNativeQuery("""
            select r.user_id, count(*)
            from (
                select d.user_id, d.activity_date,
                       row_number() over (partition by d.user_id order by d.activity_date desc) as rn,
                       max(d.activity_date) over (partition by d.user_id) as last_day
                from (select distinct user_id, activity_date
                      from learning_hub.learning_activity_days
                      where user_id in (:users)) d
            ) r
            where r.last_day >= :yesterday and r.activity_date = r.last_day - (r.rn - 1)::int
            group by r.user_id
            """)
            .setParameter("users", userIds)
            .setParameter("yesterday", today.minusDays(1))
            .getResultList();
        return toMap(rows);
    }

    private Map<String, Long> counts(Collection<String> userIds, String sql) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return toMap(entityManager.createNativeQuery(sql)
            .setParameter("users", userIds)
            .setParameter("completed", ProgressStatus.COMPLETED)
            .getResultList());
    }

    private Map<String, Long> toMap(List<?> rows) {
        return rows.stream().map(Object[].class::cast).collect(Collectors.toUnmodifiableMap(
            row -> (String) row[0], row -> ((Number) row[1]).longValue()));
    }
}
