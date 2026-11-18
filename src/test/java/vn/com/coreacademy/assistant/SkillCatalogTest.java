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
            .containsExactlyInAnyOrder("qa_tai_lieu", "dieu_huong", "ho_so", "tom_tat_bai", "hoi_bai_hoc", "tro_chuyen", "tao_khoa_hoc",
                "hoc_tap_cua_toi");
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
            Stream.of("start_course_drafting")).collect(java.util.stream.Collectors.toSet());

        for (Skill skill : catalog.all()) {
            skill.examples().lines().filter(line -> line.startsWith("Trợ lý:")).forEach(reply ->
                assertThat(toolNames).as(skill.id() + " example: " + reply).noneMatch(reply::contains));
        }
    }

    @Test
    void theNavigationSkillsNameTheToolTheyMustCall() {
        SkillCatalog catalog = new SkillCatalog();

        assertThat(catalog.find("dieu_huong").orElseThrow().requiredTools()).containsExactly("open_screen");
        assertThat(catalog.find("tao_khoa_hoc").orElseThrow().requiredTools()).containsExactly("start_course_drafting");
        assertThat(catalog.find("hoc_tap_cua_toi").orElseThrow().requiredTools()).containsExactly("my_learning");
        assertThat(catalog.find("tro_chuyen").orElseThrow().requiredTools()).isEmpty();
        catalog.all().forEach(skill -> assertThat(skill.tools()).as(skill.id()).containsAll(skill.requiredTools()));
    }

    @Test
    void theDocumentSkillMustCallOneOfItsSearchToolsAndTheProfileSkillOneOfItsOwn() {
        SkillCatalog catalog = new SkillCatalog();

        assertThat(catalog.find("qa_tai_lieu").orElseThrow().requiredTools())
            .containsExactly("search_documents", "search_courses", "search_community");
        assertThat(catalog.find("ho_so").orElseThrow().tools()).contains("read_profile", "update_profile", "open_screen");
        assertThat(catalog.find("ho_so").orElseThrow().requiredTools()).contains("read_profile");
    }

    @Test
    void theNewLearningSkillIsOnlyForTheUsersOwnLearningDataAndTheDocumentSkillSaysItIsNot() {
        SkillCatalog catalog = new SkillCatalog();
        Skill mine = catalog.find("hoc_tap_cua_toi").orElseThrow();

        assertThat(mine.tools()).containsExactly("my_learning");
        assertThat(mine.roles()).isEmpty();
        assertThat(mine.description()).contains("quá hạn").contains("tiến độ").contains("của tôi");
        assertThat(catalog.defaultSkill().description()).contains("KHÔNG dùng cho dữ liệu cá nhân");
        assertThat(catalog.style()).contains("Không tự viết URL").contains("[...]");
    }

    @Test
    void noExampleReplyClaimsAnActionTheAppDoes() {
        java.util.regex.Pattern claim = java.util.regex.Pattern.compile(
            "(?iu)\\b(mình |tôi )?(đã|vừa) (mở|điền|chuyển|tạo|chuẩn bị|đổi|cập nhật)");

        for (Skill skill : new SkillCatalog().all()) {
            skill.examples().lines().filter(line -> line.startsWith("Trợ lý:")).forEach(reply ->
                assertThat(claim.matcher(reply).find()).as(skill.id() + " example: " + reply).isFalse());
        }
    }

    @Test
    void noChatPromptShowsTheLiteralPlaceholderTheModelWouldCopy() {
        SkillCatalog catalog = new SkillCatalog();

        catalog.all().forEach(skill -> {
            assertThat(skill.output()).as(skill.id()).doesNotContain("[n]");
            assertThat(catalog.systemPrompt(skill)).as(skill.id()).doesNotContain("[n]");
        });
        assertThat(catalog.explainPrompt()).doesNotContain("[n]");
    }

    @Test
    void everySkillWithToolsSaysTheToolCallIsMandatory() {
        new SkillCatalog().all().stream().filter(skill -> !skill.tools().isEmpty()).forEach(skill ->
            assertThat(skill.output().toLowerCase()).as(skill.id()).contains("bắt buộc gọi công cụ"));
    }

    @Test
    void generationPromptsCarryNeitherTheChatStyleNorTheSuggestionAndCitationRules() {
        SkillCatalog catalog = new SkillCatalog();

        for (String step : List.of("summarise_sections", "outline", "lesson", "questions")) {
            assertThat(catalog.generationPrompt(step)).as(step).doesNotContain(Suggestions.MARKER)
                .doesNotContain("[n]").doesNotContain("[1]").doesNotContain("200 từ").doesNotContain("Xưng \"mình\"")
                .contains("DUY NHẤT một đối tượng JSON").endsWith("Viết bằng tiếng Việt.");
        }
    }

    @Test
    void theExplainPromptHasNoSuggestionMarkerRule() {
        assertThat(new SkillCatalog().explainPrompt()).doesNotContain(Suggestions.MARKER).doesNotContain("Gợi ý câu hỏi")
            .contains("mình");
    }

    @Test
    void chatSkillPromptsKeepTheStyleTheSuggestionRuleAndTheirOwnCitationRule() {
        SkillCatalog catalog = new SkillCatalog();

        assertThat(catalog.systemPrompt(catalog.find("qa_tai_lieu").orElseThrow())).contains("mình")
            .contains(Suggestions.MARKER).contains("số đoạn nguồn").contains("200 từ");
        assertThat(catalog.systemPrompt(catalog.find("dieu_huong").orElseThrow())).contains(Suggestions.MARKER)
            .doesNotContain("[n]");
    }

    @Test
    void noSharedPromptFileNamesATool() {
        SkillCatalog catalog = new SkillCatalog();
        Set<String> toolNames = Stream.concat(catalog.all().stream().flatMap(skill -> skill.tools().stream()),
            Stream.of("start_course_drafting")).collect(java.util.stream.Collectors.toSet());

        for (String shared : List.of(catalog.style(), catalog.suggestions(), catalog.explainPrompt())) {
            assertThat(toolNames).noneMatch(shared::contains);
        }
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
        // Same context size as the agent, so Ollama keeps the model loaded between the two calls.
        assertThat(defaults.classifierOptions().numCtx()).isEqualTo(defaults.agentOptions().numCtx());
        AssistantModels wide = new AssistantModels(new AssistantModels.Use(null, null, 4096), null, null, null);
        assertThat(wide.classifierOptions().numCtx()).isEqualTo(4096);

        AssistantModels custom = new AssistantModels(null, null,
            new AssistantModels.Use("qwen3:1.7b", 0.0, 1024), null);
        assertThat(custom.classifierOptions()).isEqualTo(new Options(0.0, 1024, "qwen3:1.7b"));
        assertThat(custom.agentOptions()).isEqualTo(Options.AGENT);
    }
}
