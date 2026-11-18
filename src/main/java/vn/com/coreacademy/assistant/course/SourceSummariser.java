package vn.com.coreacademy.assistant.course;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.config.AssistantBudget;
import vn.com.coreacademy.assistant.config.AssistantModels;
import vn.com.coreacademy.assistant.config.GenerationProperties;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.GenerationException;
import vn.com.coreacademy.assistant.course.SourceTextRepository.Piece;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaClient.Message;
import vn.com.coreacademy.knowledge.chunking.TokenEstimator;

/**
 * Reads a course source document and summarises it section by section, and asks the model for one structured answer:
 * the parts every course generation flow shares (the old job and Soạn cùng trợ lý). The source text is data inside the
 * message, never instructions, and nothing of it is logged.
 */
@Component
@RequiredArgsConstructor
public class SourceSummariser {
    public static final String SUMMARISE = "summarise_sections";
    /** Tries per answer: the first ask and two retries (Q24, Q41). */
    public static final int ATTEMPTS = 3;
    public static final String UNREADABLE = "Không đọc được tài liệu nguồn. Hãy thử tệp khác.";
    public static final String EMPTY = "Tài liệu nguồn không có nội dung để tạo khóa học.";
    public static final String TOO_THIN =
        "Tài liệu quá ngắn để soạn một khóa học. Hãy gửi tài liệu có nhiều nội dung hơn.";
    /** Tokens kept free in a call for the step's own wording and the retry note. */
    private static final int CALL_MARGIN = 300;
    /** The outline call's own wording: the course data and the instructions around the summaries. */
    private static final int OUTLINE_OVERHEAD_TOKENS = 400;

    private final SourceTextRepository sources;
    private final OllamaClient ollama;
    private final SkillCatalog catalog;
    private final AssistantModels models;
    private final ObjectMapper json;
    private final AssistantBudget budget;
    private final GenerationProperties generation;

    /** The summaries ({@code sections}, plus {@code groups} for a long source) and a hash of their input. */
    public record Summary(ObjectNode output, String inputHash) {
    }

    /**
     * Summarises the document's text. A document still being read throws {@link StepNotReadyException}; one that could
     * not be read, has no text, or has fewer than {@code minChars} characters of text throws a
     * {@link GenerationException} whose message the author can read. {@code progress} hears (groups done, groups) after
     * every group but the last.
     */
    public Summary summarise(long documentId, int minChars, BiConsumer<Integer, Integer> progress) {
        var status = sources.status(documentId).orElseThrow(() -> new GenerationException("Tài liệu nguồn đã bị xóa."));
        switch (status.status()) {
            case "queued", "processing" -> throw new StepNotReadyException("Source is still being read");
            case "failed" -> throw new GenerationException(UNREADABLE);
            default -> { }
        }
        List<Piece> pieces = sources.pieces(documentId);
        if (pieces.isEmpty()) {
            throw new GenerationException(EMPTY);
        }
        if (pieces.stream().mapToInt(piece -> piece.content().strip().length()).sum() < minChars) {
            throw new GenerationException(TOO_THIN);
        }

        ArrayNode sections = json.createArrayNode();
        StringBuilder hash = new StringBuilder();
        List<List<Piece>> groups = group(pieces, sourceTokens());
        int number = 0;
        for (List<Piece> group : groups) {
            number++;
            String user = "NGUỒN:\n" + join(group);
            JsonNode summary = ask(SUMMARISE, user, GenerationSchemas::summary);
            ObjectNode section = ((ObjectNode) summary).deepCopy();
            section.put("key", "s" + number);
            section.put("firstPiece", group.get(0).index());
            section.put("lastPiece", group.get(group.size() - 1).index());
            sections.add(section);
            hash.append(user.length()).append(':');
            if (number < groups.size()) {
                progress.accept(number, groups.size());
            }
        }
        ObjectNode output = json.createObjectNode();
        output.set("sections", sections);
        ArrayNode condensed = condense(sections);
        if (condensed != null) {
            // The section summaries do not fit one later call: that call reads these group summaries instead.
            output.set("groups", condensed);
        }

        return new Summary(output, digest(hash.toString() + pieces.size()));
    }

