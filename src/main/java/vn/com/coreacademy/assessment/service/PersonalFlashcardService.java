package vn.com.coreacademy.assessment.service;

import java.sql.Types;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess;
import vn.com.coreacademy.learning.service.LearningWriteLock;

@Service
@RequiredArgsConstructor
public class PersonalFlashcardService {
    private final JdbcClient jdbc;
    private final LearningAssessmentAccess access;
    private final LearningWriteLock lock;

    public record NewCard(String prompt, String answer, Long competencyId) {}

    public record Deck(long id, String name, Long courseId, long cardCount) {}
    public record Card(long id, String prompt, String answer, Long competencyId, String reviewStatus, boolean starred) {}

    private String user() {
        return access.requireLearnerActor().person().id();
    }

    @Transactional(readOnly = true)
    public List<Deck> decks() {
        return jdbc.sql("""
            select d.deck_id as id, d.name, d.course_id, count(c.card_id) as card_count
            from learning_hub.personal_flashcard_decks d
            left join learning_hub.personal_flashcards c on c.deck_id = d.deck_id
            where d.user_id = :user
            group by d.deck_id order by d.deck_id desc
            """).param("user", user()).query(Deck.class).list();
    }

    @Transactional
    public Deck createDeck(String name, Long courseId) {
        lock.acquire();
        String user = user();
        validateCourse(courseId);
        long id = jdbc.sql("""
            insert into learning_hub.personal_flashcard_decks (user_id, name, course_id)
            values (:user, :name, :course) returning deck_id
            """).param("user", user).param("name", name.trim()).param("course", courseId, Types.BIGINT)
            .query(Long.class).single();
        return deck(id, user);
    }

    @Transactional
    public Deck updateDeck(long id, String name, Long courseId) {
        lock.acquire();
        String user = user();
        validateCourse(courseId);
        int count = jdbc.sql("""
            update learning_hub.personal_flashcard_decks set name = :name, course_id = :course, update_time = now()
            where deck_id = :id and user_id = :user
            """).param("name", name.trim()).param("course", courseId, Types.BIGINT)
            .param("id", id).param("user", user).update();
        requireFound(count);
        return deck(id, user);
    }

    @Transactional
    public void deleteDeck(long id) {
        lock.acquire();
        String user = user();
        requireDeck(id, user);
        jdbc.sql("delete from learning_hub.personal_flashcards where deck_id = :id").param("id", id).update();
        requireFound(jdbc.sql("delete from learning_hub.personal_flashcard_decks where deck_id = :id and user_id = :user")
            .param("id", id).param("user", user).update());
    }

    @Transactional(readOnly = true)
    public List<Card> cards(long deckId) {
        String user = user();
        requireDeck(deckId, user);
        return jdbc.sql("""
            select card_id as id, prompt, answer, competency_id, review_status, is_starred as starred
            from learning_hub.personal_flashcards where deck_id = :deck order by card_id
            """).param("deck", deckId).query(Card.class).list();
    }

    @Transactional
    public Card createCard(long deckId, String prompt, String answer, Long competencyId) {
        lock.acquire();
        requireDeck(deckId, user());
        validateCompetency(competencyId);
        long id = jdbc.sql("""
            insert into learning_hub.personal_flashcards (deck_id, prompt, answer, competency_id)
            values (:deck, :prompt, :answer, :competency) returning card_id
            """).param("deck", deckId).param("prompt", prompt.trim()).param("answer", answer.trim())
            .param("competency", competencyId, Types.BIGINT).query(Long.class).single();
        return card(deckId, id);
    }

    @Transactional
    public List<Card> createCards(long deckId, List<NewCard> items) {
        lock.acquire();
        requireDeck(deckId, user());
        return items.stream().map(item -> createCard(deckId, item.prompt(), item.answer(), item.competencyId())).toList();
    }

    @Transactional
    public Card updateCard(long deckId, long id, String prompt, String answer, Long competencyId) {
        lock.acquire();
        requireDeck(deckId, user());
        validateCompetency(competencyId);
        requireFound(jdbc.sql("""
            update learning_hub.personal_flashcards
            set prompt = :prompt, answer = :answer, competency_id = :competency, review_status = null, update_time = now()
            where deck_id = :deck and card_id = :id
            """).param("prompt", prompt.trim()).param("answer", answer.trim())
            .param("competency", competencyId, Types.BIGINT).param("deck", deckId).param("id", id).update());
        return card(deckId, id);
    }

    @Transactional
    public void deleteCard(long deckId, long id) {
        lock.acquire();
        requireDeck(deckId, user());
        requireFound(jdbc.sql("delete from learning_hub.personal_flashcards where deck_id = :deck and card_id = :id")
            .param("deck", deckId).param("id", id).update());
    }

    @Transactional
    public Card progress(long deckId, long id, String rating, Boolean starred) {
        lock.acquire();
        requireDeck(deckId, user());
        if (rating == null && starred == null) {
            throw BusinessException.validation("rating", "REQUIRED", "Chọn đánh giá hoặc trạng thái đánh dấu.");
        }
        requireFound(jdbc.sql("""
            update learning_hub.personal_flashcards
            set review_status = coalesce(:rating, review_status), is_starred = coalesce(:starred, is_starred),
                update_time = now()
            where deck_id = :deck and card_id = :id
            """).param("rating", rating, Types.VARCHAR).param("starred", starred, Types.BOOLEAN)
            .param("deck", deckId).param("id", id).update());
        return card(deckId, id);
    }

    private void validateCourse(Long id) {
        if (id != null) {
            access.requireStudyingLearner(id);
        }
    }

    private void validateCompetency(Long id) {
        if (id != null && !jdbc.sql("""
            select exists (select 1 from learning_hub.competencies
                           where competency_id = :id and status = 'ACTIVE' and not is_deleted)
            """).param("id", id).query(Boolean.class).single()) {
            throw new BusinessException(AssessmentResponseCode.INVALID_REFERENCE);
        }
    }

    private void requireDeck(long id, String user) {
        if (!jdbc.sql("select exists (select 1 from learning_hub.personal_flashcard_decks where deck_id = :id and user_id = :user)")
            .param("id", id).param("user", user).query(Boolean.class).single()) {
            throw new BusinessException(AssessmentResponseCode.NOT_FOUND);
        }
    }

    private Deck deck(long id, String user) {
        return jdbc.sql("""
            select d.deck_id as id, d.name, d.course_id, count(c.card_id) as card_count
            from learning_hub.personal_flashcard_decks d
            left join learning_hub.personal_flashcards c on c.deck_id = d.deck_id
            where d.deck_id = :id and d.user_id = :user group by d.deck_id
            """).param("id", id).param("user", user).query(Deck.class).single();
    }

    private Card card(long deckId, long id) {
        return jdbc.sql("""
            select card_id as id, prompt, answer, competency_id, review_status, is_starred as starred
            from learning_hub.personal_flashcards where deck_id = :deck and card_id = :id
            """).param("deck", deckId).param("id", id).query(Card.class).single();
    }

    private void requireFound(int count) {
        if (count == 0) {
            throw new BusinessException(AssessmentResponseCode.NOT_FOUND);
        }
    }
}
