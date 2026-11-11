package vn.com.coreacademy.assistant;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.agent.LessonFocus;
import vn.com.coreacademy.assistant.config.AssistantModels;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.assistant.skill.SkillSelector;
import vn.com.coreacademy.knowledge.ai.OllamaClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
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
}