    /**
     * Asks the model for JSON constrained to the step's schema (structured output), up to {@link #ATTEMPTS} times, each
     * retry naming the problems of the last answer; the checks of {@link GenerationSchemas} stay as the last word.
     */
    public JsonNode ask(String stepKind, String user, Function<JsonNode, List<String>> check) {
        String system = catalog.generationPrompt(stepKind);
        JsonNode format = GenerationSchemas.format(stepKind);
        List<String> problems = List.of();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            String message = attempt == 0 ? user : user + "\n\nKết quả trước không hợp lệ ("
                + String.join("; ", problems.stream().limit(5).toList()) + "). Hãy trả lại đúng một đối tượng JSON.";
            String reply = ollama.chat(List.of(Message.system(system), Message.user(message)),
                models.generationOptions(), format, generation.modelCallTimeout());
            JsonNode node = extract(reply);
            problems = node == null ? List.of("không phải JSON hợp lệ") : check.apply(node);
            if (problems.isEmpty()) {
                return node;
            }
        }
        throw new GenerationException("Mô hình trả về dữ liệu không hợp lệ ở bước này. Hãy thử lại.");
    }

    /** The first JSON object in the reply, or null; models wrap JSON in text or code fences. */
    JsonNode extract(String reply) {
        if (reply == null) {
            return null;
        }
        int start = reply.indexOf('{');
        int end = reply.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            JsonNode node = json.readTree(reply.substring(start, end + 1));
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException exception) {
            return null;
        }
    }

    /** Tokens of source one summarise call may carry, so that the call as a whole stays inside the context. */
    int sourceTokens() {
        int context = Math.min(budget.numCtx(), models.generationOptions().numCtx());

        return Math.max(1, context - budget.systemAndTools() - budget.answer() - CALL_MARGIN);
    }

    /** Sections are sized by the token budget; a long source gives more sections, never larger ones. */
    static List<List<Piece>> group(List<Piece> pieces, int sectionTokens) {
        List<List<Piece>> groups = new ArrayList<>();
        List<Piece> current = new ArrayList<>();
        int tokens = 0;
        for (Piece piece : pieces) {
            int cost = TokenEstimator.estimate(piece.labelled()) + 1;
            if (!current.isEmpty() && tokens + cost > sectionTokens) {
                groups.add(current);
                current = new ArrayList<>();
                tokens = 0;
            }
            current.add(piece);
            tokens += cost;
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }

        return groups;
    }

    private static String summaryLine(JsonNode summary) {
        return "- " + summary.path("title").asText() + ": " + summary.path("summary").asText() + "\n";
    }

    /**
     * Null when the section summaries fit one later call. Otherwise a second level: groups of section summaries are
     * summarised again, level after level until the summaries fit.
     */
    private ArrayNode condense(ArrayNode sections) {
        int room = Math.max(1, sourceTokens() - OUTLINE_OVERHEAD_TOKENS);
        if (tokens(sections) <= room) {
            return null;
        }
        ArrayNode level = sections;
        while (tokens(level) > room) {
            ArrayNode next = json.createArrayNode();
            List<JsonNode> batch = new ArrayList<>();
            int used = 0;
            for (JsonNode summary : level) {
                int cost = TokenEstimator.estimate(summaryLine(summary));
                if (batch.size() >= 2 && used + cost > sourceTokens()) {
                    next.add(summariseGroup(batch));
                    batch = new ArrayList<>();
                    used = 0;
                }
                batch.add(summary);
                used += cost;
            }
            if (!batch.isEmpty()) {
                next.add(batch.size() == 1 ? batch.get(0) : summariseGroup(batch));
            }
            if (next.size() >= level.size()) {
                throw new GenerationException("Tài liệu nguồn quá dài để tóm tắt. Hãy thử tệp ngắn hơn.");
            }
            level = next;
        }

        return level;
    }

    private JsonNode summariseGroup(List<JsonNode> summaries) {
        StringBuilder user = new StringBuilder("NGUỒN:\n");
        summaries.forEach(summary -> user.append(summaryLine(summary)));
        return ask(SUMMARISE, user.toString(), GenerationSchemas::summary);
    }

    private static int tokens(JsonNode summaries) {
        int total = 0;
        for (JsonNode summary : summaries) {
            total += TokenEstimator.estimate(summaryLine(summary));
        }

        return total;
    }

    static String join(List<Piece> pieces) {
        return String.join("\n\n", pieces.stream().map(Piece::labelled).toList());
    }

    static String digest(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
