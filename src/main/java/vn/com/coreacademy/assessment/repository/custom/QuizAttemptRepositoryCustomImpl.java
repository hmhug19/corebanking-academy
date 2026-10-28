package vn.com.coreacademy.assessment.repository.custom;

import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;

@RequiredArgsConstructor
@Transactional(readOnly = true)
public class QuizAttemptRepositoryCustomImpl implements QuizAttemptRepositoryCustom {
    private final EntityManager entityManager;

    @Override
    public Page<AssessmentViews.AttemptListRow> search(Filter filter, Pageable page) {
        NativeSearchQuery sql = new NativeSearchQuery("from learning_hub.quiz_attempts a\n");
        sql.and("a.quiz_id = :quiz");
        sql.bind("quiz", filter.quizId());
        if (filter.userId() != null) {
            sql.and("a.user_id = :user");
            sql.bind("user", filter.userId());
        }
        if (filter.status() != null) {
            sql.and("a.status = :status");
            sql.bind("status", filter.status());
        }
        if (filter.passed() != null) {
            sql.and("a.passed = :passed");
            sql.bind("passed", filter.passed());
        }
        if (filter.from() != null) {
            sql.and("a.started_at >= :from");
            sql.bind("from", filter.from());
        }
        if (filter.to() != null) {
            sql.and("a.started_at < :to");
            sql.bind("to", filter.to());
        }
        return sql.page(entityManager, """
            select a.attempt_id, a.user_id, a.attempt_no, a.status, a.submit_type, a.started_at, a.submitted_at,
                   a.total_questions, a.correct_count, a.score_percent, a.passed, a.org_id
            """, AssessmentViews.AttemptListRow.class, page, Map.of("id", "a.attempt_id",
                "startedAt", "a.started_at", "submittedAt", "a.submitted_at", "scorePercent", "a.score_percent"));
    }

    @Override
    public Page<AssessmentViews.MyAttemptRow> searchMine(MineFilter filter, Pageable page) {
        // Giữ lịch sử cả khi đề đã xóa mềm: lượt thi là hồ sơ của học viên, không phụ thuộc vòng đời đề.
        NativeSearchQuery sql = new NativeSearchQuery("""
            from learning_hub.quiz_attempts a
            join learning_hub.quizzes z on z.quiz_id = a.quiz_id
            """);
        sql.and("a.user_id = :user");
        sql.bind("user", filter.userId());
        if (filter.quizId() != null) {
            sql.and("a.quiz_id = :quiz");
            sql.bind("quiz", filter.quizId());
        }
        if (filter.status() != null) {
            sql.and("a.status = :status");
            sql.bind("status", filter.status());
        }
        return sql.page(entityManager, """
            select a.attempt_id, a.quiz_id, z.title, z.course_id, a.attempt_no, a.status, a.submit_type,
                   a.started_at, a.submitted_at, a.total_questions, a.correct_count, a.score_percent, a.passed
            """, AssessmentViews.MyAttemptRow.class, page, Map.of("id", "a.attempt_id", "startedAt", "a.started_at",
                "submittedAt", "a.submitted_at"));
    }
}
