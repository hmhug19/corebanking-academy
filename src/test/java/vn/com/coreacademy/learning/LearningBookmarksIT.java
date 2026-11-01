package vn.com.coreacademy.learning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

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

/** Đánh dấu N4, N5, N7: bật/tắt theo khối và vị trí, đồng thời, giới hạn và quyền sở hữu. */
class LearningBookmarksIT extends AssessmentIntegrationTest {
    private static final String LIST = "/api/v1/me/learning/lessons/{lesson}/bookmarks";
    private static final String ONE = "/api/v1/me/learning/bookmarks/{bookmark}";
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
    private AssessmentFixtures fixtures;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
    }

    @Test
    void togglesOnThenOffThenOnAndListsTheLiveOnes() {
        Lesson mine = fresh();

        JsonNode on = api.ok(LEARNER, api.put(bookmark("pdf", mine.pdf(), 3, null, "Mục 3"), LIST, mine.id()));
        assertThat(on.fieldNames()).toIterable().containsExactly("saved", "bookmark");
        assertThat(on.path("saved").asBoolean()).isTrue();
        JsonNode view = on.path("bookmark");
        assertThat(view.fieldNames()).toIterable().containsExactly("id", "lessonId", "anchor", "label", "createTime");
        assertThat(view.path("lessonId").asLong()).isEqualTo(mine.id());
        assertThat(view.path("label").asText()).isEqualTo("Mục 3");
        assertThat(view.path("anchor").path("type").asText()).isEqualTo("pdf");
        assertThat(view.path("anchor").path("blockId").asLong()).isEqualTo(mine.pdf());
        assertThat(view.path("anchor").path("page").asInt()).isEqualTo(3);
        assertThat(view.path("anchor").path("second").isNull()).isTrue();
        assertThat(api.ok(LEARNER, get(LIST, mine.id()))).hasSize(1);

        JsonNode off = api.ok(LEARNER, api.put(bookmark("pdf", mine.pdf(), 3, null, null), LIST, mine.id()));
        assertThat(off.path("saved").asBoolean()).isFalse();
        assertThat(off.path("bookmark").isNull()).isTrue();
        assertThat(api.ok(LEARNER, get(LIST, mine.id()))).isEmpty();

        JsonNode again = api.ok(LEARNER, api.put(bookmark("pdf", mine.pdf(), 3, null, null), LIST, mine.id()));
        assertThat(again.path("saved").asBoolean()).isTrue();
        assertThat(again.path("bookmark").path("id").asLong()).as("a new row, the old one stays deleted")
            .isNotEqualTo(view.path("id").asLong());
        assertThat(jdbc.queryForObject("""
            select count(*) from learning_hub.lesson_bookmarks where user_id = ? and lesson_id = ?
            """, Integer.class, LEARNER, mine.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            select count(*) from learning_hub.lesson_bookmarks where user_id = ? and lesson_id = ? and not is_deleted
            """, Integer.class, LEARNER, mine.id())).isEqualTo(1);
    }

    @Test
    void treatsAnotherPageOrSecondAsAnotherBookmarkAndListsNewestFirst() {
        Lesson mine = fresh();

        long first = saved(api.ok(LEARNER, api.put(bookmark("pdf", mine.pdf(), 1, null, null), LIST, mine.id())));
        long second = saved(api.ok(LEARNER, api.put(bookmark("pdf", mine.pdf(), 2, null, null), LIST, mine.id())));
        long video = saved(api.ok(LEARNER, api.put(bookmark("video", mine.video(), null, 20, null), LIST,
            mine.id())));
        api.ok(LEARNER, api.put(bookmark("video", mine.video(), null, 21, null), LIST, mine.id()));
        api.ok(LEARNER, api.put(bookmark("video", mine.video(), null, 21, null), LIST, mine.id()));

        JsonNode list = api.ok(LEARNER, get(LIST, mine.id()));
        assertThat(ids(list)).as("newest first; second 21 was toggled off again").containsExactly(video, second,
            first);
    }

    @Test
    void keepsEachLearnersBookmarksPrivate() {
        Lesson shared = LessonNotesFixture.lesson(fixtures, jdbc, LEARNER, LEARNER_2);

        long mine = saved(api.ok(LEARNER, api.put(bookmark("pdf", shared.pdf(), 2, null, null), LIST, shared.id())));
        assertThat(api.ok(LEARNER_2, get(LIST, shared.id()))).as("the other learner sees none").isEmpty();

        long theirs = saved(api.ok(LEARNER_2, api.put(bookmark("pdf", shared.pdf(), 2, null, null), LIST,
            shared.id())));
        assertThat(theirs).isNotEqualTo(mine);
        assertThat(ids(api.ok(LEARNER, get(LIST, shared.id())))).containsExactly(mine);
        assertThat(ids(api.ok(LEARNER_2, get(LIST, shared.id())))).containsExactly(theirs);
    }

    @Test
    void fillsADefaultLabelFromTheBlockTitleAndPosition() {
        Lesson mine = fresh();

        JsonNode pdf = api.ok(LEARNER, api.put(bookmark("pdf", mine.pdf(), 3, null, null), LIST, mine.id()))
            .path("bookmark");
        JsonNode video = api.ok(LEARNER, api.put(bookmark("video", mine.video(), null, 25, "   "), LIST, mine.id()))
            .path("bookmark");

        assertThat(pdf.path("label").asText()).isEqualTo("Khối pdf 10 — trang 3");
        assertThat(video.path("label").asText()).isEqualTo("Khối video 11 — 0:25");
    }

    @Test
    void appliesTheNoteAnchorRulesAndRefusesTheWholeLessonAnchor() {
        Lesson mine = fresh();

        api.error(LEARNER, api.put(bookmark("none", null, null, null, null), LIST, mine.id()), VALIDATION);
        api.error(LEARNER, api.put(bookmark("pdf", mine.pdf(), 5, null, null), LIST, mine.id()),
            INVALID_REFERENCE);
        api.error(LEARNER, api.put(bookmark("video", mine.video(), null, 31, null), LIST, mine.id()),
            INVALID_REFERENCE);
        api.error(LEARNER, api.put(bookmark("video", mine.pdf(), null, 1, null), LIST, mine.id()),
            INVALID_REFERENCE);
        api.error(LEARNER, api.put(bookmark("pdf", mine.unboundedPdf(), 1, null, null), LIST, mine.id()),
            INVALID_REFERENCE);
        api.error(LEARNER, api.put(bookmark("pdf", null, 1, null, null), LIST, mine.id()), VALIDATION);
        api.error(LEARNER, api.put(bookmark("pdf", mine.pdf(), 1, null, "x".repeat(201)), LIST, mine.id()),
            VALIDATION);
        api.ok(LEARNER, api.put(bookmark("pdf", mine.pdf(), 1, null, "x".repeat(200)), LIST, mine.id()));
        assertThat(api.ok(LEARNER, get(LIST, mine.id()))).hasSize(1);
    }

    @Test
    void concurrentTogglesFromOneUserLeaveAConsistentState() throws Exception {
        Lesson mine = fresh();
        int calls = 5;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return api.ok(LEARNER, api.put(bookmark("pdf", mine.pdf(), 2, null, null), LIST, mine.id()))
                        .path("saved").asBoolean();
                }));
            }
            start.countDown();
            int saved = 0;
            for (Future<Boolean> result : results) {
                saved += result.get() ? 1 : -1;
            }

            assertThat(saved).as("serialised: every toggle saw the previous one").isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                select count(*) from learning_hub.lesson_bookmarks
                where user_id = ? and lesson_id = ? and not is_deleted
                """, Integer.class, LEARNER, mine.id())).as("one live row").isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                select count(*) from learning_hub.lesson_bookmarks where user_id = ? and lesson_id = ?
                """, Integer.class, LEARNER, mine.id())).as("three rows were created, two soft-deleted").isEqualTo(3);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void refusesALessonOutsideTheCallersEnrollments() {
        Lesson mine = fresh();
        Lesson noEnrollment = LessonNotesFixture.lesson(fixtures, jdbc);
        Map<String, Object> body = bookmark("pdf", mine.pdf(), 1, null, null);

        api.error(LEARNER_2, get(LIST, mine.id()), NOT_FOUND);
        api.error(LEARNER_2, api.put(body, LIST, mine.id()), NOT_FOUND);
        api.error(LEARNER, get(LIST, noEnrollment.id()), NOT_FOUND);
        api.error(LEARNER, api.put(bookmark("pdf", noEnrollment.pdf(), 1, null, null), LIST, noEnrollment.id()),
            NOT_FOUND);
        api.error(LEARNER, api.put(body, LIST, 999_999_999L), NOT_FOUND);
    }

    @Test
    void stopsAtFiveHundredLiveBookmarksButStillAllowsRemovingOne() {
        Lesson mine = fresh();
        jdbc.update("""
            insert into learning_hub.lesson_bookmarks (user_id, lesson_id, block_id, anchor_type, pdf_page, label,
                created_by, updated_by)
            select ?, ?, ?, 'pdf', case when n = 1 then 4 else 100 + n end, 'đánh dấu ' || n, ?, ?
            from generate_series(1, 500) n
            """, LEARNER, mine.id(), mine.pdf(), LEARNER, LEARNER);

        api.error(LEARNER, api.put(bookmark("pdf", mine.pdf(), 3, null, null), LIST, mine.id()),
            "LEARNING-NOTE_LIMIT");
        JsonNode off = api.ok(LEARNER, api.put(bookmark("pdf", mine.pdf(), 4, null, null), LIST, mine.id()));
        assertThat(off.path("saved").asBoolean()).as("removing at the limit is allowed").isFalse();
        api.ok(LEARNER, api.put(bookmark("pdf", mine.pdf(), 3, null, null), LIST, mine.id()));
    }

    @Test
    void deletesByIdSoftlyAndOnlyTheCallersOwn() {
        Lesson mine = fresh();
        long id = saved(api.ok(LEARNER, api.put(bookmark("video", mine.video(), null, 20, null), LIST, mine.id())));

        api.error(LEARNER_2, delete(ONE, id), NOT_FOUND);
        assertThat(api.ok(LEARNER, get(LIST, mine.id()))).hasSize(1);

        assertThat(api.ok(LEARNER, delete(ONE, id)).isNull()).isTrue();
        assertThat(api.ok(LEARNER, get(LIST, mine.id()))).isEmpty();
        assertThat(jdbc.queryForObject("select is_deleted from learning_hub.lesson_bookmarks where bookmark_id = ?",
            Boolean.class, id)).isTrue();
        api.error(LEARNER, delete(ONE, id), NOT_FOUND);
        api.error(LEARNER, delete(ONE, 999_999_999L), NOT_FOUND);

        JsonNode again = api.ok(LEARNER, api.put(bookmark("video", mine.video(), null, 20, null), LIST, mine.id()));
        assertThat(again.path("saved").asBoolean()).as("a deleted bookmark does not count as toggled on").isTrue();
    }

    @Test
    void onlyLearnersMayUseTheEndpoints() {
        Lesson mine = fresh();

        api.http(ADMIN, get(LIST, mine.id()), 403, "CORE-ACCESS_DENIED");
        api.http(ADMIN, api.put(bookmark("pdf", mine.pdf(), 1, null, null), LIST, mine.id()), 403,
            "CORE-ACCESS_DENIED");
        api.http(ADMIN, delete(ONE, 1L), 403, "CORE-ACCESS_DENIED");
        api.http(null, get(LIST, mine.id()), 401, "CORE-UNAUTHENTICATED");
    }

    private Lesson fresh() {
        return LessonNotesFixture.lesson(fixtures, jdbc, LEARNER);
    }

    private static long saved(JsonNode toggle) {
        assertThat(toggle.path("saved").asBoolean()).isTrue();
        return toggle.path("bookmark").path("id").asLong();
    }

    private static Map<String, Object> bookmark(String type, Long blockId, Integer page, Integer second,
                                                String label) {
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("type", type);
        anchor.put("blockId", blockId);
        anchor.put("page", page);
        anchor.put("second", second);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchor", anchor);
        body.put("label", label);
        return body;
    }

    private static List<Long> ids(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }
}
