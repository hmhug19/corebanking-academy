package vn.com.coreacademy.learning;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * N6 trang Đã lưu. Mỗi ca bắt đầu từ bảng ghi chú và đánh dấu trống, vì số lượng và thứ tự là theo cả người dùng chứ
 * không theo một bài học.
 */
class LearningSavedIT extends AssessmentIntegrationTest {
    private static final String SAVED = "/api/v1/me/learning/saved";
    private static final String NOTES = "/api/v1/me/learning/lessons/{lesson}/notes";
    private static final String BOOKMARKS = "/api/v1/me/learning/lessons/{lesson}/bookmarks";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private AssessmentFixtures fixtures;

    @BeforeEach
    void emptyTables() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        jdbc.update("delete from learning_hub.lesson_notes");
        jdbc.update("delete from learning_hub.lesson_bookmarks");
    }

    @Test
    void pagesTheNotesTabNewestFirstWithCourseAndLessonTitlesAndCountsOfBothTabs() {
        Lesson first = lesson();
        Lesson second = lesson();
        long a = note(first, "pdf", first.pdf(), 3, null, "ghi chú A");
        long b = note(second, "video", second.video(), null, 20, "ghi chú B");
        long c = note(first, "none", null, null, null, "ghi chú C");
        bookmark(first, "pdf", first.pdf(), 2);
        bookmark(second, "video", second.video(), null, 5);

        JsonNode firstPage = api.ok(LEARNER, get(SAVED + "?size=2"));
        JsonNode secondPage = api.ok(LEARNER, get(SAVED + "?size=2&page=1"));
        JsonNode beyond = api.ok(LEARNER, get(SAVED + "?size=2&page=5"));

        assertThat(firstPage.fieldNames()).toIterable().containsExactly("page", "counts");
        assertThat(firstPage.path("counts").path("notes").asInt()).isEqualTo(3);
        assertThat(firstPage.path("counts").path("bookmarks").asInt()).isEqualTo(2);
        JsonNode page = firstPage.path("page");
        assertThat(page.path("totalElements").asInt()).isEqualTo(3);
        assertThat(page.path("totalPages").asInt()).isEqualTo(2);
        assertThat(page.path("page").asInt()).isZero();
        assertThat(page.path("size").asInt()).isEqualTo(2);
        assertThat(ids(page.path("items"))).as("newest first").containsExactly(c, b);
        assertThat(ids(secondPage.path("page").path("items"))).containsExactly(a);
        assertThat(beyond.path("page").path("items")).isEmpty();
        assertThat(beyond.path("page").path("totalElements").asInt()).isEqualTo(3);

        JsonNode item = secondPage.path("page").path("items").get(0);
        assertThat(item.fieldNames()).toIterable().containsExactly("kind", "id", "lessonId", "anchor", "text",
            "label", "createTime", "courseId", "courseTitle", "lessonTitle");
        assertThat(item.path("kind").asText()).isEqualTo("note");
        assertThat(item.path("text").asText()).isEqualTo("ghi chú A");
        assertThat(item.path("label").isNull()).isTrue();
        assertThat(item.path("lessonId").asLong()).isEqualTo(first.id());
        assertThat(item.path("anchor").path("page").asInt()).isEqualTo(3);
        assertThat(item.path("courseId").asLong()).isEqualTo(first.course().id());
        assertThat(item.path("courseTitle").asText()).isEqualTo("Khóa kiểm thử Quiz");
        assertThat(item.path("lessonTitle").asText()).isEqualTo("Bài 1");
    }

    @Test
    void listsTheBookmarksTabWithTheirLabels() {
        Lesson mine = lesson();
        bookmark(mine, "pdf", mine.pdf(), 2);
        long newest = bookmark(mine, "video", mine.video(), null, 5);
        note(mine, "none", null, null, null, "một ghi chú");

        JsonNode saved = api.ok(LEARNER, get(SAVED + "?kind=bookmarks"));
        JsonNode items = saved.path("page").path("items");

        assertThat(saved.path("page").path("totalElements").asInt()).isEqualTo(2);
        assertThat(saved.path("counts").path("notes").asInt()).isEqualTo(1);
        assertThat(saved.path("counts").path("bookmarks").asInt()).isEqualTo(2);
        assertThat(items.get(0).path("id").asLong()).isEqualTo(newest);
        assertThat(items.get(0).path("kind").asText()).isEqualTo("bookmark");
        assertThat(items.get(0).path("label").asText()).isEqualTo("Khối video 11 — 0:05");
        assertThat(items.get(0).path("text").isNull()).isTrue();
        assertThat(items.get(0).path("anchor").path("second").asInt()).isEqualTo(5);
        assertThat(items.get(1).path("anchor").path("page").asInt()).isEqualTo(2);
    }

    @Test
    void showsOnlyTheCallersItems() {
        Lesson shared = LessonNotesFixture.lesson(fixtures, jdbc, LEARNER, LEARNER_2);
        note(shared, "none", null, null, null, "của LEARNER");
        bookmark(shared, "pdf", shared.pdf(), 1);

        JsonNode other = api.ok(LEARNER_2, get(SAVED));

        assertThat(other.path("page").path("items")).isEmpty();
        assertThat(other.path("counts").path("notes").asInt()).isZero();
        assertThat(other.path("counts").path("bookmarks").asInt()).isZero();
        assertThat(api.ok(LEARNER_2, get(SAVED + "?kind=bookmarks")).path("page").path("items")).isEmpty();
        assertThat(api.ok(LEARNER, get(SAVED)).path("page").path("items")).hasSize(1);
    }

    @Test
    void leavesOutDeletedItemsAndTheirCounts() {
        Lesson mine = lesson();
        long kept = note(mine, "none", null, null, null, "giữ");
        long gone = note(mine, "none", null, null, null, "xóa");
        long goneBookmark = bookmark(mine, "pdf", mine.pdf(), 1);
        bookmark(mine, "pdf", mine.pdf(), 2);

        api.ok(LEARNER, delete("/api/v1/me/learning/notes/{id}", gone));
        api.ok(LEARNER, delete("/api/v1/me/learning/bookmarks/{id}", goneBookmark));

        JsonNode saved = api.ok(LEARNER, get(SAVED));
        assertThat(ids(saved.path("page").path("items"))).containsExactly(kept);
        assertThat(saved.path("page").path("totalElements").asInt()).isEqualTo(1);
        assertThat(saved.path("counts").path("notes").asInt()).isEqualTo(1);
        assertThat(saved.path("counts").path("bookmarks").asInt()).isEqualTo(1);
    }

    @Test
    void keepsItemsOfALessonTheLearnerCanNoLongerOpenSoTheyCanBeDeleted() {
        Lesson mine = lesson();
        long id = note(mine, "none", null, null, null, "bài đã rút");
        jdbc.update("update learning_hub.course_enrollments set status = 'withdrawn' where course_id = ?",
            mine.course().id());

        JsonNode items = api.ok(LEARNER, get(SAVED)).path("page").path("items");

        assertThat(ids(items)).containsExactly(id);
        api.ok(LEARNER, delete("/api/v1/me/learning/notes/{id}", id));
    }

    @Test
    void validatesTheQueryAndRestrictsTheEndpointToLearners() {
        for (String query : new String[] {"?kind=planet", "?size=101", "?size=0", "?page=-1"}) {
            api.error(LEARNER, get(SAVED + query), "CORE-VALIDATION_FAILED");
        }
        assertThat(api.ok(LEARNER, get(SAVED + "?size=100")).path("page").path("size").asInt()).isEqualTo(100);

        api.http(ADMIN, get(SAVED), 403, "CORE-ACCESS_DENIED");
        api.http(null, get(SAVED), 401, "CORE-UNAUTHENTICATED");
    }

    private Lesson lesson() {
        return LessonNotesFixture.lesson(fixtures, jdbc, LEARNER);
    }

    private long note(Lesson lesson, String type, Long blockId, Integer page, Integer second, String text) {
        return api.ok(LEARNER, api.post(Map.of("anchor", anchor(type, blockId, page, second), "text", text), NOTES,
            lesson.id())).path("id").asLong();
    }

    private long bookmark(Lesson lesson, String type, Long blockId, Integer page) {
        return bookmark(lesson, type, blockId, page, null);
    }

    private long bookmark(Lesson lesson, String type, Long blockId, Integer page, Integer second) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("anchor", anchor(type, blockId, page, second));
        body.put("label", null);
        return api.ok(LEARNER, api.put(body, BOOKMARKS, lesson.id())).path("bookmark").path("id").asLong();
    }

    private static Map<String, Object> anchor(String type, Long blockId, Integer page, Integer second) {
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("type", type);
        anchor.put("blockId", blockId);
        anchor.put("page", page);
        anchor.put("second", second);
        return anchor;
    }

    private static List<Long> ids(JsonNode items) {
        List<Long> ids = new ArrayList<>();
        items.forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }
}
