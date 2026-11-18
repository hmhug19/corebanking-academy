package vn.com.coreacademy.assessment.repository;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Câu hỏi nhanh cuối bài lấy từ flashcard đã công bố của khóa (đúng phiên bản đã duyệt) có {@code lesson_id} là bài
 * đang học. Chỉ SINGLE/MULTIPLE; câu hỏi, phương án và đáp án đúng đọc riêng để payload câu hỏi không bao giờ kèm cờ
 * đúng/sai.
 */
@Repository
@RequiredArgsConstructor
public class CheckpointRepository {
    private static final String VISIBLE = """
        from learning_hub.flashcards f
        join learning_hub.questions q on q.question_id = f.question_id
        join learning_hub.course_revisions r on r.revision_id = :revision and r.course_id = f.course_id
        where f.course_id = :course and f.status = 'PUBLISHED'
          and q.lesson_id = :lesson and q.question_type in ('SINGLE', 'MULTIPLE')
          and q.status = 'ACTIVE' and not q.is_deleted and f.approved_question_version = q.row_version
          and (q.program_id = r.program_id or (q.program_id is null and q.course_id = f.course_id))
        """;
    private final JdbcClient jdbc;

    public record Candidate(long questionId, long questionVersion, String questionType, String content) {
    }

    public record Option(long id, String content) {
    }

    public record Graded(long questionId, long questionVersion, long competencyId, String questionType,
                         String explanation) {
    }

    /**
     * Thứ tự chọn: chưa từng trả lời câu hỏi nhanh, rồi câu từng trả lời sai, rồi còn lại; mỗi nhóm ngẫu nhiên. Việc
     * chọn xong ngay trong SQL nên không tải cả bộ thẻ của bài.
     */
    public List<Candidate> pick(long courseId, long revisionId, long lessonId, String user, int limit) {
        return jdbc.sql("""
            select q.question_id, q.row_version as question_version, q.question_type, q.content
            """ + VISIBLE + """
            order by case
                         when not exists (select 1 from learning_hub.checkpoint_answers a
                                          where a.user_id = :user and a.question_id = q.question_id) then 0
                         when exists (select 1 from learning_hub.checkpoint_answers a
                                      where a.user_id = :user and a.question_id = q.question_id
                                        and not a.correct) then 1
                         else 2
                     end, random()
            limit :limit
            """).param("course", courseId).param("revision", revisionId).param("lesson", lessonId)
            .param("user", user).param("limit", limit).query(Candidate.class).list();
    }

    public List<Option> options(long questionId) {
        return jdbc.sql("""
            select option_id as id, content from learning_hub.question_options
            where question_id = :id and not is_deleted order by sort_no, option_id
            """).param("id", questionId).query(Option.class).list();
    }

    /** Câu hỏi đang là câu hỏi nhanh hợp lệ của bài ở đúng phiên bản; rỗng nghĩa là đã đổi hoặc không còn dùng. */
    public Optional<Graded> visible(long courseId, long revisionId, long lessonId, long questionId, long version) {
        return jdbc.sql("""
            select q.question_id, q.row_version as question_version, q.competency_id, q.question_type, q.explanation
            """ + VISIBLE + """
              and q.question_id = :question and q.row_version = :version
            """).param("course", courseId).param("revision", revisionId).param("lesson", lessonId)
            .param("question", questionId).param("version", version).query(Graded.class).optional();
    }

    public Set<Long> correctOptionIds(long questionId) {
        return Set.copyOf(jdbc.sql("""
            select option_id from learning_hub.question_options
            where question_id = :id and is_correct and not is_deleted
            """).param("id", questionId).query(Long.class).list());
    }

    public Set<Long> optionIds(long questionId) {
        return Set.copyOf(jdbc.sql("""
            select option_id from learning_hub.question_options where question_id = :id and not is_deleted
            """).param("id", questionId).query(Long.class).list());
    }

    public void record(String user, long lessonId, Graded question, boolean correct) {
        jdbc.sql("""
            insert into learning_hub.checkpoint_answers
                (user_id, lesson_id, question_id, question_version, competency_id, correct)
            values (:user, :lesson, :question, :version, :competency, :correct)
            """).param("user", user).param("lesson", lessonId).param("question", question.questionId())
            .param("version", question.questionVersion()).param("competency", question.competencyId())
            .param("correct", correct).update();
    }
}
