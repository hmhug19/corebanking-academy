package vn.com.coreacademy.assessment.repository.custom;

import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.entity.QuizAttempt;

/** Read model có thể dựng lại; lịch sử thi và snapshot đáp án luôn là nguồn chuẩn. */
@Repository
@RequiredArgsConstructor
public class AssessmentResultRepository {
    private static final List<String> GRADED = List.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED);

    private final EntityManager entityManager;

    /**
     * Serialize theo đề/học viên sau khóa lượt của bên gọi; chỉ đọc các lượt khác, không khóa chúng.
     * Recompute thay vì cộng dồn để retry, chấm lại và VOIDED không làm tăng sai số lượt/điểm.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void refresh(QuizAttempt attempt) {
        entityManager.createNativeQuery("""
            select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))
            """).setParameter("key", "assessment-result:" + attempt.getQuizId() + ":" + attempt.getUserId())
            .getSingleResult();
        entityManager.flush();

        entityManager.createNativeQuery("""
            delete from learning_hub.attempt_competency_scores where attempt_id = :id
            """).setParameter("id", attempt.getId()).executeUpdate();
        entityManager.createNativeQuery("""
            insert into learning_hub.attempt_competency_scores
                (attempt_id, competency_id, question_count, correct_count, earned_score, max_score, update_time)
            select a.attempt_id, q.competency_id, count(*), count(*) filter (where ans.is_correct),
                   coalesce(sum(ans.score_awarded), 0), sum(q.points), clock_timestamp()
            from learning_hub.quiz_attempts a
            join learning_hub.attempt_questions q on q.attempt_id = a.attempt_id
            left join learning_hub.attempt_answers ans on ans.attempt_question_id = q.attempt_question_id
            where a.attempt_id = :id and a.status in (:graded) and q.competency_id is not null
            group by a.attempt_id, q.competency_id
            """).setParameter("id", attempt.getId()).setParameter("graded", GRADED).executeUpdate();

        pair(entityManager.createNativeQuery("""
            delete from learning_hub.quiz_user_results where quiz_id = :quiz and user_id = :user
            """), attempt).executeUpdate();
        pair(entityManager.createNativeQuery("""
            with ranked as (
                select a.*,
                       row_number() over (
                           order by score_percent desc nulls last, submitted_at desc, attempt_id desc) as best_no,
                       row_number() over (order by submitted_at desc, attempt_id desc) as last_no
                from learning_hub.quiz_attempts a
                where a.quiz_id = :quiz and a.user_id = :user and a.status in (:graded)
            )
            insert into learning_hub.quiz_user_results
                (quiz_id, user_id, graded_attempt_count, best_attempt_id, last_attempt_id,
                 best_score_percent, last_score_percent, ever_passed, last_passed, last_submitted_at, update_time)
            select :quiz, :user, count(*),
                   max(attempt_id) filter (where best_no = 1), max(attempt_id) filter (where last_no = 1),
                   max(score_percent) filter (where best_no = 1), max(score_percent) filter (where last_no = 1),
                   bool_or(passed), bool_or(passed) filter (where last_no = 1), max(submitted_at), clock_timestamp()
            from ranked having count(*) > 0
            """), attempt).setParameter("graded", GRADED).executeUpdate();
    }

    private Query pair(Query query, QuizAttempt attempt) {
        return query.setParameter("quiz", attempt.getQuizId()).setParameter("user", attempt.getUserId());
    }
}
