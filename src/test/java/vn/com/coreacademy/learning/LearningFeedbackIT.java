package vn.com.coreacademy.learning;

import java.nio.charset.StandardCharsets;
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
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/**
 * Nhận xét, câu hỏi và báo nội dung F3, màn phản hồi F1 và trang nhận xét F4: trạng thái từng loại, độ dài nội dung,
 * neo, giới hạn mỗi ngày học, quyền xem và phân trang.
 */
class LearningFeedbackIT extends AssessmentIntegrationTest {
    private static final String FEEDBACK = "/api/v1/me/learning/lessons/{lesson}/feedback";
    private static final String COMMENTS = "/api/v1/me/learning/lessons/{lesson}/comments";
    private static final String RATING = "/api/v1/me/learning/lessons/{lesson}/rating";
    private static final String NOT_FOUND = "LEARNING-NOT_FOUND";
    private static final String INVALID_REFERENCE = "LEARNING-INVALID_REFERENCE";
    private static final String VALIDATION = "CORE-VALIDATION_FAILED";
    private static final String LIMIT = "LEARNING-FEEDBACK_LIMIT";
    private static final String ICT = "Asia/Ho_Chi_Minh";

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
    void savesEachKindWithItsStatusAndTheAnchorAsSent() {
        Lesson mine = fresh(LEARNER);

        JsonNode comment = api.ok(LEARNER, api.post(send("comment", "  Bài rất rõ ràng  ", "none", null, null, null),
            FEEDBACK, mine.id()));
        JsonNode question = api.ok(LEARNER, api.post(send("question", "Trang này nghĩa là gì?", "pdf", mine.pdf(), 3,
            null), FEEDBACK, mine.id()));
        JsonNode report = api.ok(LEARNER, api.post(send("report", "Sai thông tin ở đây", "video", mine.video(), null,
            12), FEEDBACK, mine.id()));

        assertThat(comment.fieldNames()).toIterable().containsExactly("id", "kind", "text", "anchor", "status",
            "reply", "replyTime", "createTime");
        assertThat(comment.path("kind").asText()).isEqualTo("comment");
        assertThat(comment.path("text").asText()).as("trimmed").isEqualTo("Bài rất rõ ràng");
        assertThat(comment.path("status").isNull()).as("a comment has no status").isTrue();
        assertThat(comment.path("anchor").path("type").asText()).isEqualTo("none");
        assertThat(comment.path("anchor").path("blockId").isNull()).isTrue();
        assertThat(comment.path("reply").isNull()).isTrue();
        assertThat(comment.path("replyTime").isNull()).isTrue();
        assertThat(comment.path("createTime").asText()).isNotBlank();

        assertThat(question.path("kind").asText()).isEqualTo("question");
        assertThat(question.path("status").asText()).isEqualTo("new");
        assertThat(question.path("anchor").path("type").asText()).isEqualTo("pdf");
        assertThat(question.path("anchor").path("blockId").asLong()).isEqualTo(mine.pdf());
        assertThat(question.path("anchor").path("page").asInt()).isEqualTo(3);
        assertThat(question.path("anchor").path("second").isNull()).isTrue();

        assertThat(report.path("kind").asText()).isEqualTo("report");
        assertThat(report.path("status").asText()).isEqualTo("new");
        assertThat(report.path("anchor").path("type").asText()).isEqualTo("video");
        assertThat(report.path("anchor").path("second").asInt()).isEqualTo(12);
        assertThat(report.path("anchor").path("page").isNull()).isTrue();

        Map<String, Object> row = jdbc.queryForMap("""
            select user_id, lesson_id, course_id, kind, stars, body, block_id, anchor_type, pdf_page, video_second,
                   status, created_by, updated_by, is_deleted
            from learning_hub.lesson_feedback where feedback_id = ?
            """, question.path("id").asLong());
        assertThat(row).containsEntry("user_id", LEARNER).containsEntry("lesson_id", mine.id())
            .containsEntry("course_id", mine.course().id()).containsEntry("kind", "question")
            .containsEntry("body", "Trang này nghĩa là gì?").containsEntry("block_id", mine.pdf())
            .containsEntry("anchor_type", "pdf").containsEntry("pdf_page", 3).containsEntry("status", "new")
            .containsEntry("created_by", LEARNER).containsEntry("updated_by", LEARNER)
            .containsEntry("is_deleted", false);
        assertThat(row.get("stars")).isNull();
        assertThat(row.get("video_second")).isNull();
    }

