package vn.com.coreacademy.learning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;
import vn.com.coreacademy.learning.LessonNotesFixture.Lesson;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.delete;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/** Ghi chú riêng N1–N3: quyền vào bài, từng luật neo, nội dung, giới hạn và xóa mềm. */
class LearningNotesIT extends AssessmentIntegrationTest {
    private static final String LIST = "/api/v1/me/learning/lessons/{lesson}/notes";
    private static final String NOTE = "/api/v1/me/learning/notes/{note}";
    private static final String NOT_FOUND = "LEARNING-NOT_FOUND";
    private static final String INVALID_REFERENCE = "LEARNING-INVALID_REFERENCE";
    private static final String VALIDATION = "CORE-VALIDATION_FAILED";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private Lesson lesson;
    private Lesson otherCourse;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        AssessmentFixtures fixtures = new AssessmentFixtures(api, jdbc);
        lesson = LessonNotesFixture.lesson(fixtures, jdbc, LEARNER);
        otherCourse = LessonNotesFixture.lesson(fixtures, jdbc);
    }

    @Test
    void addsAndListsNewestFirstWithTheAnchorAsSent() {
        Lesson mine = fresh();
        JsonNode pdf = api.ok(LEARNER, api.post(note("pdf", mine.pdf(), 3, null, "  Trang ba  "), LIST, mine.id()));
        JsonNode video = api.ok(LEARNER, api.post(note("video", mine.video(), null, 20, "Mốc 20 giây"), LIST,
            mine.id()));
        JsonNode whole = api.ok(LEARNER, api.post(note("none", null, null, null, "Cả bài"), LIST, mine.id()));

        assertThat(pdf.fieldNames()).toIterable().containsExactly("id", "lessonId", "anchor", "text", "createTime");
        assertThat(pdf.path("id").isNumber()).isTrue();
        assertThat(pdf.path("lessonId").asLong()).isEqualTo(mine.id());
        assertThat(pdf.path("text").asText()).as("trimmed").isEqualTo("Trang ba");
        assertThat(pdf.path("anchor").path("type").asText()).isEqualTo("pdf");
        assertThat(pdf.path("anchor").path("blockId").asLong()).isEqualTo(mine.pdf());
        assertThat(pdf.path("anchor").path("page").asInt()).isEqualTo(3);
        assertThat(pdf.path("anchor").path("second").isNull()).isTrue();
        assertThat(pdf.path("createTime").asText()).isNotBlank();
        assertThat(video.path("anchor").path("second").asInt()).isEqualTo(20);
        assertThat(video.path("anchor").path("page").isNull()).isTrue();
        assertThat(whole.path("anchor").path("blockId").isNull()).isTrue();

        JsonNode list = api.ok(LEARNER, get(LIST, mine.id()));
        assertThat(ids(list)).as("newest first").containsExactly(whole.path("id").asLong(),
            video.path("id").asLong(), pdf.path("id").asLong());
    }

    @Test
    void refusesALessonOutsideTheCallersEnrollments() {
        String body = "{\"anchor\":{\"type\":\"none\"},\"text\":\"x\"}";

        api.error(LEARNER_2, get(LIST, lesson.id()), NOT_FOUND);
        api.error(LEARNER_2, api.post(body, LIST, lesson.id()), NOT_FOUND);
        api.error(LEARNER, get(LIST, otherCourse.id()), NOT_FOUND);
        api.error(LEARNER, api.post(body, LIST, otherCourse.id()), NOT_FOUND);
        api.error(LEARNER, get(LIST, 999_999_999L), NOT_FOUND);

        Lesson withdrawn = fresh();
        jdbc.update("update learning_hub.course_enrollments set status = 'withdrawn' where course_id = ?",
            withdrawn.course().id());
        api.error(LEARNER, get(LIST, withdrawn.id()), NOT_FOUND);
    }

    @Test
    void checksAPdfAnchorAgainstTheBlockAndItsPageCount() {
        Lesson mine = fresh();

        api.ok(LEARNER, api.post(note("pdf", mine.pdf(), 1, null, "đầu"), LIST, mine.id()));
        api.ok(LEARNER, api.post(note("pdf", mine.pdf(), 4, null, "cuối"), LIST, mine.id()));
        for (int page : new int[] {0, 5, -1}) {
            api.error(LEARNER, api.post(note("pdf", mine.pdf(), page, null, "x"), LIST, mine.id()),
                INVALID_REFERENCE);
        }
        api.error(LEARNER, api.post(note("pdf", mine.video(), 1, null, "x"), LIST, mine.id()), INVALID_REFERENCE);
        api.error(LEARNER, api.post(note("pdf", mine.text(), 1, null, "x"), LIST, mine.id()), INVALID_REFERENCE);
        api.error(LEARNER, api.post(note("pdf", otherCourse.pdf(), 1, null, "x"), LIST, mine.id()),
            INVALID_REFERENCE);
        api.error(LEARNER, api.post(note("pdf", 999_999_999L, 1, null, "x"), LIST, mine.id()), INVALID_REFERENCE);
        api.error(LEARNER, api.post(note("pdf", mine.unboundedPdf(), 1, null, "x"), LIST, mine.id()),
            INVALID_REFERENCE);
        assertThat(api.ok(LEARNER, get(LIST, mine.id()))).as("only the two valid notes").hasSize(2);
    }

    @Test
    void checksAVideoAnchorAgainstTheBlockAndTheWholeSecondsOfItsDuration() {
        Lesson mine = fresh();

        api.ok(LEARNER, api.post(note("video", mine.video(), null, 0, "đầu"), LIST, mine.id()));
        api.ok(LEARNER, api.post(note("video", mine.video(), null, 30, "30,5 s làm tròn xuống 30"), LIST,
            mine.id()));
        for (int second : new int[] {31, -1}) {
            api.error(LEARNER, api.post(note("video", mine.video(), null, second, "x"), LIST, mine.id()),
                INVALID_REFERENCE);
        }
        api.error(LEARNER, api.post(note("video", mine.pdf(), null, 1, "x"), LIST, mine.id()), INVALID_REFERENCE);
        api.error(LEARNER, api.post(note("video", mine.failedVideo(), null, 1, "x"), LIST, mine.id()),
            INVALID_REFERENCE);
        assertThat(api.ok(LEARNER, get(LIST, mine.id()))).hasSize(2);
    }

    @Test
    void refusesAnAnchorWithMissingOrForeignFields() {
        Lesson mine = fresh();
        Object[][] malformed = {
            {"pdf", null, 1, null}, {"pdf", mine.pdf(), null, null}, {"pdf", mine.pdf(), 1, 5},
            {"video", null, null, 1}, {"video", mine.video(), null, null}, {"video", mine.video(), 1, 5},
            {"none", mine.pdf(), null, null}, {"none", null, 1, null}, {"none", null, null, 1}};
        for (Object[] anchor : malformed) {
            api.error(LEARNER, api.post(note((String) anchor[0], (Long) anchor[1], (Integer) anchor[2],
                (Integer) anchor[3], "x"), LIST, mine.id()), VALIDATION);
        }
        api.error(LEARNER, api.post(note("slide", null, null, null, "x"), LIST, mine.id()), VALIDATION);
        api.error(LEARNER, api.post("{\"text\":\"x\"}", LIST, mine.id()), VALIDATION);
        assertThat(api.ok(LEARNER, get(LIST, mine.id()))).isEmpty();
    }

    @Test
    void acceptsTrimmedTextOfOneToFourThousandCharacters() {
        Lesson mine = fresh();

        for (String blank : new String[] {"", "   ", "\n\t"}) {
            api.error(LEARNER, api.post(note("none", null, null, null, blank), LIST, mine.id()), VALIDATION);
        }
        api.error(LEARNER, api.post("{\"anchor\":{\"type\":\"none\"}}", LIST, mine.id()), VALIDATION);
        api.error(LEARNER, api.post(note("none", null, null, null, "a".repeat(4001)), LIST, mine.id()), VALIDATION);

        api.ok(LEARNER, api.post(note("none", null, null, null, "a"), LIST, mine.id()));
        api.ok(LEARNER, api.post(note("none", null, null, null, "a".repeat(4000)), LIST, mine.id()));
        JsonNode padded = api.ok(LEARNER, api.post(note("none", null, null, null, "  " + "b".repeat(4000) + "  "),
            LIST, mine.id()));
        assertThat(padded.path("text").asText()).hasSize(4000);
        assertThat(api.ok(LEARNER, get(LIST, mine.id()))).hasSize(3);
    }

    @Test
    void stopsAtFiveHundredLiveNotesAndFreesASlotWhenOneIsDeleted() {
        Lesson mine = fresh();
        jdbc.update("""
            insert into learning_hub.lesson_notes (user_id, lesson_id, anchor_type, body, created_by, updated_by)
            select ?, ?, 'none', 'ghi chú ' || n, ?, ? from generate_series(1, 500) n
            """, LEARNER, mine.id(), LEARNER, LEARNER);
        jdbc.update("""
            insert into learning_hub.lesson_notes (user_id, lesson_id, anchor_type, body, is_deleted, created_by,
                updated_by)
            values (?, ?, 'none', 'đã xóa', true, ?, ?)
            """, LEARNER, mine.id(), LEARNER, LEARNER);

        api.error(LEARNER, api.post(note("none", null, null, null, "thứ 501"), LIST, mine.id()),
            "LEARNING-NOTE_LIMIT");

        long newest = api.ok(LEARNER, get(LIST, mine.id())).get(0).path("id").asLong();
        api.ok(LEARNER, delete(NOTE, newest));
        api.ok(LEARNER, api.post(note("none", null, null, null, "vừa đủ"), LIST, mine.id()));
        api.error(LEARNER, api.post(note("none", null, null, null, "lại đầy"), LIST, mine.id()),
            "LEARNING-NOTE_LIMIT");
    }

    @Test
    void deletesSoftlyAndOnlyTheCallersOwnNote() {
        Lesson mine = fresh();
        long id = api.ok(LEARNER, api.post(note("pdf", mine.pdf(), 2, null, "của tôi"), LIST, mine.id()))
            .path("id").asLong();

        api.error(LEARNER_2, delete(NOTE, id), NOT_FOUND);
        assertThat(api.ok(LEARNER, get(LIST, mine.id()))).as("another user's delete changed nothing").hasSize(1);

        JsonNode deleted = api.ok(LEARNER, delete(NOTE, id));
        assertThat(deleted.isNull()).isTrue();
        assertThat(api.ok(LEARNER, get(LIST, mine.id()))).isEmpty();
        assertThat(jdbc.queryForObject("select is_deleted from learning_hub.lesson_notes where note_id = ?",
            Boolean.class, id)).as("soft delete keeps the row").isTrue();
        assertThat(jdbc.queryForObject("select updated_by from learning_hub.lesson_notes where note_id = ?",
            String.class, id)).isEqualTo(LEARNER);

        api.error(LEARNER, delete(NOTE, id), NOT_FOUND);
        api.error(LEARNER, delete(NOTE, 999_999_999L), NOT_FOUND);
    }

    @Test
    void onlyLearnersMayUseTheEndpoints() {
        api.http(ADMIN, get(LIST, lesson.id()), 403, "CORE-ACCESS_DENIED");
        api.http(ADMIN, api.post(note("none", null, null, null, "x"), LIST, lesson.id()), 403,
            "CORE-ACCESS_DENIED");
        api.http(ADMIN, delete(NOTE, 1L), 403, "CORE-ACCESS_DENIED");
        api.http(null, get(LIST, lesson.id()), 401, "CORE-UNAUTHENTICATED");
    }

    /** Bài riêng của LEARNER cho từng ca, để các ca không đếm chung ghi chú của nhau. */
    private Lesson fresh() {
        return LessonNotesFixture.lesson(new AssessmentFixtures(api, jdbc), jdbc, LEARNER);
    }

    private static Map<String, Object> note(String type, Long blockId, Integer page, Integer second, String text) {
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("type", type);
        anchor.put("blockId", blockId);
        anchor.put("page", page);
        anchor.put("second", second);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchor", anchor);
        body.put("text", text);
        return body;
    }

    private static List<Long> ids(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }
}
