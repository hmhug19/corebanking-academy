package vn.com.coreacademy.assessment.repository;

import java.time.Instant;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.dto.AssessmentViews.ReviewLesson;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;

/**
 * Bài nên ôn lại cho một năng lực: câu sai của lượt thi đã chấm (câu bỏ trống tính là sai) cộng câu sai của câu hỏi
 * nhanh, trong khoảng thời gian gần đây, gom theo bài của câu hỏi. Chỉ đọc theo {@code user_id} (đã có chỉ mục ở
 * {@code quiz_attempts} và {@code checkpoint_answers}), không quét câu trả lời của người khác.
 */
@Repository
@RequiredArgsConstructor
public class CompetencyReviewRepository {
    private final JdbcClient jdbc;

    /**
     * Bài vẫn mở được (thuộc phiên bản của một lần học chưa rút) và chưa "qua" ở câu hỏi nhanh: bài bị bỏ khi hai câu
     * trả lời câu hỏi nhanh gần nhất của người học trong bài, cho năng lực này, đều đúng.
     */
    public List<ReviewLesson> lessons(String userId, long competencyId, Instant since, int limit) {
        return jdbc.sql("""
            with misses as (
                select q.lesson_id, a.submitted_at as missed_at
                from learning_hub.quiz_attempts a
                join learning_hub.attempt_questions aq on aq.attempt_id = a.attempt_id
                join learning_hub.questions q on q.question_id = aq.question_id
                left join learning_hub.attempt_answers ans on ans.attempt_question_id = aq.attempt_question_id
                where a.user_id = :user and a.status in (:graded) and a.submitted_at >= :since
                  and aq.competency_id = :competency and q.lesson_id is not null
                  and coalesce(ans.is_correct, false) = false
                union all
                select c.lesson_id, c.answered_at
                from learning_hub.checkpoint_answers c
                where c.user_id = :user and c.competency_id = :competency and not c.correct
                  and c.answered_at >= :since
            ), mastered as (
                select lesson_id
                from (
                    select lesson_id, correct,
                           row_number() over (partition by lesson_id order by answered_at desc, answer_id desc) as rn
                    from learning_hub.checkpoint_answers
                    where user_id = :user and competency_id = :competency
                ) last_two
                where rn <= 2
                group by lesson_id
                having count(*) = 2 and bool_and(correct)
            )
            select r.course_id, r.title as course_title, l.lesson_id, l.title as lesson_title,
                   count(*) as missed_count, max(m.missed_at) as last_missed_at
            from misses m
            join learning_hub.course_lessons l on l.lesson_id = m.lesson_id
            join learning_hub.course_chapters ch on ch.chapter_id = l.chapter_id
            join learning_hub.course_revisions r on r.revision_id = ch.revision_id
            where exists (
                    select 1 from learning_hub.course_enrollments e
                    where e.revision_id = ch.revision_id and e.user_id = :user and e.status <> :withdrawn)
              and not exists (select 1 from mastered x where x.lesson_id = m.lesson_id)
            group by r.course_id, r.title, l.lesson_id, l.title
            order by missed_count desc, last_missed_at desc, l.lesson_id
            limit :limit
            """).param("user", userId).param("competency", competencyId)
            .param("graded", List.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED))
            .param("since", java.sql.Timestamp.from(since)).param("withdrawn", ProgressStatus.WITHDRAWN)
            .param("limit", limit).query((rs, row) -> new ReviewLesson(rs.getLong("course_id"),
                rs.getString("course_title"), rs.getLong("lesson_id"), rs.getString("lesson_title"),
                rs.getLong("missed_count"), rs.getTimestamp("last_missed_at").toInstant())).list();
    }
}
