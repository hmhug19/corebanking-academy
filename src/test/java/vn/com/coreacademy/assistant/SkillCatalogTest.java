package vn.com.coreacademy.assistant;

import java.util.List;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.config.AssistantModels;
import vn.com.coreacademy.assistant.skill.Skill;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.assistant.skill.Suggestions;
import vn.com.coreacademy.knowledge.ai.OllamaClient.Options;

import static org.assertj.core.api.Assertions.assertThat;

class SkillCatalogTest {
    @Test
    void theFourSkillsLoadWithTheirToolsAndOneStableVersionOfThePromptFiles() {
        SkillCatalog catalog = new SkillCatalog();

        assertThat(catalog.all()).extracting(Skill::id)
            .containsExactlyInAnyOrder("qa_tai_lieu", "dieu_huong", "ho_so", "tro_chuyen");
        assertThat(catalog.find("qa_tai_lieu").orElseThrow().tools())
            .containsExactly("search_documents", "search_community");
        assertThat(catalog.find("tro_chuyen").orElseThrow().tools()).isEmpty();
        assertThat(catalog.find("không_có")).isEmpty();
        assertThat(catalog.version()).hasSize(12).isEqualTo(new SkillCatalog().version());
        assertThat(catalog.systemPrompt(catalog.defaultSkill())).contains("mình").contains("search_documents")
            .contains(Suggestions.MARKER);
    }

    @Test
    void suggestionsKeepAtMostThreeShortLinesAndDropAnythingElse() {
        String text = "Trả lời.\n" + Suggestions.MARKER + "\n- Một\n* sai\n- " + "x".repeat(121) + "\n- Hai\n- Ba\n- Bốn";

        Suggestions.Split split = Suggestions.split(text);

        assertThat(split.answer()).isEqualTo("Trả lời.");
        assertThat(split.suggestions()).containsExactly("Một", "Hai", "Ba");
        assertThat(Suggestions.split("Không có khối.").suggestions()).isEmpty();
        assertThat(Suggestions.split(Suggestions.MARKER + "\nlung tung").suggestions()).isEmpty();
    }

    @Test
    void theStreamFilterHoldsBackOnlyWhatCouldBecomeTheMarker() {
        Suggestions.Filter filter = new Suggestions.Filter();

        StringBuilder shown = new StringBuilder();
        for (String piece : List.of("Giá @", "@ trị", " ok\n@@GO", "I_Y@@\n- a", "\n- b")) {
            shown.append(filter.accept(piece));
        }

        assertThat(shown.toString().strip()).isEqualTo("Giá @@ trị ok");
    }

    @Test
    void modelOptionsDefaultToTheBuiltInValuesAndCanBeOverriddenPerUse() {
        AssistantModels defaults = AssistantModels.defaults();
        assertThat(defaults.agentOptions()).isEqualTo(Options.AGENT);
        assertThat(defaults.groundedOptions()).isEqualTo(Options.GROUNDED);
        assertThat(defaults.classifierOptions().temperature()).isZero();

        AssistantModels custom = new AssistantModels(null, null,
            new AssistantModels.Use("qwen3:1.7b", 0.0, 1024), null);
        assertThat(custom.classifierOptions()).isEqualTo(new Options(0.0, 1024, "qwen3:1.7b"));
        assertThat(custom.agentOptions()).isEqualTo(Options.AGENT);
    }
}
