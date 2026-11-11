package vn.com.coreacademy.assistant;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.config.AssistantModels;
import vn.com.coreacademy.assistant.skill.Skill;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.assistant.skill.Suggestions;
import vn.com.coreacademy.knowledge.ai.OllamaClient.Options;

import static org.assertj.core.api.Assertions.assertThat;

class SkillCatalogTest {
    @Test
    void theSkillsLoadWithTheirToolsAndOneStableVersionOfThePromptFiles() {
        SkillCatalog catalog = new SkillCatalog();

        assertThat(catalog.all()).extracting(Skill::id)
            .containsExactlyInAnyOrder("qa_tai_lieu", "dieu_huong", "ho_so", "tom_tat_bai", "tro_chuyen", "tao_khoa_hoc");
        assertThat(catalog.find("qa_tai_lieu").orElseThrow().tools())
            .containsExactly("search_documents", "search_courses", "search_community");
        assertThat(catalog.find("tro_chuyen").orElseThrow().tools()).isEmpty();
        assertThat(catalog.find("không_có")).isEmpty();
        assertThat(catalog.version()).hasSize(12).isEqualTo(new SkillCatalog().version());
        assertThat(catalog.systemPrompt(catalog.defaultSkill())).contains("mình").contains("search_documents")
            .contains(Suggestions.MARKER);
    }

    @Test
    void anExampleReplyNeverShowsAToolCallAsText() {
        SkillCatalog catalog = new SkillCatalog();
        Set<String> toolNames = Stream.concat(catalog.all().stream().flatMap(skill -> skill.tools().stream()),
            Stream.of("start_course_job", "open_course_form")).collect(java.util.stream.Collectors.toSet());

        for (Skill skill : catalog.all()) {
            skill.examples().lines().filter(line -> line.startsWith("Trợ lý:")).forEach(reply ->
                assertThat(toolNames).as(skill.id() + " example: " + reply).noneMatch(reply::contains));
        }
    }

    @Test
    void everySkillWithToolsSaysTheToolCallIsMandatory() {
        new SkillCatalog().all().stream().filter(skill -> !skill.tools().isEmpty()).forEach(skill ->
            assertThat(skill.output().toLowerCase()).as(skill.id()).contains("bắt buộc gọi công cụ"));
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
