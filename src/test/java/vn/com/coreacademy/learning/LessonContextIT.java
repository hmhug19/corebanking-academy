package vn.com.coreacademy.learning;

import java.util.List;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.assistant.agent.CourseLessonAccess;
import vn.com.coreacademy.assistant.agent.LessonFocus;
import vn.com.coreacademy.assistant.conversation.ConversationService;
import vn.com.coreacademy.assistant.dto.LessonContext;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.support.FakeEmbeddings;
import vn.com.coreacademy.learning.index.CourseContentIndexer;
import vn.com.coreacademy.learning.index.CourseQuestionSearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/** Asking from a lesson: access is checked for that lesson, its chunks lead, and the conversation keeps the label. */
class LessonContextIT extends AssessmentIntegrationTest {
    @MockitoBean
    private OllamaClient ollama;

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper json;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private CourseContentIndexer indexer;
    @Autowired
    private CourseQuestionSearch questions;
    @Autowired
    private CourseLessonAccess access;
    @Autowired
    private ConversationService conversations;

    private Course course;

    @BeforeEach
    void stubOllama() {
        when(ollama.isAvailable()).thenReturn(true);
        when(ollama.embed(anyList())).thenAnswer(FakeEmbeddings.everyInputOnAxisZero());
    }

    @BeforeAll
    void setUp() {
        stubOllama();
        course = new AssessmentFixtures(new ApiClient(mvc, json), jdbc).course(LEARNER);
        while (indexer.runOnce() > 0) {
            // other tests share the database; a run takes a few revisions
        }
    }

    private LessonContext context() {
        return new LessonContext(course.id(), course.revisionId(), course.lessonId());
    }

    @Test
    void anEnrolledLearnerAndTheManagerOpenTheLesson() {
        LessonFocus learner = as(LEARNER, () -> access.require(context()));

        assertThat(learner.lessonId()).isEqualTo(course.lessonId());
        assertThat(learner.lessonTitle()).isNotBlank();
        assertThat(as(INSTRUCTOR, () -> access.require(context())).courseId()).isEqualTo(course.id());
    }

    @Test
    void aLearnerWithoutEnrolmentIsRefusedWithTheSameAnswerAsAMissingLesson() {
        assertThatThrownBy(() -> as(LEARNER_2, () -> access.require(context())))
            .isInstanceOf(BusinessException.class).extracting("responseCode")
            .isEqualTo(AssistantResponseCode.LESSON_NOT_FOUND);
        assertThatThrownBy(() -> as(LEARNER, () -> access.require(
            new LessonContext(course.id(), course.revisionId(), course.lessonId() + 99_999))))
            .isInstanceOf(BusinessException.class).extracting("responseCode")
            .isEqualTo(AssistantResponseCode.LESSON_NOT_FOUND);
        assertThatThrownBy(() -> as(LEARNER, () -> access.require(
            new LessonContext(course.id(), course.revisionId() + 99_999, course.lessonId()))))
            .isInstanceOf(BusinessException.class);
    }

    @Test
    void theLessonsOwnChunksLeadEvenForAVagueQuestion() {
        List<ChunkHit> hits = as(LEARNER, () -> questions.hits("bài này nói gì?", course.lessonId()));

        assertThat(hits).isNotEmpty();
        assertThat(hits.get(0).lesson().lessonId()).isEqualTo(course.lessonId());
        assertThat(as(LEARNER, () -> questions.lessonText(course.lessonId()))).isNotEmpty();
        assertThat(as(LEARNER_2, () -> questions.lessonText(course.lessonId()))).isEmpty();
        assertThat(as(LEARNER_2, () -> questions.hits("bài này nói gì?", course.lessonId())))
            .noneMatch(hit -> hit.lesson().courseId() == course.id());
    }

    @Test
    void theConversationRemembersTheLessonItWasOpenedFrom() {
        LessonFocus focus = as(LEARNER, () -> access.require(context()));

        long id = conversations.open(LEARNER, null, "Bài này nói gì?", focus).conversationId();
        var detail = conversations.get(LEARNER, id);

        assertThat(detail.context()).isNotNull();
        assertThat(detail.context().lessonId()).isEqualTo(course.lessonId());
        assertThat(detail.context().label()).contains(focus.lessonTitle());
        assertThat(conversations.list(LEARNER, null, 0, 50).items()).anySatisfy(item -> {
            assertThat(item.id()).isEqualTo(id);
            assertThat(item.context().courseId()).isEqualTo(course.id());
        });
        long plain = conversations.open(LEARNER, null, "Xin chào", null).conversationId();
        assertThat(conversations.get(LEARNER, plain).context()).isNull();
    }

    private static <T> T as(String userId, Supplier<T> action) {
        var original = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("it")
            .header("alg", "none").subject(userId).build(), List.of()));
        try {
            SecurityContextHolder.setContext(context);
            return action.get();
        } finally {
            SecurityContextHolder.setContext(original);
        }
    }
}