    @Test
    void acceptsTrimmedTextOfFiveToOneThousandCharactersWithTheFieldMessage() {
        Lesson mine = fresh(LEARNER);

        for (String tooShort : new String[] {"abcd", "    abcd    ", "", "   ", "\n\t"}) {
            JsonNode body = api.error(LEARNER, api.post(send("comment", tooShort, "none", null, null, null), FEEDBACK,
                mine.id()), VALIDATION);
            assertThat(body.path("errors").get(0).path("field").asText()).isEqualTo("text");
            assertThat(body.path("errors").get(0).path("message").asText())
                .isEqualTo("Nội dung cần tối thiểu 5 ký tự.");
        }
        JsonNode tooLong = api.error(LEARNER, api.post(send("question", "a".repeat(1001), "none", null, null, null),
            FEEDBACK, mine.id()), VALIDATION);
        assertThat(tooLong.path("errors").get(0).path("field").asText()).isEqualTo("text");
        assertThat(tooLong.path("errors").get(0).path("message").asText()).isEqualTo("Nội dung tối đa 1000 ký tự.");
        api.error(LEARNER, api.post("{\"kind\":\"comment\",\"anchor\":{\"type\":\"none\"}}", FEEDBACK, mine.id()),
            VALIDATION);
        assertThat(count(LEARNER, mine.id())).as("nothing was saved").isZero();

        api.ok(LEARNER, api.post(send("comment", "abcde", "none", null, null, null), FEEDBACK, mine.id()));
        api.ok(LEARNER, api.post(send("question", "a".repeat(1000), "none", null, null, null), FEEDBACK, mine.id()));
        JsonNode padded = api.ok(LEARNER, api.post(send("report", "  " + "b".repeat(1000) + "  ", "none", null, null,
            null), FEEDBACK, mine.id()));
        assertThat(padded.path("text").asText()).hasSize(1000);
        assertThat(count(LEARNER, mine.id())).isEqualTo(3);
    }

    @Test
    void checksTheKindAndTheAnchorAsForNotes() {
        Lesson mine = fresh(LEARNER);
        Lesson other = fresh(LEARNER);

        for (String kind : new String[] {"rating", "note", "", "COMMENT"}) {
            api.error(LEARNER, api.post(send(kind, "Nội dung hợp lệ", "none", null, null, null), FEEDBACK, mine.id()),
                VALIDATION);
        }
        api.error(LEARNER, api.post("{\"kind\":\"comment\",\"text\":\"Nội dung hợp lệ\"}", FEEDBACK, mine.id()),
            VALIDATION);
        for (String kind : new String[] {"comment", "question", "report"}) {
            api.error(LEARNER, api.post(send(kind, "Nội dung hợp lệ", "pdf", mine.pdf(), 5, null), FEEDBACK,
                mine.id()), INVALID_REFERENCE);
            api.error(LEARNER, api.post(send(kind, "Nội dung hợp lệ", "pdf", mine.pdf(), 0, null), FEEDBACK,
                mine.id()), INVALID_REFERENCE);
            api.error(LEARNER, api.post(send(kind, "Nội dung hợp lệ", "video", mine.video(), null, 31), FEEDBACK,
                mine.id()), INVALID_REFERENCE);
            api.error(LEARNER, api.post(send(kind, "Nội dung hợp lệ", "pdf", mine.video(), 1, null), FEEDBACK,
                mine.id()), INVALID_REFERENCE);
            api.error(LEARNER, api.post(send(kind, "Nội dung hợp lệ", "pdf", other.pdf(), 1, null), FEEDBACK,
                mine.id()), INVALID_REFERENCE);
            api.error(LEARNER, api.post(send(kind, "Nội dung hợp lệ", "video", mine.failedVideo(), null, 1),
                FEEDBACK, mine.id()), INVALID_REFERENCE);
            api.error(LEARNER, api.post(send(kind, "Nội dung hợp lệ", "pdf", mine.pdf(), null, null), FEEDBACK,
                mine.id()), VALIDATION);
            api.error(LEARNER, api.post(send(kind, "Nội dung hợp lệ", "none", mine.pdf(), null, null), FEEDBACK,
                mine.id()), VALIDATION);
        }
        assertThat(count(LEARNER, mine.id())).as("nothing was saved").isZero();

        api.ok(LEARNER, api.post(send("comment", "Trang cuối", "pdf", mine.pdf(), 4, null), FEEDBACK, mine.id()));
        api.ok(LEARNER, api.post(send("question", "Giây cuối", "video", mine.video(), null, 30), FEEDBACK,
            mine.id()));
    }

