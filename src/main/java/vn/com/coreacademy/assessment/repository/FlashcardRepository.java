package vn.com.coreacademy.assessment.repository;

import java.sql.Types;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuizType;

@Repository
@RequiredArgsConstructor
public class FlashcardRepository {
    private static final String SOURCE = """
        from learning_hub.flashcards f
        join learning_hub.questions q on q.question_id = f.question_id
        join learning_hub.course_revisions r on r.revision_id = :revision and r.course_id = f.course_id
        """;
    private static final String VISIBLE = """
        where f.course_id = :course and f.status = 'PUBLISHED'
          and q.status = 'ACTIVE' and not q.is_deleted and f.approved_question_version = q.row_version
          and (q.program_id = r.program_id or (q.program_id is null and q.course_id = f.course_id))
        """;
    private final JdbcClient jdbc;

    public record ManagedCard(long id, long questionId, String status, String prompt, boolean current) {
    }

    public record StudyCard(long id, long questionVersion, long competencyId, String competencyName, String prompt,
                            String reviewStatus, boolean starred) {
    }

    public record Answer(List<String> options, String explanation) {
    }

    public List<ManagedCard> managed(long courseId) {
        return jdbc.sql("""
            select f.flashcard_id as id, q.question_id, f.status, q.content as prompt,
                   coalesce(f.approved_question_version = q.row_version and q.status = 'ACTIVE'
                            and not q.is_deleted, false) as current
            from learning_hub.flashcards f
            join learning_hub.questions q on q.question_id = f.question_id
            where f.course_id = :course
            order by f.flashcard_id
            """).param("course", courseId).query(ManagedCard.class).list();
    }

    public void setStatus(long courseId, long questionId, String status, Long version, String actor) {
        jdbc.sql("""
            insert into learning_hub.flashcards
                (course_id, question_id, status, approved_question_version, first_published_at, created_by, updated_by)
            values (:course, :question, :status, :version,
                    case when :status = 'PUBLISHED' then now() end, :actor, :actor)
            on conflict (course_id, question_id) do update
            set status = excluded.status, approved_question_version = excluded.approved_question_version,
                first_published_at = coalesce(flashcards.first_published_at, excluded.first_published_at),
                updated_by = excluded.updated_by, update_time = now(), row_version = flashcards.row_version + 1
            """).param("course", courseId).param("question", questionId).param("status", status)
            .param("version", version, Types.BIGINT).param("actor", actor).update();
    }

    public boolean withdraw(long courseId, long questionId, String actor) {
        return jdbc.sql("""
            update learning_hub.flashcards
            set status = 'WITHDRAWN', updated_by = :actor, update_time = now(), row_version = row_version + 1
            where course_id = :course and question_id = :question
            """).param("course", courseId).param("question", questionId).param("actor", actor).update() != 0;
    }

    public boolean inFinalExam(long questionId) {
        return jdbc.sql("""
            select exists (
                select 1 from learning_hub.quiz_questions p
                join learning_hub.quizzes z on z.quiz_id = p.quiz_id
                where p.question_id = :question and z.quiz_type = :type and not z.is_deleted
            )
            """).param("question", questionId).param("type", QuizType.FINAL_EXAM).query(Boolean.class).single();
    }

    public boolean previouslyPublished(Collection<Long> questionIds) {
        return !questionIds.isEmpty() && jdbc.sql("""
            select exists (
                select 1 from learning_hub.flashcards
                where question_id in (:ids) and first_published_at is not null
            )
            """).param("ids", questionIds).query(Boolean.class).single();
    }

    public List<StudyCard> study(long course, long revision, String user) {
        return jdbc.sql("""
            select f.flashcard_id as id, q.row_version as question_version, q.competency_id,
                   coalesce(c.name, 'Nhóm kiến thức') as competency_name, q.content as prompt,
                   case when p.reviewed_question_version = q.row_version then p.review_status end as review_status,
                   coalesce(p.is_starred, false) as starred
            """ + SOURCE + """
            left join learning_hub.competencies c on c.competency_id = q.competency_id
            left join learning_hub.flashcard_progress p on p.flashcard_id = f.flashcard_id and p.user_id = :user
            """ + VISIBLE + " order by f.flashcard_id")
            .param("course", course).param("revision", revision).param("user", user).query(StudyCard.class).list();
    }

    public Optional<Long> question(long course, long revision, long cardId, long version) {
        return jdbc.sql("select q.question_id\n" + SOURCE + VISIBLE + """
             and f.flashcard_id = :card and q.row_version = :version
            """).param("course", course).param("revision", revision).param("card", cardId)
            .param("version", version).query(Long.class).optional();
    }

    public Answer answer(long questionId) {
        List<String> options = jdbc.sql("""
            select content from learning_hub.question_options
            where question_id = :id and is_correct and not is_deleted
            order by sort_no, option_id
            """).param("id", questionId).query(String.class).list();
        String explanation = jdbc.sql("""
            select explanation from learning_hub.questions where question_id = :id
            """).param("id", questionId).query(String.class).optional().orElse(null);
        return new Answer(options, explanation);
    }

    public void saveProgress(String user, long card, long version, String rating, Boolean starred) {
        jdbc.sql("""
            insert into learning_hub.flashcard_progress
                (user_id, flashcard_id, review_status, is_starred, reviewed_question_version, last_reviewed_at)
            values (:user, :card, :rating, coalesce(:starred, false),
                    case when :rating is not null then :version end,
                    case when :rating is not null then now() end)
            on conflict (user_id, flashcard_id) do update
            set review_status = coalesce(excluded.review_status, flashcard_progress.review_status),
                is_starred = coalesce(:starred, flashcard_progress.is_starred),
                reviewed_question_version = coalesce(excluded.reviewed_question_version,
                                                    flashcard_progress.reviewed_question_version),
                last_reviewed_at = coalesce(excluded.last_reviewed_at, flashcard_progress.last_reviewed_at),
                update_time = now()
            """).param("user", user).param("card", card).param("version", version)
            .param("rating", rating, Types.VARCHAR).param("starred", starred, Types.BOOLEAN).update();
    }
}
