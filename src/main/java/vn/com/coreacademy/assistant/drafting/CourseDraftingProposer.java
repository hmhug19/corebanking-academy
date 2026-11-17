package vn.com.coreacademy.assistant.drafting;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.course.GenerationSchemas;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.GenerationException;
import vn.com.coreacademy.assistant.course.SourceSummariser;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.Candidate;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.DraftingStepRow;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.SessionRow;

/**
 * The model work of each drafting step. The source is summarised once per session and every step reads the summary.
 * What the model returns is checked against {@link GenerationSchemas}; ids it returns are kept only when they are in
 * the lists the author may pick from, and the course code is made here, never by the model. Source text and proposals
 * are never logged.
 */
@Component
@RequiredArgsConstructor
public class CourseDraftingProposer implements DraftingStepProposer {
    /** Fewer characters of text than this cannot make a course: the session fails instead of inventing one. */
    public static final int MIN_SOURCE_CHARS = 400;
    static final String NOT_SUPPORTED = "Bước này chưa được hỗ trợ.";
    static final String NO_PROGRAMME = "Chưa chọn được chương trình đào tạo phù hợp; hãy chọn trong danh sách.";
    static final String NO_MODULE = "Chưa chọn được phân hệ nghiệp vụ; hãy chọn trong danh sách nếu cần.";
    private static final int CANDIDATES = 100;
    private static final int CODE_TRIES = 20;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Map<String, String> LABELS = Map.of("title", "tên khóa học", "summary", "mô tả ngắn",
        "objectives", "mục tiêu đầu ra", "level", "mức độ", "defaultRequirement", "loại khóa học");

    private final SourceSummariser summariser;
    private final CourseDraftingRepository drafting;
    private final ObjectMapper json;

    @Override
    public Proposal propose(SessionRow session, DraftingStepRow step) {
        return switch (step.stepNo()) {
            case 1 -> basics(session);
            default -> throw new GenerationException(NOT_SUPPORTED);
        };
    }

    // ---- step 1 ----------------------------------------------------------------------------------------------------

    private Proposal basics(SessionRow session) {
        JsonNode made = null;
        JsonNode summary;
        if (session.sourceSummary() != null) {
            summary = parse(session.sourceSummary());
        } else {
            if (session.sourceDocumentId() == null) {
                throw new GenerationException("Phiên soạn không còn tài liệu nguồn.");
            }
            summary = summariser.summarise(session.sourceDocumentId(), MIN_SOURCE_CHARS, (done, groups) -> { })
                .output();
            made = summary;
        }
        JsonNode hints = parse(session.hints());
        List<Candidate> programmes = drafting.programmes(session.ownerOrgId(), CANDIDATES);
        List<Candidate> modules = drafting.modules(session.ownerOrgId(), CANDIDATES);

        JsonNode answer = summariser.ask(GenerationSchemas.DRAFT_BASICS, basicsMessage(summary, hints, programmes,
            modules), GenerationSchemas::draftBasics);

        return checkedBasics(answer, hints, programmes, modules, made);
    }

    private static String basicsMessage(JsonNode summary, JsonNode hints, List<Candidate> programmes,
                                        List<Candidate> modules) {
        StringBuilder user = new StringBuilder("DỮ LIỆU\n");
        appendHint(user, hints, "title", "Tên khóa học người dùng muốn");
        appendHint(user, hints, "targetLearners", "Đối tượng học viên");
        appendHint(user, hints, "level", "Mức độ");
        user.append("\nTóm tắt các phần của tài liệu nguồn:\n");
        JsonNode sections = summary.path("groups").isArray() && !summary.path("groups").isEmpty()
            ? summary.path("groups") : summary.path("sections");
        for (JsonNode section : sections) {
            user.append("- ").append(section.path("title").asText()).append(": ")
                .append(section.path("summary").asText()).append('\n');
        }
        user.append("\nCHƯƠNG TRÌNH (id: tên [lĩnh vực]):\n");
        programmes.forEach(item -> user.append("- ").append(item.id()).append(": ").append(item.name())
            .append(" [").append(item.fieldId()).append("]\n"));
        user.append("\nPHÂN HỆ (id: tên [lĩnh vực]):\n");
        modules.forEach(item -> user.append("- ").append(item.id()).append(": ").append(item.name())
            .append(" [").append(item.fieldId()).append("]\n"));

        return user.toString();
    }