    @Test
    void stopsAtTwentySendsPerLessonPerLearningDayInVietnamTime() {
        Lesson mine = fresh(LEARNER, LEARNER_2);
        Lesson sibling = fresh(LEARNER);
        // 20 lần gửi lúc 23:59 hôm qua theo giờ Việt Nam không tính vào hôm nay.
        insertSent(LEARNER, mine, 20, "(date_trunc('day', now() at time zone '" + ICT + "') - interval '1 minute') "
            + "at time zone '" + ICT + "'");

        for (int i = 1; i <= 20; i++) {
            String kind = i % 3 == 0 ? "report" : i % 3 == 1 ? "comment" : "question";
            api.ok(LEARNER, api.post(send(kind, "Lần gửi thứ " + i, "none", null, null, null), FEEDBACK, mine.id()));
        }
        api.error(LEARNER, api.post(send("comment", "Lần thứ 21", "none", null, null, null), FEEDBACK, mine.id()),
            LIMIT);
        api.error(LEARNER, api.post(send("question", "Lần thứ 21", "none", null, null, null), FEEDBACK, mine.id()),
            LIMIT);
        api.error(LEARNER, api.post(send("report", "Lần thứ 21", "none", null, null, null), FEEDBACK, mine.id()),
            LIMIT);

        assertThat(todaySent(LEARNER, mine.id())).as("the refused sends saved nothing").isEqualTo(20);
        api.ok(LEARNER, api.post(send("comment", "Bài khác vẫn gửi được", "none", null, null, null), FEEDBACK,
            sibling.id()));
        api.ok(LEARNER_2, api.post(send("comment", "Người khác vẫn gửi được", "none", null, null, null), FEEDBACK,
            mine.id()));
        api.ok(LEARNER, api.put("{\"stars\":4}", RATING, mine.id()));
        api.ok(LEARNER, api.put("{\"stars\":5}", RATING, mine.id()));
    }

    @Test
    void theLearningDayStartsAtMidnightInVietnamNotUtc() {
        Lesson mine = fresh(LEARNER);
        insertSent(LEARNER, mine, 20, "date_trunc('day', now() at time zone '" + ICT + "') at time zone '" + ICT
            + "'");

        api.error(LEARNER, api.post(send("comment", "Sau nửa đêm", "none", null, null, null), FEEDBACK, mine.id()),
            LIMIT);
    }

