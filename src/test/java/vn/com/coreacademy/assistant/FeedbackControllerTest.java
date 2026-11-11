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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/** Feedback through the real controller and database. */
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
class FeedbackControllerTest extends KnowledgeIntegrationTest {
    private static final String ME = "000004";
    private static final String THEM = "000005";

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

    private static RequestPostProcessor as(String userId) {
        return jwt().jwt(token -> token.subject(userId));
    }
}
