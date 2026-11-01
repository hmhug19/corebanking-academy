package vn.com.coreacademy.assessment.repository.custom;

import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuizType;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ScoreMethod;
import vn.com.coreacademy.assessment.dto.AssessmentDashboard;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;

@Repository
@RequiredArgsConstructor
public class AssessmentDashboardRepository {
    private final EntityManager entityManager;

    public AssessmentDashboard.Counts counts(String userId) {
        return (AssessmentDashboard.Counts) entityManager.createNativeQuery("""
            select count(*), count(*) filter (where case when q.score_method = :last
                                                        then r.last_passed else r.ever_passed end)
            from learning_hub.quiz_user_results r
            join learning_hub.quizzes q on q.quiz_id = r.quiz_id and not q.is_deleted
            where r.user_id = :user and q.quiz_type = :finalExam
            """, AssessmentDashboard.Counts.class).setParameter("user", userId)
            .setParameter("last", ScoreMethod.LAST).setParameter("finalExam", QuizType.FINAL_EXAM)
            .getSingleResult();
    }

    /** Cả hai kết quả đều lấy lượt thi thật để tên, thời gian và trạng thái đạt khớp nhau. */
    public AssessmentDashboard.Score score(String userId, boolean best) {
        String order = best ? "a.score_percent desc, a.submitted_at desc, a.attempt_id desc"
            : "a.submitted_at desc, a.attempt_id desc";
        List<?> rows = entityManager.createNativeQuery("""
            select q.quiz_id, q.title, q.course_id, a.attempt_id, a.enrollment_id,
                   a.score_percent, a.passed, a.submitted_at
            from learning_hub.quiz_user_results r
            join learning_hub.quizzes q on q.quiz_id = r.quiz_id and not q.is_deleted
            join learning_hub.quiz_attempts a on a.attempt_id =
                case when :best then r.best_attempt_id else r.last_attempt_id end
            where r.user_id = :user and a.user_id = :user and a.status in (:graded)
              and q.quiz_type = :finalExam
            order by
            """ + order, AssessmentDashboard.Score.class).setParameter("user", userId)
            .setParameter("best", best).setParameter("finalExam", QuizType.FINAL_EXAM)
            .setParameter("graded", List.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED))
            .setMaxResults(1).getResultList();
        return rows.isEmpty() ? null : (AssessmentDashboard.Score) rows.get(0);
    }

    /** Chọn một lượt mỗi đề trước khi cộng điểm; không AVG phần trăm của các nhóm có trọng số khác nhau. */
    public Page<AssessmentDashboard.Competency> competencies(String userId, String method, Pageable page) {
        NativeSearchQuery sql = new NativeSearchQuery("""
            from (
                select s.competency_id, count(*) as quiz_count, sum(s.question_count)::bigint as question_count,
                       sum(s.correct_count)::bigint as correct_count, sum(s.earned_score) as earned_score,
                       sum(s.max_score) as max_score
                from learning_hub.quiz_user_results r
                join learning_hub.quizzes q on q.quiz_id = r.quiz_id and not q.is_deleted
                join learning_hub.quiz_attempts a on a.attempt_id =
                    case when :method = :last then r.last_attempt_id else r.best_attempt_id end
                join learning_hub.attempt_competency_scores s on s.attempt_id = a.attempt_id
                where r.user_id = :user and a.user_id = :user and a.status in (:graded)
                  and q.quiz_type = :finalExam
                group by s.competency_id
            ) x
            left join learning_hub.competencies c on c.competency_id = x.competency_id
            """);
        sql.bind("user", userId);
        sql.bind("method", method);
        sql.bind("last", ScoreMethod.LAST);
        sql.bind("finalExam", QuizType.FINAL_EXAM);
        sql.bind("graded", List.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED));
        return sql.page(entityManager, """
            select x.competency_id, c.code, c.name, x.quiz_count, x.question_count, x.correct_count,
                   x.earned_score, x.max_score, round(x.earned_score * 100 / nullif(x.max_score, 0), 2)
            """, AssessmentDashboard.Competency.class, page, Map.of("id", "x.competency_id"));
    }
}
