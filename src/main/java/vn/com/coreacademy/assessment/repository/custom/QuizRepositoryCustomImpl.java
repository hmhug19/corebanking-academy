package vn.com.coreacademy.assessment.repository.custom;

import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuizRepositoryCustomImpl implements QuizRepositoryCustom {
    private final EntityManager entityManager;

    @Override
    public Page<AssessmentViews.QuizSummary> search(Filter filter, Pageable page) {
        NativeSearchQuery sql = new NativeSearchQuery("from learning_hub.quizzes z\n");
        sql.and("not z.is_deleted");
        sql.and("z.course_id = :course");
        sql.bind("course", filter.courseId());
        String term = NativeSearchQuery.containsPattern(filter.q());
        if (term != null) {
            sql.and("lower(z.title) like :term escape '!'");
            sql.bind("term", term);
        }
        if (filter.status() != null) {
            sql.and("z.status = :status");
            sql.bind("status", filter.status());
        }
        if (filter.quizType() != null) {
            sql.and("z.quiz_type = :type");
            sql.bind("type", filter.quizType());
        }
        sql.bindProjection("graded", List.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED));
        return sql.page(entityManager, """
            select z.quiz_id, z.course_id, z.title, z.quiz_type, z.status, z.selection_mode, z.question_count,
                   z.time_limit_minutes, z.pass_percent, z.max_attempts, z.open_at, z.close_at,
                   (select count(*) from learning_hub.quiz_attempts a
                    where a.quiz_id = z.quiz_id and a.status in (:graded)),
                   (select count(*) from learning_hub.quiz_attempts a
                    where a.quiz_id = z.quiz_id and a.status in (:graded) and a.passed),
                   (select count(*) from learning_hub.quiz_questions qq where qq.quiz_id = z.quiz_id),
                   z.row_version, z.update_time
            """, AssessmentViews.QuizSummary.class, page, Map.of("id", "z.quiz_id", "title", "z.title",
                "updateTime", "z.update_time"));
    }
}
