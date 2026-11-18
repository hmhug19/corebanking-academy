package vn.com.coreacademy.assistant;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.com.coreacademy.assistant.conversation.Conversations;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.agent.LessonFocus;
import vn.com.coreacademy.assistant.config.AssistantModels;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.assistant.skill.SkillSelector;
import vn.com.coreacademy.identity.dto.FunctionInfo;
import vn.com.coreacademy.identity.dto.UserRights;
import vn.com.coreacademy.knowledge.ai.OllamaClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;

class SkillSelectorTest {
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final SkillSelector selector = new SkillSelector(ollama, new SkillCatalog(), AssistantModels.defaults());
    private final AgentContext inLesson = new AgentContext("000004", null, null,
        new LessonFocus(26, 3, 88, "Tín dụng", "Chương 1", "Bài 1"), null);

    @Test
    void aQuestionAskedFromALessonGoesToTheLessonSkillWithoutAClassifierCall() {
        assertThat(selector.select(null, "Bài này nói gì?", inLesson).id()).isEqualTo("hoi_bai_hoc");
        assertThat(selector.select(null, "Giải thích khái niệm tài sản bảo đảm trong khóa này", inLesson).id())
            .isEqualTo("hoi_bai_hoc");
        verifyNoInteractions(ollama);
    }

    @Test
    void aSkillTheAppNamesStillWinsInALesson() {
        assertThat(selector.select("tom_tat_bai", "Tóm tắt bài này", inLesson).id()).isEqualTo("tom_tat_bai");
    }

    @Test
    void theLessonSkillIsNotOfferedWithoutAnOpenLesson() {
        AgentContext none = new AgentContext("000004", null, null);

        assertThat(selector.select("hoi_bai_hoc", "Bài này nói gì?", none).id()).isEqualTo("qa_tai_lieu");
    }

    @Test
    void theClassifierIsGivenTheLastExchangeWithACutAnswerAndTheNewQuestionLast() {
        when(ollama.chat(anyList(), any())).thenReturn("ho_so");
        String longAnswer = "Đã gửi thẻ xác nhận. " + "x".repeat(600);

        var skill = selector.select(null, "Còn email thì sao?", new AgentContext("000004", null, null),
            new Conversations.Exchange("Cập nhật chức danh của tôi", longAnswer));

        ArgumentCaptor<List<OllamaClient.Message>> sent = ArgumentCaptor.captor();
        verify(ollama).chat(sent.capture(), any());
        String user = sent.getValue().get(1).content();
        assertThat(skill.id()).isEqualTo("ho_so");
        assertThat(user).contains("Cập nhật chức danh của tôi").contains("Đã gửi thẻ xác nhận").endsWith("Còn email thì sao?");
        assertThat(user.length()).isLessThan(SkillSelector.ANSWER_CUT + 400);
    }

    private static AgentContext learnerWithScreens() {
        return new AgentContext("000004", null, new UserRights(List.of(), List.of(
            new FunctionInfo(1L, "PROFILE", "Hồ sơ cá nhân", "/profile", null, 10),
            new FunctionInfo(2L, "COURSE_LEARNING", "Học tập", "/learn", null, 20),
            new FunctionInfo(3L, "COURSE_DETAIL", "Chi tiết khóa", "/learn/:id", null, 30)), Map.of()));
    }

    @Test
    void aPlainOpenThePageRequestGoesToNavigationWithoutAClassifierCall() {
        for (String question : List.of("Mở trang khóa học của tôi", "mở trang hồ sơ cá nhân", "Vào màn hình tiến độ",
            "Đi tới trang cộng đồng", "  Mở mục Học tập")) {
            assertThat(selector.select(null, question, learnerWithScreens()).id()).as(question).isEqualTo("dieu_huong");
        }
        verifyNoInteractions(ollama);
    }

    @Test
    void aQuestionAboutDataIsNotTakenForNavigationByTheWording() {
        when(ollama.chat(anyList(), any())).thenReturn("hoc_tap_cua_toi");

        assertThat(selector.select(null, "Danh sách khóa học quá hạn của tôi", learnerWithScreens()).id())
            .isEqualTo("hoc_tap_cua_toi");
        assertThat(selector.select(null, "Mở trang 5 của tài liệu nói gì", learnerWithScreens()).id())
            .isEqualTo("hoc_tap_cua_toi");
    }

    @Test
    void theClassifierPromptNamesTheUsersScreensForNavigationAndExcludesPersonalDataFromDocumentQa() {
        when(ollama.chat(anyList(), any())).thenReturn("qa_tai_lieu");

        selector.select(null, "Cho mình xem lại trang cũ", learnerWithScreens());

        ArgumentCaptor<List<OllamaClient.Message>> sent = ArgumentCaptor.captor();
        verify(ollama).chat(sent.capture(), any());
        String prompt = sent.getValue().get(0).content();
        assertThat(prompt).contains("dieu_huong:").contains("Các màn hình của người dùng: Hồ sơ cá nhân, Học tập")
            .doesNotContain("Chi tiết khóa").contains("hoc_tap_cua_toi:").contains("KHÔNG dùng cho dữ liệu cá nhân");
    }

    @Test
    void aFirstQuestionIsSentToTheClassifierAsItIs() {
        when(ollama.chat(anyList(), any())).thenReturn("tro_chuyen");

        selector.select(null, "Xin chào", new AgentContext("000004", null, null), null);

        ArgumentCaptor<List<OllamaClient.Message>> sent = ArgumentCaptor.captor();
        verify(ollama).chat(sent.capture(), any());
        assertThat(sent.getValue().get(1).content()).isEqualTo("Xin chào");
    }
}
