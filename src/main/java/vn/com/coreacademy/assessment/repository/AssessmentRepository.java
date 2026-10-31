package vn.com.coreacademy.assessment.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ItemStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuestionType;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuizStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuizType;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.entity.AssessmentRecord;
import vn.com.coreacademy.assessment.entity.AttemptAnswer;
import vn.com.coreacademy.assessment.entity.AttemptQuestion;
import vn.com.coreacademy.assessment.entity.AttemptRegrade;
import vn.com.coreacademy.assessment.entity.Competency;
import vn.com.coreacademy.assessment.entity.Question;
import vn.com.coreacademy.assessment.entity.QuestionOption;
import vn.com.coreacademy.assessment.entity.Quiz;
import vn.com.coreacademy.assessment.entity.QuizAttempt;
import vn.com.coreacademy.assessment.entity.QuizAttemptGrant;
import vn.com.coreacademy.assessment.entity.QuizQuestion;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.common.exception.BusinessException;

/** Truy cập scalar entity của Quiz Engine, không có quan hệ JPA hay FK; query nghiệp vụ chỉ đặt tại đây. */
@Repository
@RequiredArgsConstructor
public class AssessmentRepository {
    private final EntityManager entityManager;

    public <T> T require(Class<T> type, Long id) {
        T value = id == null ? null : entityManager.find(type, id);
        if (value == null) {
            throw new BusinessException(AssessmentResponseCode.NOT_FOUND);
        }
        return value;
    }

    public <T extends AssessmentRecord> T save(T value, String actor) {
        if (value.getCreatedBy() == null) {
            value.setCreatedBy(actor);
        }
        value.setUpdatedBy(actor);
        if (!entityManager.contains(value)) {
            entityManager.persist(value);
        }
        return value;
    }

    public <T> T persist(T value) {
        entityManager.persist(value);
        return value;
    }

    public void flush() {
        entityManager.flush();
    }

    /**
     * Tăng row_version ngay (không đợi commit) cả khi chỉ dữ liệu con như phương án hoặc pool thay đổi, để người sửa
     * song song nhận xung đột và response trả đúng version mới.
     */
    public void touchVersion(AssessmentRecord value) {
        entityManager.lock(value, LockModeType.PESSIMISTIC_FORCE_INCREMENT);
    }

    /**
     * SELECT FOR UPDATE: tuần tự hóa lưu đáp án, nộp bài, hủy lượt và job tự nộp trên cùng một lượt. Nếu entity đã
     * được tải trước trong transaction thì đọc lại kèm khóa, tránh quyết định trên trạng thái cũ.
     */
    public QuizAttempt lockAttempt(Long attemptId) {
        QuizAttempt attempt = require(QuizAttempt.class, attemptId);
        entityManager.refresh(attempt, LockModeType.PESSIMISTIC_WRITE);
        return attempt;
    }

    /**
     * Khóa giao dịch theo cặp (đề, học viên) để hai request bắt đầu thi đồng thời không cùng tạo lượt hoặc trùng số
     * thứ tự; unique index lượt IN_PROGRESS vẫn là lớp chặn cuối ở DB.
     */
    public void lockLearnerQuiz(Long quizId, String userId) {
        entityManager.createNativeQuery("""
            select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))
            """).setParameter("key", "assessment-attempt:" + quizId + ":" + userId).getSingleResult();
    }

    /** Danh mục năng lực nhỏ (master data) nên tải toàn bộ bản ghi chưa xóa; service lọc trạng thái. */
    public List<Competency> competencies() {
        return query("""
            select c
            from Competency c
            where c.deleted = false
            order by c.sortNo, c.id
            """, Competency.class, Map.of());
    }

    public List<Competency> competencies(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return query("""
            select c
            from Competency c
            where c.id in :ids
            """, Competency.class, Map.of("ids", ids));
    }

