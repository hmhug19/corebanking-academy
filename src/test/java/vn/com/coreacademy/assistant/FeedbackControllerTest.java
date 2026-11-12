package vn.com.coreacademy.assistant;

import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import vn.com.coreacademy.knowledge.support.KnowledgeIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Feedback through the real controller and database. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class FeedbackControllerTest extends KnowledgeIntegrationTest {
    private static final String ME = "000004";
    private static final String THEM = "000005";
    private static final String REVIEWER = "000006";
    private static final String ADMIN = "000002";
    private static final String REVIEW = "/api/v1/assistant/feedback-review";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meters;

    private long conversation;
    private long turn;

    @BeforeEach
    void seed() {
        clean();
        conversation = jdbc.queryForObject("INSERT INTO learning_hub.assistant_conversations (owner_id, title, "
            + "last_activity_time) VALUES (?, 'Hỏi thử', now()) RETURNING conversation_id", Long.class, ME);
        turn = jdbc.queryForObject("INSERT INTO learning_hub.assistant_turns (conversation_id, surface, question, "
            + "answer, status) VALUES (?, 'AGENT', 'Câu hỏi', 'Trả lời', 'DONE') RETURNING turn_id", Long.class,
            conversation);
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM learning_hub.role_functions WHERE function_id IN "
            + "(SELECT function_id FROM learning_hub.functions WHERE code = 'AI_FEEDBACK_REVIEW')");
        jdbc.update("DELETE FROM learning_hub.assistant_feedback");
        jdbc.update("DELETE FROM learning_hub.assistant_turns");
        jdbc.update("DELETE FROM learning_hub.assistant_conversations");
    }

    private String url() {
        return "/api/v1/assistant/turns/" + turn + "/feedback";
    }

    private org.springframework.test.web.servlet.ResultActions rate(String user, String body) throws Exception {
        return mvc.perform(put(url()).with(as(user)).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    void theOwnerRatesATurnAndAReloadShowsTheSelectionUntilTheyChangeIt() throws Exception {
        rate(ME, "{\"helpful\":false,\"reasonCode\":\"outdated\",\"note\":\"Số liệu cũ\"}")
            .andExpect(jsonPath("$.data.helpful").value(false))
            .andExpect(jsonPath("$.data.reasonCode").value("outdated"));

        mvc.perform(get("/api/v1/assistant/conversations/" + conversation).with(as(ME)))
            .andExpect(jsonPath("$.data.turns[0].feedback.helpful").value(false))
            .andExpect(jsonPath("$.data.turns[0].feedback.reasonCode").value("outdated"))
            .andExpect(jsonPath("$.data.turns[0].feedback.note").value("Số liệu cũ"));

        rate(ME, "{\"helpful\":true}").andExpect(jsonPath("$.data.helpful").value(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.assistant_feedback", Integer.class))
            .isEqualTo(1);
        mvc.perform(get("/api/v1/assistant/conversations/" + conversation).with(as(ME)))
            .andExpect(jsonPath("$.data.turns[0].feedback.helpful").value(true))
            .andExpect(jsonPath("$.data.turns[0].feedback.reasonCode").doesNotExist());
    }

    @Test
    void feedbackIsCountedByThumbsAndReasonWithoutTheNote() throws Exception {
        double before = count(false, "outdated");

        rate(ME, "{\"helpful\":false,\"reasonCode\":\"outdated\",\"note\":\"Chuyện riêng\"}");

        assertThat(count(false, "outdated")).isEqualTo(before + 1);
        assertThat(meters.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
            .map(io.micrometer.core.instrument.Tag::getValue)).noneMatch(value -> value.contains("riêng")
            || value.contains(ME));
    }

    private double count(boolean helpful, String reason) {
        var counter = meters.find("assistant.feedback").tag("helpful", Boolean.toString(helpful))
            .tag("reason", reason).counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    void anotherUsersTurnAndAMissingTurnAnswerTheSameAndStoreNothing() throws Exception {
        rate(THEM, "{\"helpful\":true}").andExpect(jsonPath("$.messageCode").value("ASSISTANT-TURN_NOT_FOUND"));
        mvc.perform(put("/api/v1/assistant/turns/999999999/feedback").with(as(ME))
                .contentType(MediaType.APPLICATION_JSON).content("{\"helpful\":true}"))
            .andExpect(jsonPath("$.messageCode").value("ASSISTANT-TURN_NOT_FOUND"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.assistant_feedback", Integer.class))
            .isZero();
    }

    @Test
    void aReasonNeedsAThumbsDownAndFromTheListAndTheNoteHasALimit() throws Exception {
        rate(ME, "{\"helpful\":true,\"reasonCode\":\"other\"}")
            .andExpect(jsonPath("$.messageCode").value("CORE-VALIDATION_FAILED"));
        rate(ME, "{\"helpful\":false,\"reasonCode\":\"rude\"}")
            .andExpect(jsonPath("$.messageCode").value("CORE-VALIDATION_FAILED"));
        rate(ME, "{\"helpful\":false,\"note\":\"" + "x".repeat(2001) + "\"}")
            .andExpect(jsonPath("$.messageCode").value("CORE-VALIDATION_FAILED"));
        rate(ME, "{\"helpful\":false}").andExpect(jsonPath("$.data.helpful").value(false));
    }

    @Test
    void deletingTheConversationDeletesItsFeedback() throws Exception {
        rate(ME, "{\"helpful\":true}");

        mvc.perform(delete("/api/v1/assistant/conversations/" + conversation).with(as(ME)));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.assistant_feedback", Integer.class))
            .isZero();
    }

    /** The one seeded reviewer role is REPORT_VIEWER (user 000006); the function is granted by an operator, never seeded. */
    private void grantReview() {
        jdbc.update("INSERT INTO learning_hub.role_functions(role_id, function_id) SELECT r.role_id, f.function_id "
            + "FROM learning_hub.roles r, learning_hub.functions f WHERE r.code = 'REPORT_VIEWER' "
            + "AND f.code = 'AI_FEEDBACK_REVIEW'");
    }

    private long secondTurn(String question, String answer, String skill) {
        return jdbc.queryForObject("INSERT INTO learning_hub.assistant_turns (conversation_id, surface, question, "
            + "answer, status, skill, prompt_version, citations, retrieval_trace) VALUES (?, 'AGENT', ?, ?, 'DONE', ?, "
            + "'abc123def456', '[{\"label\":\"Tài liệu A\"}]'::jsonb, "
            + "'[{\"tool\":\"search_documents\",\"candidates\":4}]'::jsonb) RETURNING turn_id", Long.class,
            conversation, question, answer, skill);
    }

    private void rateTurn(long turnId, String user, String body) throws Exception {
        mvc.perform(put("/api/v1/assistant/turns/" + turnId + "/feedback").with(as(user))
            .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
    }

    @Test
    void withoutTheTickNothingButThumbsReasonAndNoteIsStored() throws Exception {
        grantReview();
        rate(ME, "{\"helpful\":false,\"reasonCode\":\"incorrect\",\"note\":\"Sai\"}")
            .andExpect(jsonPath("$.data.shared").value(false));

        var row = jdbc.queryForMap("SELECT shared, shared_question, shared_answer, shared_citations, shared_skill, "
            + "shared_prompt_version, shared_retrieval_trace, shared_time FROM learning_hub.assistant_feedback");
        assertThat(row.get("shared")).isEqualTo(false);
        assertThat(row.entrySet().stream().filter(e -> !"shared".equals(e.getKey())).map(java.util.Map.Entry::getValue))
            .containsOnlyNulls();
        mvc.perform(get(REVIEW).with(as(REVIEWER))).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void withTheTickAReviewerSeesThatOneTurnAndItsTraceAndNothingElseOfTheConversation() throws Exception {
        grantReview();
        long rated = secondTurn("Hạn mức chuyển khoản?", "Theo [1] là 500 triệu.", "qa_tai_lieu");
        secondTurn("Câu riêng tư khác", "Trả lời riêng tư khác", "tro_chuyen");

        rateTurn(rated, ME, "{\"helpful\":false,\"reasonCode\":\"outdated\",\"note\":\"Cũ\",\"share\":true}");

        mvc.perform(get(REVIEW).with(as(REVIEWER)))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.items[0].question").value("Hạn mức chuyển khoản?"))
            .andExpect(jsonPath("$.data.items[0].answer").value("Theo [1] là 500 triệu."))
            .andExpect(jsonPath("$.data.items[0].skill").value("qa_tai_lieu"))
            .andExpect(jsonPath("$.data.items[0].promptVersion").value("abc123def456"))
            .andExpect(jsonPath("$.data.items[0].reasonCode").value("outdated"))
            .andExpect(jsonPath("$.data.items[0].note").value("Cũ"))
            .andExpect(jsonPath("$.data.items[0].citations[0].label").value("Tài liệu A"))
            .andExpect(jsonPath("$.data.items[0].retrievalTrace[0].candidates").value(4))
            .andExpect(jsonPath("$.data.items[0].conversationId").doesNotExist())
            .andExpect(jsonPath("$.data.items[0].ownerId").doesNotExist())
            .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("riêng tư"))));
        mvc.perform(get("/api/v1/assistant/conversations/" + conversation).with(as(REVIEWER)))
            .andExpect(jsonPath("$.messageCode").value("ASSISTANT-CONVERSATION_NOT_FOUND"));
    }

    @Test
    void aCallerWithoutTheReviewFunctionGetsForbidden() throws Exception {
        grantReview();
        rate(ME, "{\"helpful\":false,\"share\":true}");

        for (String user : new String[] {ME, ADMIN}) {
            mvc.perform(get(REVIEW).with(as(user))).andExpect(status().isForbidden());
            mvc.perform(get(REVIEW + "/export").with(as(user))).andExpect(status().isForbidden());
        }
        mvc.perform(get(REVIEW).with(as(REVIEWER))).andExpect(status().isOk());
    }

    @Test
    void noSeededRoleHoldsTheReviewFunction() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.role_functions rf JOIN learning_hub.functions f "
            + "ON f.function_id = rf.function_id WHERE f.code = 'AI_FEEDBACK_REVIEW'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.functions WHERE code = 'AI_FEEDBACK_REVIEW'",
            Integer.class)).isEqualTo(1);
    }

    @Test
    void unTickingOrSwitchingToThumbsUpRemovesTheCopy() throws Exception {
        grantReview();
        rate(ME, "{\"helpful\":false,\"share\":true}").andExpect(jsonPath("$.data.shared").value(true));
        assertThat(jdbc.queryForObject("SELECT shared_question FROM learning_hub.assistant_feedback", String.class))
            .isEqualTo("Câu hỏi");

        rate(ME, "{\"helpful\":false,\"share\":false}").andExpect(jsonPath("$.data.shared").value(false));
        assertThat(jdbc.queryForObject("SELECT shared_question FROM learning_hub.assistant_feedback", String.class))
            .isNull();
        mvc.perform(get(REVIEW).with(as(REVIEWER))).andExpect(jsonPath("$.data.totalElements").value(0));

        rate(ME, "{\"helpful\":false,\"share\":true}");
        rate(ME, "{\"helpful\":true}");
        assertThat(jdbc.queryForObject("SELECT shared_answer FROM learning_hub.assistant_feedback", String.class))
            .isNull();
        mvc.perform(get(REVIEW).with(as(REVIEWER))).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void deletingTheConversationDeletesTheCopy() throws Exception {
        grantReview();
        rate(ME, "{\"helpful\":false,\"share\":true}");

        mvc.perform(delete("/api/v1/assistant/conversations/" + conversation).with(as(ME)));

        mvc.perform(get(REVIEW).with(as(REVIEWER))).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void aShareNeedsAThumbsDown() throws Exception {
        rate(ME, "{\"helpful\":true,\"share\":true}")
            .andExpect(jsonPath("$.messageCode").value("CORE-VALIDATION_FAILED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.assistant_feedback", Integer.class)).isZero();
    }

    @Test
    void theListIsNewestFirstAndFiltersByReasonAndSkill() throws Exception {
        grantReview();
        long first = secondTurn("Câu 1", "Đáp 1", "qa_tai_lieu");
        long second = secondTurn("Câu 2", "Đáp 2", "dieu_huong");
        long third = secondTurn("Câu 3", "Đáp 3", "qa_tai_lieu");
        rateTurn(first, ME, "{\"helpful\":false,\"reasonCode\":\"incorrect\",\"share\":true}");
        jdbc.update("UPDATE learning_hub.assistant_feedback SET shared_time = shared_time - interval '2 hours' "
            + "WHERE turn_id = ?", first);
        rateTurn(second, ME, "{\"helpful\":false,\"reasonCode\":\"outdated\",\"share\":true}");
        jdbc.update("UPDATE learning_hub.assistant_feedback SET shared_time = shared_time - interval '1 hours' "
            + "WHERE turn_id = ?", second);
        rateTurn(third, ME, "{\"helpful\":false,\"reasonCode\":\"outdated\",\"share\":true}");

        mvc.perform(get(REVIEW).with(as(REVIEWER)))
            .andExpect(jsonPath("$.data.items[0].question").value("Câu 3"))
            .andExpect(jsonPath("$.data.items[1].question").value("Câu 2"))
            .andExpect(jsonPath("$.data.items[2].question").value("Câu 1"));
        mvc.perform(get(REVIEW).param("reason", "outdated").with(as(REVIEWER)))
            .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get(REVIEW).param("skill", "qa_tai_lieu").with(as(REVIEWER)))
            .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc.perform(get(REVIEW).param("reason", "outdated").param("skill", "qa_tai_lieu").with(as(REVIEWER)))
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.items[0].question").value("Câu 3"));
    }

    @Test
    void theCsvExportCarriesTheSameRowsAndDefusesFormulas() throws Exception {
        grantReview();
        long turnId = secondTurn("=HYPERLINK(\"x\")", "Đáp, có \"dấu\" phẩy", "qa_tai_lieu");
        rateTurn(turnId, ME, "{\"helpful\":false,\"reasonCode\":\"incorrect\",\"share\":true}");

        String csv = mvc.perform(get(REVIEW + "/export").with(as(REVIEWER))).andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith("text/csv"))
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(csv).contains("question").contains("'=HYPERLINK").contains("\"Đáp, có \"\"dấu\"\" phẩy\"")
            .contains("qa_tai_lieu").contains("incorrect");
        assertThat(csv).doesNotContain("\n=HYPERLINK").doesNotContain(",=HYPERLINK");
    }

    private static RequestPostProcessor as(String userId) {
        return jwt().jwt(token -> token.subject(userId));
    }
}
