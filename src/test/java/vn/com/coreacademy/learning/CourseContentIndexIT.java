package vn.com.coreacademy.learning;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.support.FakeEmbeddings;
import vn.com.coreacademy.learning.index.CourseContentChunkRepository;
import vn.com.coreacademy.learning.index.CourseContentIndexer;
import vn.com.coreacademy.learning.index.CourseContentRetrieval;
import vn.com.coreacademy.learning.index.CourseContentRetrieval.CourseHit;
import vn.com.coreacademy.learning.index.CourseQuestionSearch;
import vn.com.coreacademy.storage.service.ObjectStorage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/** Chỉ mục nội dung khóa: xuất bản thì tìm được, người không được học thì không thấy, chạy lại không nhân đôi. */
class CourseContentIndexIT extends AssessmentIntegrationTest {
    @MockitoBean
    private OllamaClient ollama;

    @MockitoBean
    private ObjectStorage storage;

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
    @Autowired
    private CourseContentChunkRepository index;

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

    @Test
    void textInsideALessonPdfIsFoundWithItsLessonAndPage() throws IOException {
        Course pdfCourse = new AssessmentFixtures(new ApiClient(mvc, json), jdbc).course(LEARNER);
        indexAll();
        byte[] pdf = pdf("Introduction to the course", "The refund ceiling is five million dong per customer");
        attachPdf(pdfCourse, pdf);
        jdbc.update("DELETE FROM learning_hub.course_content_index WHERE course_id = ?", pdfCourse.id());
        jdbc.update("DELETE FROM learning_hub.course_content_chunks WHERE course_id = ?", pdfCourse.id());

        assertThat(indexAll()).isPositive();

        List<ChunkHit> hits = as(LEARNER, () -> questions.hits("refund ceiling", pdfCourse.lessonId()));
        assertThat(hits).filteredOn(hit -> hit.content().contains("refund ceiling")).singleElement().satisfies(hit -> {
            assertThat(hit.page()).isEqualTo(2);
            assertThat(hit.lesson().url()).isEqualTo("/lesson/" + pdfCourse.id() + "/" + pdfCourse.lessonId());
        });
    }

    @Test
    void fileThatCannotBeReadDoesNotFailTheRevision() {
        Course scanCourse = new AssessmentFixtures(new ApiClient(mvc, json), jdbc).course(LEARNER);
        indexAll();
        attachPdf(scanCourse, "not a pdf at all".getBytes());
        jdbc.update("DELETE FROM learning_hub.course_content_index WHERE course_id = ?", scanCourse.id());
        jdbc.update("DELETE FROM learning_hub.course_content_chunks WHERE course_id = ?", scanCourse.id());

        assertThat(indexAll()).isPositive();

        assertThat(jdbc.queryForObject("SELECT status FROM learning_hub.course_content_index WHERE course_id = ?",
            String.class, scanCourse.id())).isEqualTo("done");
    }

    @Test
    void learnerStaysOnTheirRevisionAndANewLearnerGetsTheNewOne() {
        AssessmentFixtures fixtures = new AssessmentFixtures(new ApiClient(mvc, json), jdbc);
        ApiClient api = new ApiClient(mvc, json);
        Course old = fixtures.course(LEARNER);
        indexAll();
        JsonNode draft = api.ok(INSTRUCTOR, api.post(Map.of(), "/api/v1/courses/{c}/draft", old.id()));
        long next = draft.path("revision").path("id").asLong();
        api.ok(INSTRUCTOR, api.post(Map.of("expectedVersion", draft.path("revision").path("rowVersion").asLong()),
            "/api/v1/courses/{c}/revisions/{r}/publish", old.id(), next));
        fixtures.assign(old.id(), next, LEARNER_2);

        // Published but not indexed yet: the previous revision still answers, for the manager too.
        assertThat(revisions(LEARNER, old.id())).containsExactly(old.revisionId());
        assertThat(revisions(INSTRUCTOR, old.id())).containsExactly(old.revisionId());

        indexAll();

        assertThat(revisions(LEARNER, old.id())).as("the learner never sees the new text").containsExactly(old.revisionId());
        assertThat(revisions(LEARNER_2, old.id())).containsExactly(next);
        assertThat(revisions(INSTRUCTOR, old.id())).containsExactly(next);

        jdbc.update("UPDATE learning_hub.course_enrollments SET status = 'withdrawn' WHERE revision_id = ?",
            old.revisionId());
        indexAll();

        assertThat(chunkRevisions(old.id())).as("no active enrolment left on the old revision").containsExactly(next);
    }