    public List<Competency> competenciesByCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return query("""
            select c
            from Competency c
            where c.deleted = false
            and c.code in :codes
            """, Competency.class, Map.of("codes", codes));
    }

    public List<Question> questionsByCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return List.of();
        }
        return query("""
            select q
            from Question q
            where q.deleted = false
            and q.code in :codes
            """, Question.class, Map.of("codes", codes));
    }

    public boolean competencyCodeExists(String code, Long excludeId) {
        return exists("""
            select count(c)
            from Competency c
            where c.deleted = false
            and c.code = :code
            and c.id <> :exclude
            """, Map.of("code", code, "exclude", excludeId == null ? -1L : excludeId));
    }

    public boolean questionCodeExists(String code, Long excludeId) {
        return exists("""
            select count(q)
            from Question q
            where q.deleted = false
            and q.code = :code
            and q.id <> :exclude
            """, Map.of("code", code, "exclude", excludeId == null ? -1L : excludeId));
    }

    public List<QuestionOption> options(Collection<Long> questionIds) {
        if (questionIds.isEmpty()) {
            return List.of();
        }
        return query("""
            select o
            from QuestionOption o
            where o.questionId in :ids
            and o.deleted = false
            order by o.questionId, o.sortNo, o.id
            """, QuestionOption.class, Map.of("ids", questionIds));
    }

    public List<Question> questions(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return query("""
            select q
            from Question q
            where q.id in :ids
            """, Question.class, Map.of("ids", ids));
    }

    /** Câu đã từng được phát trong bất kỳ lượt thi nào; sửa nội dung khi đó phải tăng content_version. */
    public boolean questionServed(Long questionId) {
        return exists("""
            select count(a)
            from AttemptQuestion a
            where a.questionId = :id
            """, Map.of("id", questionId));
    }

    public List<AssessmentViews.QuizRef> quizzesUsing(Long questionId) {
        return query("""
            select z.id, z.title, z.status
            from QuizQuestion p, Quiz z
            where p.quizId = z.id
            and p.questionId = :id
            and z.deleted = false
            order by z.id
            """, AssessmentViews.QuizRef.class, Map.of("id", questionId));
    }

    /** Câu hỏi trong danh sách đang nằm trong pool của ít nhất một đề chưa xóa. */
    public List<Long> questionsInPools(Collection<Long> questionIds) {
        return query("""
            select distinct p.questionId
            from QuizQuestion p, Quiz z
            where p.quizId = z.id
            and z.deleted = false
            and p.questionId in :ids
            """, Long.class, Map.of("ids", questionIds));
    }

    public List<Quiz> publishedQuizzesUsing(Collection<Long> questionIds) {
        return query("""
            select distinct z
            from QuizQuestion p, Quiz z
            where p.quizId = z.id
            and z.deleted = false
            and z.status = :published
            and p.questionId in :ids
            """, Quiz.class, Map.of("ids", questionIds, "published", QuizStatus.PUBLISHED));
    }

    /** Đề đã xuất bản, chưa xóa của khóa; đề DRAFT/CLOSED không hiện cho học viên. */
    public List<Quiz> publishedQuizzes(Long courseId) {
        return query("""
            select z
            from Quiz z
            where z.deleted = false
            and z.status = :published
            and z.courseId = :course
            order by z.id
            """, Quiz.class, Map.of("course", courseId, "published", QuizStatus.PUBLISHED));
    }

    public List<Long> inProgressAttemptIds(Long quizId) {
        return query("""
            select a.id
            from QuizAttempt a
            where a.quizId = :quiz
            and a.status = :status
            order by a.id
            """, Long.class, Map.of("quiz", quizId, "status", AttemptStatus.IN_PROGRESS));
    }

    /** Có bất kỳ lượt nào (kể cả VOIDED) thì đề là lịch sử thi, không được xóa. */
    public boolean quizHasAttempts(Long quizId) {
        return exists("""
            select count(a)
            from QuizAttempt a
            where a.quizId = :quiz
            """, Map.of("quiz", quizId));
    }

    /** Nhóm còn câu hỏi chưa xóa gắn vào, hoặc còn nhóm con chưa xóa. */
    public boolean competencyInUse(Long competencyId) {
        return exists("""
            select count(q)
            from Question q
            where q.competencyId = :id
            and q.deleted = false
            """, Map.of("id", competencyId)) || exists("""
            select count(c)
            from Competency c
            where c.parentId = :id
            and c.deleted = false
            """, Map.of("id", competencyId));
    }

    /** Snapshot câu hỏi trong các lượt đã chấm của đề; lượt đang làm/VOIDED không bị chấm lại. */
    public List<AttemptQuestion> gradedSnapshots(Long quizId, Long questionId) {
        return query("""
            select q
            from AttemptQuestion q, QuizAttempt a
            where q.attemptId = a.id
            and a.quizId = :quiz
            and q.questionId = :question
            and a.status in :graded
            order by a.id
            """, AttemptQuestion.class, Map.of("quiz", quizId, "question", questionId,
                "graded", List.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED)));
    }

    public List<AttemptRegrade> regrades(Long attemptId) {
        return query("""
            select r
            from AttemptRegrade r
            where r.attemptId = :id
            order by r.regradedAt, r.id
            """, AttemptRegrade.class, Map.of("id", attemptId));
    }

    public List<QuizAttemptGrant> grants(Long quizId, String userId) {
        if (userId == null) {
            return query("""
                select g
                from QuizAttemptGrant g
                where g.quizId = :quiz
                order by g.grantedAt desc, g.id desc
                """, QuizAttemptGrant.class, Map.of("quiz", quizId));
        }
        return query("""
            select g
            from QuizAttemptGrant g
            where g.quizId = :quiz
            and g.userId = :user
            order by g.grantedAt desc, g.id desc
            """, QuizAttemptGrant.class, Map.of("quiz", quizId, "user", userId));
    }

    public List<QuizQuestion> pool(Long quizId) {
        return query("""
            select p
            from QuizQuestion p
            where p.quizId = :id
            order by p.sortNo, p.id
            """, QuizQuestion.class, Map.of("id", quizId));
    }

    public void clearPool(Long quizId) {
        entityManager.createQuery("""
            delete from QuizQuestion p
            where p.quizId = :id
            """).setParameter("id", quizId).executeUpdate();
    }

    public long activePoolSize(Long quizId) {
        return count("""
            select count(p)
            from QuizQuestion p, Question q
            where p.questionId = q.id
            and p.quizId = :id
            and q.deleted = false
            and q.status = :active
            """, Map.of("id", quizId, "active", ItemStatus.ACTIVE));
    }

    public boolean poolHasActiveMultiple(Long quizId) {
        return exists("""
            select count(p)
            from QuizQuestion p, Question q
            where p.questionId = q.id
            and p.quizId = :id
            and q.deleted = false
            and q.status = :active
            and q.questionType = :multiple
            """, Map.of("id", quizId, "active", ItemStatus.ACTIVE, "multiple", QuestionType.MULTIPLE));
    }

    public boolean finalQuizExists(Long courseId, Long excludeId) {
        return exists("""
            select count(z)
            from Quiz z
            where z.deleted = false
            and z.courseId = :course
            and z.quizType = :final
            and z.id <> :exclude
            """, Map.of("course", courseId, "final", QuizType.FINAL_EXAM,
                "exclude", excludeId == null ? -1L : excludeId));
    }

    public List<QuizAttempt> attempts(Long quizId, String userId) {
        return query("""
            select a
            from QuizAttempt a
            where a.quizId = :quiz
            and a.userId = :user
            order by a.attemptNo
            """, QuizAttempt.class, Map.of("quiz", quizId, "user", userId));
    }

    public int grantedAttempts(Long quizId, String userId) {
        Long total = entityManager.createQuery("""
            select sum(g.extraAttempts)
            from QuizAttemptGrant g
            where g.quizId = :quiz
            and g.userId = :user
            and g.deleted = false
            """, Long.class).setParameter("quiz", quizId).setParameter("user", userId).getSingleResult();
        return total == null ? 0 : total.intValue();
    }

    public List<AttemptQuestion> attemptQuestions(Long attemptId) {
        return query("""
            select q
            from AttemptQuestion q
            where q.attemptId = :id
            order by q.displayOrder
            """, AttemptQuestion.class, Map.of("id", attemptId));
    }

    public List<AttemptAnswer> answers(Long attemptId) {
        return query("""
            select a
            from AttemptAnswer a, AttemptQuestion q
            where a.attemptQuestionId = q.id
            and q.attemptId = :id
            """, AttemptAnswer.class, Map.of("id", attemptId));
    }

    public Optional<AttemptAnswer> answer(Long attemptQuestionId) {
        return query("""
            select a
            from AttemptAnswer a
            where a.attemptQuestionId = :id
            """, AttemptAnswer.class, Map.of("id", attemptQuestionId)).stream().findFirst();
    }

    /** Lượt đang làm đã quá deadline + ân hạn (snapshot của chính lượt), cũ nhất trước. */
    public List<Long> expiredAttemptIds(Instant now, int limit) {
        List<?> rows = entityManager.createNativeQuery("""
            select a.attempt_id
            from learning_hub.quiz_attempts a
            where a.status = :status
              and a.deadline_at + a.grace_seconds * interval '1 second' < :now
            order by a.deadline_at
            limit :limit
            """).setParameter("status", AttemptStatus.IN_PROGRESS).setParameter("now", now)
            .setParameter("limit", limit).getResultList();
        return rows.stream().map(row -> ((Number) row).longValue()).toList();
    }

    private <T> List<T> query(String jpql, Class<T> type, Map<String, Object> parameters) {
        var query = entityManager.createQuery(jpql, type);
        parameters.forEach(query::setParameter);
        return query.getResultList();
    }

    private long count(String jpql, Map<String, Object> parameters) {
        var query = entityManager.createQuery(jpql, Long.class);
        parameters.forEach(query::setParameter);
        return query.getSingleResult();
    }

    private boolean exists(String jpql, Map<String, Object> parameters) {
        return count(jpql, parameters) > 0;
    }
}
