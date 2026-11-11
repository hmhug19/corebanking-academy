package vn.com.coreacademy.assistant;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicLong;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.config.AssistantModels;
import vn.com.coreacademy.assistant.skill.Skill;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.assistant.skill.SkillSelector;
import vn.com.coreacademy.assistant.tool.ToolDefinitions;
import vn.com.coreacademy.assistant.tool.ToolDefinitions.Screen;
import vn.com.coreacademy.knowledge.ai.ChatMessage;
import vn.com.coreacademy.knowledge.ai.ChatTurn;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.ToolCall;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures how reliably the real local model picks the right skill and then the right tool for fixed Vietnamese
 * prompts (the skill comes from the classifier, the tools offered are the skill's). It needs Ollama with
 * {@code qwen3:8b} running, so its name keeps it out of the default run (surefire only includes {@code *Test} and
 * {@code *IT}); run it with {@code mvn test -Dtest=AgentToolChoiceEval}. Tool execution is not involved: only the
 * first model call of a turn is made, with the real tool definitions. The report is printed and written to
 * {@code target/agent-tool-choice-eval.txt}.
 */
class AgentToolChoiceEval {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final double REQUIRED_HIT_RATE = 0.9;
    private static final String NONE = "(none)";

    private static final List<Screen> SCREENS = List.of(
        new Screen("PROFILE", "Hồ sơ cá nhân"), new Screen("COURSE_LEARNING", "Học tập"),
        new Screen("LEARNING_PROGRESS", "Tiến độ học tập"), new Screen("QUIZ_TAKING", "Làm bài kiểm tra"),
        new Screen("COURSE_MANAGEMENT", "Quản lý khóa học"), new Screen("LESSON_MANAGEMENT", "Quản lý bài giảng"),
        new Screen("QUESTION_BANK", "Ngân hàng câu hỏi"), new Screen("QUIZ_MANAGEMENT", "Quản lý bài thi"),
        new Screen("USER_MANAGEMENT", "Quản lý người dùng"),
        new Screen("ORGANIZATION_MANAGEMENT", "Quản lý đơn vị và nhóm"),
        new Screen("ROLE_MANAGEMENT", "Quản lý vai trò và phân quyền"),
        new Screen("COURSE_ASSIGNMENT", "Giao khóa học"), new Screen("REPORTING", "Báo cáo đào tạo"),
        new Screen("SYSTEM_AUDIT", "Nhật ký hệ thống"), new Screen("KNOWLEDGE_HUB", "Kho tri thức"),
        new Screen("AI_ASSISTANT", "Trợ lý AI"), new Screen("ISSUE_HUB", "Tra cứu sự cố"));

    /** {@code argument} is the argument value to check when the tool is right ({@code null}: no check). */
    private record Prompt(String text, String expectedTool, String argumentName, String expectedArgument) {
        static Prompt tool(String text, String tool) {
            return new Prompt(text, tool, null, null);
        }

        static Prompt tool(String text, String tool, String argumentName, String expectedArgument) {
            return new Prompt(text, tool, argumentName, expectedArgument);
        }

        /** The skill whose tools include the expected one. */
        String expectedSkill() {
            return switch (expectedTool) {
                case ToolDefinitions.SEARCH_DOCUMENTS, ToolDefinitions.SEARCH_COMMUNITY -> "qa_tai_lieu";
                case ToolDefinitions.OPEN_SCREEN -> "dieu_huong";
                case ToolDefinitions.UPDATE_PROFILE -> "ho_so";
                default -> "tro_chuyen";
            };
        }

        static Prompt direct(String text) {
            return new Prompt(text, NONE, null, null);
        }
    }

    private record Call(String chosen, String arguments, OptionalLong firstDeltaMs, long totalMs) {
    }

    private record Result(Prompt prompt, String skill, String chosen, String arguments, boolean argumentOk, OptionalLong firstDeltaMs,
                          long totalMs) {
        boolean hit() {
            return prompt.expectedTool().equals(chosen);
        }

        boolean skillHit() {
            return prompt.expectedSkill().equals(skill);
        }
    }

    private static final List<Prompt> PROMPTS = List.of(
        Prompt.tool("CIF là gì?", ToolDefinitions.SEARCH_DOCUMENTS),
        Prompt.tool("Quy trình EOD gồm những bước nào?", ToolDefinitions.SEARCH_DOCUMENTS),
        Prompt.tool("ECR là gì và khi nào phải tạo?", ToolDefinitions.SEARCH_DOCUMENTS),
        Prompt.tool("Checklist vận hành cuối ngày gồm những mục gì?", ToolDefinitions.SEARCH_DOCUMENTS),
        Prompt.tool("Cuộc thi phát triển ứng dụng của Ban được tổ chức khi nào?", ToolDefinitions.SEARCH_DOCUMENTS),
        Prompt.tool("Giải thích giúp mình thuật ngữ GL trong core banking", ToolDefinitions.SEARCH_DOCUMENTS),
        Prompt.tool("Khi chạy batch cuối ngày bị lỗi thì xử lý thế nào?", ToolDefinitions.SEARCH_DOCUMENTS),
        Prompt.tool("Tài liệu nào nói về kiểm soát hạn mức giao dịch?", ToolDefinitions.SEARCH_DOCUMENTS),

        Prompt.tool("Tìm trong cộng đồng bài viết về đối soát cuối ngày", ToolDefinitions.SEARCH_COMMUNITY),
        Prompt.tool("Có thảo luận nào trong cộng đồng về lỗi batch EOD không?", ToolDefinitions.SEARCH_COMMUNITY),
        Prompt.tool("Đồng nghiệp có chia sẻ kinh nghiệm xử lý ECR trên cộng đồng không?",
            ToolDefinitions.SEARCH_COMMUNITY),
        Prompt.tool("Tìm giúp mình các bài Wiki cộng đồng về hạch toán GL", ToolDefinitions.SEARCH_COMMUNITY),
        Prompt.tool("Cho mình xem câu hỏi của mọi người trong cộng đồng về CIF", ToolDefinitions.SEARCH_COMMUNITY),
        Prompt.tool("Có bài viết cộng đồng nào hướng dẫn mở tài khoản không?", ToolDefinitions.SEARCH_COMMUNITY),

        Prompt.tool("mở trang quản lý người dùng", ToolDefinitions.OPEN_SCREEN, "functionCode", "USER_MANAGEMENT"),
        Prompt.tool("cho mình xem tiến độ học tập", ToolDefinitions.OPEN_SCREEN, "functionCode", "LEARNING_PROGRESS"),
        Prompt.tool("đưa mình tới màn hình báo cáo đào tạo", ToolDefinitions.OPEN_SCREEN, "functionCode",
            "REPORTING"),
        Prompt.tool("vào kho tri thức giúp mình", ToolDefinitions.OPEN_SCREEN, "functionCode", "KNOWLEDGE_HUB"),
        Prompt.tool("mở nhật ký hệ thống", ToolDefinitions.OPEN_SCREEN, "functionCode", "SYSTEM_AUDIT"),

        Prompt.tool("đổi email của tôi thành minh.nguyen@example.com", ToolDefinitions.UPDATE_PROFILE, "email",
            "minh.nguyen@example.com"),
        Prompt.tool("Đổi chức danh của tôi thành Chuyên viên", ToolDefinitions.UPDATE_PROFILE, "positionName",
            "Chuyên viên"),
        Prompt.tool("sửa họ tên của mình thành Nguyễn Văn Minh", ToolDefinitions.UPDATE_PROFILE, "fullName",
            "Nguyễn Văn Minh"),
        Prompt.tool("cập nhật giúp mình chức danh là Chuyên viên chính", ToolDefinitions.UPDATE_PROFILE,
            "positionName", "Chuyên viên chính"),

        Prompt.direct("Xin chào"),
        Prompt.direct("Bạn làm được những gì?"),
        Prompt.direct("Cảm ơn nhé"));

    @Test
    void theModelPicksTheRightToolForAtLeastNinetyPercentOfThePrompts() throws Exception {
        OllamaClient ollama = ollama();
        assertThat(ollama.isAvailable()).as("Ollama must be running on localhost:11434").isTrue();
        List<ToolDefinition> tools = List.of(ToolDefinitions.searchDocuments(), ToolDefinitions.searchCommunity(),
            ToolDefinitions.openScreen(SCREENS), ToolDefinitions.updateProfile());

        SkillCatalog catalog = new SkillCatalog();
        SkillSelector selector = new SkillSelector(ollama, catalog, AssistantModels.defaults());
        long warmUp = call(ollama, catalog, catalog.defaultSkill(), tools, "Xin chào").totalMs();

        List<Result> results = new ArrayList<>();
        for (Prompt prompt : PROMPTS) {
            results.add(run(ollama, catalog, selector, tools, prompt));
        }

        String report = report(results, warmUp);
        System.out.println(report);
        Files.writeString(Paths.get("target", "agent-tool-choice-eval.txt"), report, StandardCharsets.UTF_8);
        long hits = results.stream().filter(Result::hit).count();
        assertThat((double) hits / results.size()).as("tool choice hit rate").isGreaterThanOrEqualTo(REQUIRED_HIT_RATE);
        long skillHits = results.stream().filter(Result::skillHit).count();
        assertThat((double) skillHits / results.size()).as("skill choice hit rate")
            .isGreaterThanOrEqualTo(REQUIRED_HIT_RATE);
    }

    private static Result run(OllamaClient ollama, SkillCatalog catalog, SkillSelector selector,
                              List<ToolDefinition> tools, Prompt prompt) {
        Skill skill = selector.select(null, prompt.text(), new AgentContext("000004", null, null));
        Call call = call(ollama, catalog, skill, tools, prompt.text());
        boolean argumentOk = prompt.argumentName() == null
            || argumentOf(call, prompt.argumentName()).equals(prompt.expectedArgument());
        return new Result(prompt, skill.id(), call.chosen(), call.arguments(), argumentOk, call.firstDeltaMs(), call.totalMs());
    }

    private static Call call(OllamaClient ollama, SkillCatalog catalog, Skill skill, List<ToolDefinition> allTools,
                             String text) {
        List<ToolDefinition> tools = allTools.stream().filter(tool -> skill.tools().contains(tool.name())).toList();
        long started = System.nanoTime();
        AtomicLong firstDelta = new AtomicLong(-1);
        ChatTurn turn = ollama.chatWithTools(
            List.of(ChatMessage.system(catalog.systemPrompt(skill)), ChatMessage.user(text)), tools,
            OllamaClient.Options.AGENT, delta -> firstDelta.compareAndSet(-1, millisSince(started)));
        long total = millisSince(started);
        ToolCall first = turn.toolCalls().isEmpty() ? null : turn.toolCalls().get(0);
        OptionalLong firstDeltaMs = firstDelta.get() < 0 ? OptionalLong.empty() : OptionalLong.of(firstDelta.get());
        return new Call(first == null ? NONE : first.name(), first == null ? "" : first.arguments().toString(),
            firstDeltaMs, total);
    }

    private static String argumentOf(Call call, String name) {
        try {
            JsonNode arguments = JSON.readTree(call.arguments());
            return arguments.path(name).asText("").strip();
        } catch (JsonProcessingException exception) {
            return "";
        }
    }

    private static String report(List<Result> results, long warmUpMs) {
        StringBuilder out = new StringBuilder("\nAgentToolChoiceEval (qwen3:8b, think=false, temperature 0.1)\n");
        out.append(String.format("model warm-up call: %d ms (excluded from the figures below)%n%n", warmUpMs));
        for (Result result : results) {
            out.append(String.format("%-4s %-62s skill=%-12s expected=%-16s chosen=%-16s args=%s %s | first delta=%s total=%d ms%n",
                result.hit() ? "OK" : "MISS", result.prompt().text(), result.skill(), result.prompt().expectedTool(),
                result.chosen(), result.argumentOk() ? "ok" : "WRONG", result.arguments(),
                result.firstDeltaMs().isPresent() ? result.firstDeltaMs().getAsLong() + " ms" : "-", result.totalMs()));
        }
        long hits = results.stream().filter(Result::hit).count();
        long argumentHits = results.stream().filter(result -> result.hit() && result.argumentOk()).count();
        List<Long> decisions = results.stream().map(Result::totalMs).sorted().toList();
        List<Long> firstTokens = results.stream().filter(result -> result.firstDeltaMs().isPresent())
            .map(result -> result.firstDeltaMs().getAsLong()).sorted().toList();
        out.append(String.format("%nskill choice: %d/%d%n", results.stream().filter(Result::skillHit).count(),
            results.size()));
        out.append(String.format("tool choice: %d/%d = %.0f%%  (arguments right where the tool was right: %d/%d)%n",
            hits, results.size(), 100.0 * hits / results.size(), argumentHits, hits));
        out.append(String.format("time to the tool decision (first model call): median %d ms, max %d ms%n",
            median(decisions), decisions.get(decisions.size() - 1)));
        out.append(firstTokens.isEmpty() ? "time to first streamed text: no direct answers\n"
            : String.format("time to first streamed text (direct answers): median %d ms%n", median(firstTokens)));
        return out.toString();
    }

    private static long median(List<Long> sorted) {
        return sorted.get(sorted.size() / 2);
    }

    private static long millisSince(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }

    private static OllamaClient ollama() {
        KnowledgeProperties.Ollama ollama = new KnowledgeProperties.Ollama("http://localhost:11434", "qwen3:8b",
            "bge-m3", Duration.ofSeconds(5), Duration.ofSeconds(180), Duration.ofMinutes(10));
        return new OllamaClient(new KnowledgeProperties(Path.of("unused"), DataSize.ofMegabytes(20),
            new KnowledgeProperties.Worker(Duration.ofSeconds(2), 1, 3),
            new KnowledgeProperties.Chunk(600, 800, 80), new KnowledgeProperties.Retrieval(5, 0.35), ollama));
    }
}
