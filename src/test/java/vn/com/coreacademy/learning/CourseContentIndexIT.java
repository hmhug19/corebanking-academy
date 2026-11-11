package vn.com.coreacademy.learning;

import java.util.List;

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
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.support.FakeEmbeddings;
import vn.com.coreacademy.learning.index.CourseContentIndexer;
import vn.com.coreacademy.learning.index.CourseContentRetrieval;
import vn.com.coreacademy.learning.index.CourseContentRetrieval.CourseHit;
import vn.com.coreacademy.learning.index.CourseQuestionSearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/** Chỉ mục nội dung khóa: xuất bản thì tìm được, người không được học thì không thấy, chạy lại không nhân đôi. */
class CourseContentIndexIT extends AssessmentIntegrationTest {
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
    private CourseContentRetrieval retrieval;
    @Autowired
    private CourseQuestionSearch questions;

    private Course course;

    // The mock is reset after every test, so it is stubbed again before each one.
    @BeforeEach
    void stubOllama() {
        when(ollama.isAvailable()).thenReturn(true);
        when(ollama.embed(anyList())).thenAnswer(FakeEmbeddings.everyInputOnAxisZero());
    }

    @BeforeAll
    void setUp() {
        stubOllama();
        course = new AssessmentFixtures(new ApiClient(mvc, json), jdbc).course(LEARNER);
        indexAll();
    }

    @Test
    void publishedLessonIsIndexedOnce() {
        assertThat(count()).isPositive();
        int before = count();

        assertThat(indexAll()).isZero();
        assertThat(count()).isEqualTo(before);
    }

    @Test
    void enrolledLearnerFindsTheLessonAndCitesItWithALink() {
        List<ChunkHit> hits = as(LEARNER, () -> questions.hits("Giới thiệu", course.lessonId()));

        assertThat(hits).anySatisfy(hit -> {
            assertThat(hit.lesson()).isNotNull();
            assertThat(hit.lesson().courseId()).isEqualTo(course.id());
            assertThat(hit.lesson().lessonTitle()).isNotBlank();
            assertThat(hit.lesson().url()).isEqualTo("/lesson/" + course.id() + "/" + course.lessonId());
        });
    }

    @Test
    void courseManagerFindsTheLesson() {
        assertThat(as(INSTRUCTOR, () -> search())).extracting(CourseHit::courseId).contains(course.id());
    }

    @Test
    void learnerWithoutEnrolmentFindsNothingOfTheCourse() {
        List<CourseHit> hits = as(LEARNER_2, () -> search());

        assertThat(hits).extracting(CourseHit::courseId).doesNotContain(course.id());
        long chunkId = jdbc.queryForObject("SELECT min(chunk_id) FROM learning_hub.course_content_chunks "
            + "WHERE course_id = ?", Long.class, course.id());
        assertThat(as(LEARNER_2, () -> retrieval.find(chunkId))).isEmpty();
        assertThat(as(LEARNER, () -> retrieval.find(chunkId))).isPresent();
    }

    @Test
    void revisionWithoutCompletedMarkerStaysPendingAndIsDoneAgain() {
        jdbc.update("DELETE FROM learning_hub.course_content_index WHERE course_id = ?", course.id());
        jdbc.update("DELETE FROM learning_hub.course_content_chunks WHERE course_id = ?", course.id());

        assertThat(indexAll()).isPositive();
        assertThat(count()).isPositive();
    }

    /** A run takes a few revisions; other tests share the database, so repeat until nothing is pending. */
    private int indexAll() {
        int total = 0;
        for (int run = indexer.runOnce(); run > 0; run = indexer.runOnce()) {
            total += run;
        }

        return total;
    }

    private List<CourseHit> search() {
        float[] vector = new float[1024];
        vector[0] = 1f;

        // Other tests share the database and every fake vector is alike, so take them all.
        return retrieval.search(vector, 100_000);
    }

    private int count() {
        return jdbc.queryForObject("SELECT count(*) FROM learning_hub.course_content_chunks WHERE course_id = ?",
            Integer.class, course.id());
    }

    private static <T> T as(String userId, java.util.function.Supplier<T> action) {
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