    @Test
    void failingRevisionStopsRetryingAndAnAdminCanRetryIt() {
        Course failing = new AssessmentFixtures(new ApiClient(mvc, json), jdbc).course(LEARNER);
        doThrow(new IllegalStateException("embedding broke")).when(ollama).embed(anyList());
        try {
            for (int attempt = 0; attempt < 5; attempt++) {
                jdbc.update("UPDATE learning_hub.course_content_index SET next_attempt_time = now() "
                    + "WHERE status = 'retry'");
                indexer.runOnce();
            }
            verify(ollama, atLeastOnce()).embed(anyList());
            assertThat(statusOf(failing)).isEqualTo("failed");
            assertThat(jdbc.queryForObject("SELECT attempts FROM learning_hub.course_content_index "
                + "WHERE revision_id = ?", Integer.class, failing.revisionId())).isEqualTo(5);

            int calls = (int) org.mockito.Mockito.mockingDetails(ollama).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("embed")).count();
            indexer.runOnce();
            verify(ollama, times(calls)).embed(anyList());
            assertThat(index.failed()).extracting(CourseContentChunkRepository.FailedRevision::revisionId)
                .contains(failing.revisionId());
        } finally {
            doAnswer(FakeEmbeddings.everyInputOnAxisZero()).when(ollama).embed(anyList());
        }

        assertThat(index.retry(failing.revisionId())).isTrue();
        assertThat(indexAll()).isPositive();
        assertThat(statusOf(failing)).isEqualTo("done");
    }

    private String statusOf(Course course) {
        return jdbc.queryForObject("SELECT status FROM learning_hub.course_content_index WHERE revision_id = ?",
            String.class, course.revisionId());
    }

    private Set<Long> revisions(String user, long courseId) {
        return as(user, () -> search()).stream().filter(hit -> hit.courseId() == courseId)
            .map(CourseHit::revisionId).collect(Collectors.toSet());
    }

    private Set<Long> chunkRevisions(long courseId) {
        return Set.copyOf(jdbc.queryForList("SELECT DISTINCT revision_id FROM learning_hub.course_content_chunks "
            + "WHERE course_id = ?", Long.class, courseId));
    }

    /** Adds a pdf block to the published lesson of the course, backed by {@code content} in the stubbed storage. */
    private void attachPdf(Course target, byte[] content) {
        String key = "learning/it-" + java.util.UUID.randomUUID();
        long assetId = jdbc.queryForObject("""
            INSERT INTO learning_hub.assets (owner_org_id, uploaded_by, storage_type, storage_key, original_name,
                mime_type, size_bytes, status, created_by, updated_by)
            VALUES (?, ?, 's3', ?, 'lesson.pdf', 'application/pdf', ?, 'ready', ?, ?) RETURNING asset_id
            """, Long.class, target.ownerOrgId(), INSTRUCTOR, key, (long) content.length, INSTRUCTOR, INSTRUCTOR);
        long blockId = jdbc.queryForObject("""
            INSERT INTO learning_hub.lesson_blocks (lesson_id, block_type, title, completion_mode, is_required,
                sort_no, created_by, updated_by)
            VALUES (?, 'pdf', 'Tài liệu PDF', 'manual', false, 99, ?, ?) RETURNING block_id
            """, Long.class, target.lessonId(), INSTRUCTOR, INSTRUCTOR);
        jdbc.update("""
            INSERT INTO learning_hub.block_assets (block_id, asset_id, usage, sort_no, created_by, updated_by)
            VALUES (?, ?, 'primary', 1, ?, ?)
            """, blockId, assetId, INSTRUCTOR, INSTRUCTOR);
        when(storage.stat(key)).thenReturn(new ObjectStorage.ObjectInfo(content.length, "etag"));
        when(storage.read(key, 0, content.length, "etag")).thenAnswer(call -> new ByteArrayInputStream(content));
    }

    private static byte[] pdf(String... pages) throws IOException {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            for (String text : pages) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText(text);
                    content.endText();
                }
            }
            document.save(out);

            return out.toByteArray();
        }
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
