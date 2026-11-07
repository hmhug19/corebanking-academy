package vn.com.coreacademy.assessment.repository.custom;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuizStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuizType;
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
        if (filter.gradedOnly()) {
            sql.and("a.status in (:graded)");
            sql.bind("graded", List.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED));
        }
        String term = NativeSearchQuery.containsPattern(filter.q());
        if (term != null) {
            sql.and("lower(z.title) like :term escape '!'");
            sql.bind("term", term);
        }
        if (filter.passed() != null) {
            sql.and("a.passed = :passed");
            sql.bind("passed", filter.passed());
        }
        if (filter.from() != null) {
            sql.and("a.submitted_at >= :from");
            sql.bind("from", filter.from());
        }
        if (filter.to() != null) {
            sql.and("a.submitted_at < :to");
            sql.bind("to", filter.to());
        }
        return sql.page(entityManager, """
            select a.attempt_id, a.quiz_id, z.title, z.course_id, a.attempt_no, a.status, a.submit_type,
                   a.started_at, a.submitted_at, a.total_questions, a.correct_count, a.score_percent, a.passed
            """, AssessmentViews.MyAttemptRow.class, page, Map.of("id", "a.attempt_id", "startedAt", "a.started_at",
                "submittedAt", "a.submitted_at"));
    }

    @Override
    public Set<Long> failedFinalExamCourseIds(String userId) {
        List<?> rows = entityManager.createNativeQuery("""
            select z.course_id
            from learning_hub.quizzes z
            join learning_hub.quiz_attempts a on a.quiz_id = z.quiz_id
            where a.user_id = :user and a.status in (:graded) and a.graded_at is not null
              and z.quiz_type = :finalExam and z.status = :published and not z.is_deleted
            group by z.course_id
            having not bool_or(coalesce(a.passed, false))
            """)
            .setParameter("user", userId)
            .setParameter("graded", List.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED))
            .setParameter("finalExam", QuizType.FINAL_EXAM)
            .setParameter("published", QuizStatus.PUBLISHED)
            .getResultList();
        return rows.stream().map(row -> ((Number) row).longValue()).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public Map<String, Integer> passedQuizCounts(Collection<String> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        List<?> rows = entityManager.createNativeQuery("""
            select a.user_id, count(distinct a.quiz_id)
            from learning_hub.quiz_attempts a
            join learning_hub.quizzes z on z.quiz_id = a.quiz_id
            where a.user_id in (:users) and a.status in (:graded) and a.graded_at is not null
              and a.passed = true and z.status = :published and not z.is_deleted
            group by a.user_id
            """)
            .setParameter("users", userIds)
            .setParameter("graded", List.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED))
            .setParameter("published", QuizStatus.PUBLISHED)
            .getResultList();
        return rows.stream().map(Object[].class::cast).collect(Collectors.toUnmodifiableMap(
            row -> (String) row[0], row -> ((Number) row[1]).intValue()));
    }
}