    @Test
    void theLimitHoldsUnderConcurrentSends() throws Exception {
        Lesson mine = fresh(LEARNER);
        insertSent(LEARNER, mine, 15, "now()");
        int calls = 12;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        try {
            List<Future<String>> results = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                int number = i;
                results.add(pool.submit(() -> {
                    start.await();
                    String content = api.raw(LEARNER, api.post(send("comment", "Gửi đồng thời " + number, "none", null,
                        null, null), FEEDBACK, mine.id())).getContentAsString(StandardCharsets.UTF_8);
                    return json.readTree(content).path("messageCode").asText();
                }));
            }
            start.countDown();
            int accepted = 0;
            int refused = 0;
            for (Future<String> result : results) {
                String code = result.get();
                accepted += "CORE-SUCCESS".equals(code) ? 1 : 0;
                refused += LIMIT.equals(code) ? 1 : 0;
            }

            assertThat(accepted).as("only the 5 remaining slots").isEqualTo(5);
            assertThat(refused).isEqualTo(calls - 5);
            assertThat(todaySent(LEARNER, mine.id())).isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void anotherLearnerSeesTheCommentWithTheAuthorsNameButNeverTheQuestionOrReport() {
        Lesson shared = fresh(LEARNER, LEARNER_2);
        String authorName = jdbc.queryForObject("select full_name from learning_hub.users where user_id = ?",
            String.class, LEARNER);
        JsonNode comment = api.ok(LEARNER, api.post(send("comment", "Nhận xét công khai", "pdf", shared.pdf(), 2,
            null), FEEDBACK, shared.id()));
        JsonNode question = api.ok(LEARNER, api.post(send("question", "Câu hỏi riêng", "none", null, null, null),
            FEEDBACK, shared.id()));
        JsonNode report = api.ok(LEARNER, api.post(send("report", "Báo cáo riêng", "none", null, null, null),
            FEEDBACK, shared.id()));
        api.ok(LEARNER, api.put("{\"stars\":5}", RATING, shared.id()));

        JsonNode author = api.ok(LEARNER, get(FEEDBACK, shared.id()));
        assertThat(author.fieldNames()).toIterable().containsExactly("rating", "myStars", "comments", "mine");
        assertThat(author.path("myStars").asInt()).isEqualTo(5);
        assertThat(author.path("comments").path("items")).hasSize(1);
        JsonNode own = author.path("comments").path("items").get(0);
        assertThat(own.fieldNames()).toIterable().containsExactly("id", "authorName", "text", "anchor", "createTime",
            "mine");
        assertThat(own.path("mine").asBoolean()).isTrue();
        assertThat(ids(author.path("mine"))).as("own question and report, newest first")
            .containsExactly(report.path("id").asLong(), question.path("id").asLong());
        assertThat(author.path("mine").get(0).path("kind").asText()).isEqualTo("report");
        assertThat(author.path("mine").get(0).path("status").asText()).isEqualTo("new");

        JsonNode reader = api.ok(LEARNER_2, get(FEEDBACK, shared.id()));
        JsonNode seen = reader.path("comments").path("items").get(0);
        assertThat(reader.path("comments").path("items")).hasSize(1);
        assertThat(seen.path("id").asLong()).isEqualTo(comment.path("id").asLong());
        assertThat(seen.path("authorName").asText()).isEqualTo(authorName);
        assertThat(seen.path("text").asText()).isEqualTo("Nhận xét công khai");
        assertThat(seen.path("mine").asBoolean()).isFalse();
        assertThat(seen.path("anchor").path("type").asText()).isEqualTo("pdf");
        assertThat(seen.path("anchor").path("page").asInt()).isEqualTo(2);
        assertThat(reader.path("mine")).as("another learner's question and report stay private").isEmpty();
        assertThat(reader.path("myStars").isNull()).as("the rating belongs to its author").isTrue();
        assertThat(reader.path("rating").path("average").decimalValue()).isEqualByComparingTo("5.0");
        assertThat(reader.path("rating").path("count").asLong()).isEqualTo(1);
        assertThat(api.ok(LEARNER_2, get(COMMENTS, shared.id())).path("items")).hasSize(1);
        assertThat(api.ok(LEARNER_2, get(COMMENTS, shared.id())).toString()).doesNotContain("Câu hỏi riêng")
            .doesNotContain("Báo cáo riêng");
    }

    @Test
    void aLessonWithoutFeedbackHasNullRatingAndEmptyLists() {
        Lesson mine = fresh(LEARNER);

        JsonNode view = api.ok(LEARNER, get(FEEDBACK, mine.id()));

        assertThat(view.path("rating").isNull()).isTrue();
        assertThat(view.path("myStars").isNull()).isTrue();
        assertThat(view.path("comments").fieldNames()).toIterable().containsExactly("items", "page", "size",
            "totalElements", "totalPages");
        assertThat(view.path("comments").path("items")).isEmpty();
        assertThat(view.path("comments").path("totalElements").asLong()).isZero();
        assertThat(view.path("mine")).isEmpty();
    }

    @Test
    void pagesCommentsNewestFirstTwentyAtATime() {
        Lesson mine = fresh(LEARNER, LEARNER_2);
        insertSent(LEARNER_2, mine, 25, "now()");
        long newest = api.ok(LEARNER, api.post(send("comment", "Nhận xét mới nhất", "none", null, null, null),
            FEEDBACK, mine.id())).path("id").asLong();
        // Câu hỏi và báo nội dung không lẫn vào danh sách nhận xét.
        api.ok(LEARNER, api.post(send("question", "Câu hỏi không phải nhận xét", "none", null, null, null), FEEDBACK,
            mine.id()));

        JsonNode first = api.ok(LEARNER, get(FEEDBACK, mine.id())).path("comments");
        assertThat(first.path("items")).hasSize(20);
        assertThat(first.path("page").asInt()).isZero();
        assertThat(first.path("size").asInt()).isEqualTo(20);
        assertThat(first.path("totalElements").asLong()).isEqualTo(26);
        assertThat(first.path("totalPages").asInt()).isEqualTo(2);
        assertThat(first.path("items").get(0).path("id").asLong()).as("newest first").isEqualTo(newest);
        assertThat(first.path("items").get(0).path("mine").asBoolean()).isTrue();
        assertThat(first.path("items").get(1).path("mine").asBoolean()).isFalse();

        JsonNode second = api.ok(LEARNER, get(COMMENTS + "?page=1", mine.id()));
        assertThat(second.path("items")).hasSize(6);
        assertThat(second.path("page").asInt()).isEqualTo(1);
        assertThat(second.path("totalElements").asLong()).isEqualTo(26);
        List<Long> all = new ArrayList<>(ids(first.path("items")));
        all.addAll(ids(second.path("items")));
        assertThat(all).doesNotHaveDuplicates().isSortedAccordingTo((a, b) -> Long.compare(b, a));

        assertThat(api.ok(LEARNER, get(COMMENTS + "?page=0&size=100", mine.id())).path("items")).hasSize(26);
        assertThat(api.ok(LEARNER, get(COMMENTS + "?page=5", mine.id())).path("items")).isEmpty();
        assertThat(api.ok(LEARNER, get(COMMENTS + "?size=5&page=2", mine.id())).path("items")).hasSize(5);
        for (String invalid : new String[] {"size=101", "size=0", "page=-1", "size=abc"}) {
            api.error(LEARNER, get(COMMENTS + "?" + invalid, mine.id()), VALIDATION);
        }
    }

    @Test
    void refusesALessonOutsideTheCallersEnrollments() {
        Lesson mine = fresh(LEARNER);
        Lesson withdrawn = fresh(LEARNER);
        jdbc.update("update learning_hub.course_enrollments set status = 'withdrawn' where course_id = ?",
            withdrawn.course().id());
        Map<String, Object> body = send("comment", "Nội dung hợp lệ", "none", null, null, null);

        api.error(LEARNER_2, get(FEEDBACK, mine.id()), NOT_FOUND);
        api.error(LEARNER_2, get(COMMENTS, mine.id()), NOT_FOUND);
        api.error(LEARNER_2, api.post(body, FEEDBACK, mine.id()), NOT_FOUND);
        api.error(LEARNER, get(FEEDBACK, withdrawn.id()), NOT_FOUND);
        api.error(LEARNER, api.post(body, FEEDBACK, withdrawn.id()), NOT_FOUND);
        api.error(LEARNER, get(FEEDBACK, 999_999_999L), NOT_FOUND);
        api.error(LEARNER, get(COMMENTS, 999_999_999L), NOT_FOUND);
        api.error(LEARNER, api.post(body, FEEDBACK, 999_999_999L), NOT_FOUND);
        assertThat(count(LEARNER_2, mine.id()) + count(LEARNER, withdrawn.id())).isZero();
    }

    @Test
    void onlyLearnersMayUseTheEndpoints() {
        Lesson mine = fresh(LEARNER);
        Map<String, Object> body = send("comment", "Nội dung hợp lệ", "none", null, null, null);

        api.http(ADMIN, get(FEEDBACK, mine.id()), 403, "CORE-ACCESS_DENIED");
        api.http(ADMIN, get(COMMENTS, mine.id()), 403, "CORE-ACCESS_DENIED");
        api.http(ADMIN, api.post(body, FEEDBACK, mine.id()), 403, "CORE-ACCESS_DENIED");
        api.http(null, get(FEEDBACK, mine.id()), 401, "CORE-UNAUTHENTICATED");
    }

    /** Bài riêng cho từng ca, để các ca không đếm chung giới hạn hay nhận xét của nhau. */
    private Lesson fresh(String... learners) {
        return LessonNotesFixture.lesson(fixtures, jdbc, learners);
    }

    /** Chèn n nhận xét đã gửi vào lúc cho trước (biểu thức SQL), không qua API. */
    private void insertSent(String userId, Lesson lesson, int n, String createTime) {
        jdbc.update("""
            insert into learning_hub.lesson_feedback (user_id, lesson_id, course_id, kind, body, anchor_type,
                created_by, updated_by, create_time)
            select ?, ?, ?, 'comment', 'Đã gửi ' || g, 'none', ?, ?, %s from generate_series(1, ?) g
            """.formatted(createTime), userId, lesson.id(), lesson.course().id(), userId, userId, n);
    }

    private int count(String userId, long lessonId) {
        return jdbc.queryForObject("""
            select count(*) from learning_hub.lesson_feedback where user_id = ? and lesson_id = ?
            """, Integer.class, userId, lessonId);
    }

    private int todaySent(String userId, long lessonId) {
        return jdbc.queryForObject("""
            select count(*) from learning_hub.lesson_feedback
            where user_id = ? and lesson_id = ? and kind <> 'rating'
              and create_time >= date_trunc('day', now() at time zone '%s') at time zone '%s'
            """.formatted(ICT, ICT), Integer.class, userId, lessonId);
    }

    private static Map<String, Object> send(String kind, String text, String type, Long blockId, Integer page,
                                            Integer second) {
        Map<String, Object> anchor = new LinkedHashMap<>();
        anchor.put("type", type);
        anchor.put("blockId", blockId);
        anchor.put("page", page);
        anchor.put("second", second);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kind", kind);
        body.put("text", text);
        body.put("anchor", anchor);
        return body;
    }

    private static List<Long> ids(JsonNode list) {
        List<Long> ids = new ArrayList<>();
        list.forEach(item -> ids.add(item.path("id").asLong()));
        return ids;
    }
}
