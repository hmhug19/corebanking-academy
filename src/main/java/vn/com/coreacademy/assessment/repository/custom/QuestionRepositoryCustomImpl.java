package vn.com.coreacademy.assessment.repository.custom;

import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentLimits;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuestionRepositoryCustomImpl implements QuestionRepositoryCustom {
    private final EntityManager entityManager;

    @Override
    public Page<AssessmentViews.QuestionSummary> search(Filter filter, Pageable page) {
        NativeSearchQuery sql = new NativeSearchQuery("""
            from learning_hub.questions q
            left join learning_hub.competencies c on c.competency_id = q.competency_id
            """);
        sql.and("not q.is_deleted");
        sql.and("q.course_id = :course");
        sql.bind("course", filter.courseId());
        String term = NativeSearchQuery.containsPattern(filter.q());
        if (term != null) {
            sql.and("lower(coalesce(q.code, '')) like :term escape '!' or lower(q.content) like :term escape '!'");
            sql.bind("term", term);
        }
        if (filter.lessonId() != null) {
            sql.and("q.lesson_id = :lesson");
            sql.bind("lesson", filter.lessonId());
        }
        if (filter.competencyId() != null) {
            sql.and("q.competency_id = :competency");
            sql.bind("competency", filter.competencyId());
        }
        if (filter.questionType() != null) {
            sql.and("q.question_type = :type");
            sql.bind("type", filter.questionType());
        }
        if (filter.difficulty() != null) {
            sql.and("q.difficulty = :difficulty");
            sql.bind("difficulty", filter.difficulty());
        }
        if (filter.status() != null) {
            sql.and("q.status = :status");
            sql.bind("status", filter.status());
        }
        sql.bindProjection("preview", AssessmentLimits.CONTENT_PREVIEW_LENGTH);
        // Số đề đang dùng câu chỉ tính đề chưa xóa mềm; đếm bằng subquery để không nhân dòng khi join pool.
        return sql.page(entityManager, """
            select q.question_id, q.code, q.course_id, q.lesson_id, q.competency_id, c.name, q.question_type,
                   left(q.content, :preview), q.difficulty, q.points, q.status, q.content_version,
                   (select count(*) from learning_hub.question_options o
                    where o.question_id = q.question_id and not o.is_deleted),
                   (select count(*) from learning_hub.question_options o
                    where o.question_id = q.question_id and not o.is_deleted and o.is_correct),
                   (select count(*) from learning_hub.quiz_questions qq
                    join learning_hub.quizzes z on z.quiz_id = qq.quiz_id and not z.is_deleted
                    where qq.question_id = q.question_id),
                   q.row_version, q.update_time
            """, AssessmentViews.QuestionSummary.class, page, Map.of("id", "q.question_id", "code", "q.code",
                "updateTime", "q.update_time"));
    }
}