    private static void appendHint(StringBuilder user, JsonNode hints, String field, String label) {
        String value = hints.path(field).asText("").strip();
        if (!value.isEmpty()) {
            user.append(label).append(": ").append(value).append('\n');
        }
    }

    /**
     * The proposal the author sees. What the author said wins over the model (title, level); a programme outside the
     * list, or a module outside the list or of another field than the programme, is dropped and named in the notes.
     */
    Proposal checkedBasics(JsonNode answer, JsonNode hints, List<Candidate> programmes, List<Candidate> modules,
                           JsonNode madeSummary) {
        Set<String> unsure = new LinkedHashSet<>();
        answer.path("unsure").forEach(field -> unsure.add(field.asText()));

        String hintTitle = hints.path("title").asText("").strip();
        String hintLevel = hints.path("level").asText("").strip();
        boolean levelGiven = GenerationSchemas.LEVELS.contains(hintLevel);
        if (!hintTitle.isEmpty()) {
            unsure.remove("title");
        }
        if (levelGiven) {
            unsure.remove("level");
        }

        Map<Long, Candidate> programmeById = byId(programmes);
        Map<Long, Candidate> moduleById = byId(modules);
        Candidate programme = id(answer, "programId").map(programmeById::get).orElse(null);
        Candidate module = id(answer, "moduleId").map(moduleById::get)
            .filter(found -> programme != null && found.fieldId() == programme.fieldId()).orElse(null);
        if (programme == null) {
            unsure.add("programId");
        }
        if (module == null) {
            unsure.add("moduleId");
        }

        ObjectNode proposal = json.createObjectNode();
        proposal.put("title", hintTitle.isEmpty() ? answer.path("title").asText().strip()
            : hintTitle.substring(0, Math.min(hintTitle.length(), 255)));
        proposal.put("summary", answer.path("summary").asText().strip());
        var objectives = proposal.putArray("objectives");
        answer.path("objectives").forEach(objective -> objectives.add(objective.asText().strip()));
        proposal.put("level", levelGiven ? hintLevel : answer.path("level").asText());
        proposal.put("defaultRequirement", answer.path("defaultRequirement").asText());
        if (programme == null) {
            proposal.putNull("programId");
        } else {
            proposal.put("programId", programme.id());
        }
        if (module == null) {
            proposal.putNull("moduleId");
        } else {
            proposal.put("moduleId", module.id());
        }
        proposal.put("code", freeCode());
        var unsureList = proposal.putArray("unsure");
        GenerationSchemas.BASICS_FIELDS.stream().filter(unsure::contains).forEach(unsureList::add);

        List<String> notes = new ArrayList<>();
        if (programme == null) {
            notes.add(NO_PROGRAMME);
        }
        if (module == null) {
            notes.add(NO_MODULE);
        }
        List<String> check = GenerationSchemas.BASICS_FIELDS.stream().filter(unsure::contains)
            .filter(LABELS::containsKey).map(LABELS::get).toList();
        if (!check.isEmpty()) {
            notes.add("Hãy kiểm tra lại " + String.join(", ", check) + ".");
        }

        return new Proposal(proposal, List.copyOf(notes), madeSummary);
    }

    /** A course code nobody uses now, in the old flow's shape ({@code AI-} and 8 characters). */
    String freeCode() {
        return freeCode(drafting);
    }

    /**
     * A course code nobody uses now. Static so the service can propose a new code after a taken one at confirm, even
     * where the proposer itself is replaced (tests).
     */
    static String freeCode(CourseDraftingRepository drafting) {
        for (int i = 0; i < CODE_TRIES; i++) {
            String code = newCode();
            if (!drafting.codeTaken(code)) {
                return code;
            }
        }
        throw new GenerationException("Không tạo được mã khóa học. Hãy thử lại.");
    }

    private static String newCode() {
        StringBuilder code = new StringBuilder("AI-");
        for (int i = 0; i < 8; i++) {
            code.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        }

        return code.toString();
    }

    private static Optional<Long> id(JsonNode answer, String field) {
        JsonNode value = answer.path(field);
        return value.isIntegralNumber() ? Optional.of(value.asLong()) : Optional.empty();
    }

    private static Map<Long, Candidate> byId(List<Candidate> candidates) {
        return candidates.stream().collect(Collectors.toMap(Candidate::id, Function.identity(), (a, b) -> a));
    }

    private JsonNode parse(String text) {
        if (text == null || text.isBlank()) {
            return json.createObjectNode();
        }
        try {
            return json.readTree(text);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
