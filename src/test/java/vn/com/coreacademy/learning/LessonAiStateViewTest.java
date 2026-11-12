package vn.com.coreacademy.learning;

import org.junit.jupiter.api.Test;

import vn.com.coreacademy.learning.entity.CourseLesson;
import vn.com.coreacademy.learning.mapper.LearningMapperImpl;

import static org.assertj.core.api.Assertions.assertThat;

/** The editor reads a lesson's AI state from the course detail; it is never part of a save. */
class LessonAiStateViewTest {
    private final LearningMapperImpl mapper = new LearningMapperImpl();

    @Test
    void aLessonViewCarriesItsAiState() {
        CourseLesson lesson = new CourseLesson();
        lesson.setTitle("Bài 1");
        lesson.setAiState("writing");

        assertThat(mapper.view(lesson).aiState()).isEqualTo("writing");
    }

    @Test
    void aHandMadeLessonHasNoAiState() {
        CourseLesson lesson = new CourseLesson();
        lesson.setTitle("Bài 2");

        assertThat(mapper.view(lesson).aiState()).isNull();
    }
}
