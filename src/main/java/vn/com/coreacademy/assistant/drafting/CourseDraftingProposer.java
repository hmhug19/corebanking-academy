package vn.com.coreacademy.assistant.drafting;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.Choice;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.CourseBrief;
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
    static final String NO_DRAFT = "Phiên soạn chưa có bản nháp khóa học.";
    static final String NO_AUDIENCE = "Chưa chọn được đối tượng giao học phù hợp; hãy chọn ở bước Đối tượng & xuất "
        + "bản nếu cần.";
    static final String NOTHING_ASSIGNED = "Trợ lý không giao, không chia sẻ và không xuất bản khóa học; bạn tự làm "
        + "khi đã sẵn sàng.";
    static final String PUBLISHABLE = "Kiểm tra trước khi xuất bản: không còn mục bắt buộc nào chưa đạt.";
    static final String NO_COMPETENCY = "Chưa chọn được năng lực cho câu hỏi; hãy chọn trong danh sách trước khi chốt.";
    private static final int CANDIDATES = 100;
    private static final int CODE_TRIES = 20;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final Map<String, String> LABELS = Map.of("title", "tên khóa học", "summary", "mô tả ngắn",
        "objectives", "mục tiêu đầu ra", "level", "mức độ", "defaultRequirement", "loại khóa học");

    private final SourceSummariser summariser;
    private final CourseDraftingRepository drafting;
    private final ObjectMapper json;
    private final DraftingTargets targets;
    private final DraftingPublishCheck publishCheck;

    @Override
    public Proposal propose(SessionRow session, DraftingStepRow step) {
        return switch (step.stepNo()) {
            case 1 -> basics(session);
            case 2 -> outline(session);
            case 3 -> rules(session);
            case 4 -> audience(session);
            default -> throw new GenerationException(NOT_SUPPORTED);
        };
    }

    // ---- step 1 ----------------------------------------------------------------------------------------------------

    /** The session's source summary, made (and returned for storing) only when the session has none yet. */
    private record Source(JsonNode summary, JsonNode made) {
    }

    private Source source(SessionRow session) {
        if (session.sourceSummary() != null) {
            return new Source(parse(session.sourceSummary()), null);
        }
        if (session.sourceDocumentId() == null) {
            throw new GenerationException("Phiên soạn không còn tài liệu nguồn.");
        }
        JsonNode made = summariser.summarise(session.sourceDocumentId(), MIN_SOURCE_CHARS, (done, groups) -> { })
            .output();

        return new Source(made, made);
    }

    private Proposal basics(SessionRow session) {
        Source source = source(session);
        JsonNode summary = source.summary();
        JsonNode made = source.made();
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

    // ---- step 2 ----------------------------------------------------------------------------------------------------

    /**
     * Chapters and lesson titles with one line per lesson, from the source summary and the course as step 1 saved it.
     * The model names each lesson's source section by its key; the proposal carries the section's title instead, and
     * an answer naming a section the summary does not have is asked again. Lessons get no content here, ever.
     */
    private Proposal outline(SessionRow session) {
        Source source = source(session);
        Map<String, String> sections = sectionTitles(source.summary());
        if (sections.isEmpty()) {
            throw new GenerationException(SourceSummariser.EMPTY);
        }
        JsonNode hints = parse(session.hints());
        Optional<CourseBrief> course = session.revisionId() == null ? Optional.empty()
            : drafting.courseBrief(session.revisionId());

        JsonNode answer = summariser.ask(GenerationSchemas.DRAFT_OUTLINE, outlineMessage(source.summary(), sections,
            hints, course), node -> {
                List<String> problems = new ArrayList<>(GenerationSchemas.draftOutline(node));
                if (problems.isEmpty()) {
                    unknownSections(node, sections.keySet()).forEach(key ->
                        problems.add("lesson section " + key + " is not one of " + sections.keySet()));
                }
                return problems;
            });

        return checkedOutline(answer, sections, source.made());
    }

    /**
     * The sections a lesson may name, key to title: the summary's top level that later calls read (its groups for a
     * long source, else its sections), keyed {@code s1}, {@code s2}... by position.
     */
    private static Map<String, String> sectionTitles(JsonNode summary) {
        Map<String, String> titles = new LinkedHashMap<>();
        int number = 0;
        for (JsonNode section : topSections(summary)) {
            titles.put("s" + ++number, section.path("title").asText().strip());
        }

        return titles;
    }

    private static JsonNode topSections(JsonNode summary) {
        return summary.path("groups").isArray() && !summary.path("groups").isEmpty()
            ? summary.path("groups") : summary.path("sections");
    }

    private static List<String> unknownSections(JsonNode answer, Set<String> keys) {
        List<String> unknown = new ArrayList<>();
        answer.path("chapters").forEach(chapter -> chapter.path("lessons").forEach(lesson -> {
            String key = lesson.path("section").asText().strip();
            if (!keys.contains(key)) {
                unknown.add(key);
            }
        }));

        return unknown;
    }

    private static String outlineMessage(JsonNode summary, Map<String, String> sections, JsonNode hints,
                                         Optional<CourseBrief> course) {
        StringBuilder user = new StringBuilder("DỮ LIỆU\n");
        course.ifPresent(brief -> {
            user.append("Tên khóa học: ").append(brief.title()).append('\n');
            if (brief.summary() != null && !brief.summary().isBlank()) {
                user.append("Mô tả ngắn: ").append(brief.summary().strip()).append('\n');
            }
            if (brief.level() != null) {
                user.append("Mức độ: ").append(brief.level()).append('\n');
            }
            if (!brief.objectives().isEmpty()) {
                user.append("Mục tiêu đầu ra:\n");
                brief.objectives().forEach(objective -> user.append("- ").append(objective).append('\n'));
            }
        });
        appendHint(user, hints, "targetLearners", "Đối tượng học viên");
        user.append("\nCÁC PHẦN CỦA TÀI LIỆU NGUỒN (khóa: tên — tóm tắt):\n");
        int number = 0;
        for (JsonNode section : topSections(summary)) {
            String key = "s" + ++number;
            user.append("- ").append(key).append(": ").append(sections.get(key)).append(" — ")
                .append(section.path("summary").asText().strip()).append('\n');
        }

        return user.toString();
    }

    /** The proposal the author sees: the model's outline with each section key replaced by the section's title. */
    Proposal checkedOutline(JsonNode answer, Map<String, String> sections, JsonNode madeSummary) {
        ObjectNode proposal = json.createObjectNode();
        var chapters = proposal.putArray("chapters");
        int lessons = 0;
        for (JsonNode chapter : answer.path("chapters")) {
            ObjectNode outChapter = chapters.addObject();
            outChapter.put("title", chapter.path("title").asText().strip());
            var outLessons = outChapter.putArray("lessons");
            for (JsonNode lesson : chapter.path("lessons")) {
                lessons++;
                outLessons.addObject()
                    .put("title", lesson.path("title").asText().strip())
                    .put("description", lesson.path("description").asText().strip())
                    .put("sourceSection", sections.get(lesson.path("section").asText().strip()));
            }
        }
        String note = "Đã đề xuất " + chapters.size() + " chương, " + lessons + " bài; nội dung từng bài để trống.";

        return new Proposal(proposal, List.of(note), madeSummary);
    }

    // ---- step 3 ----------------------------------------------------------------------------------------------------

    /**
     * Bonus points, a competency from the list and about ten draft questions for the final exam, from the source
     * summary and the course as saved. Each question names its source section by key, replaced by the section's title
     * as in the outline; a competency outside the list, or none, is left empty for the author. Nothing reaches the
     * question bank here: the questions stay in the proposal until the author confirms the step.
     */
    private Proposal rules(SessionRow session) {
        Source source = source(session);
        Map<String, String> sections = sectionTitles(source.summary());
        if (sections.isEmpty()) {
            throw new GenerationException(SourceSummariser.EMPTY);
        }
        Optional<CourseBrief> course = session.revisionId() == null ? Optional.empty()
            : drafting.courseBrief(session.revisionId());
        List<Choice> competencies = drafting.competencies(CANDIDATES);

        JsonNode answer = summariser.ask(GenerationSchemas.DRAFT_RULES, rulesMessage(source.summary(), sections,
            course, competencies), node -> {
                List<String> problems = new ArrayList<>(GenerationSchemas.draftRules(node));
                if (problems.isEmpty()) {
                    node.path("questions").forEach(question -> {
                        String key = question.path("section").asText().strip();
                        if (!sections.containsKey(key)) {
                            problems.add("question section " + key + " is not one of " + sections.keySet());
                        }
                    });
                }
                return problems;
            });

        return checkedRules(answer, sections, competencies, source.made());
    }

    private static String rulesMessage(JsonNode summary, Map<String, String> sections, Optional<CourseBrief> course,
                                       List<Choice> competencies) {
        StringBuilder user = new StringBuilder("DỮ LIỆU\n");
        course.ifPresent(brief -> {
            user.append("Tên khóa học: ").append(brief.title()).append('\n');
            if (brief.level() != null) {
                user.append("Mức độ: ").append(brief.level()).append('\n');
            }
            if (!brief.objectives().isEmpty()) {
                user.append("Mục tiêu đầu ra:\n");
                brief.objectives().forEach(objective -> user.append("- ").append(objective).append('\n'));
            }
        });
        user.append("\nCÁC PHẦN CỦA TÀI LIỆU NGUỒN (khóa: tên — tóm tắt):\n");
        int number = 0;
        for (JsonNode section : topSections(summary)) {
            String key = "s" + ++number;
            user.append("- ").append(key).append(": ").append(sections.get(key)).append(" — ")
                .append(section.path("summary").asText().strip());
            section.path("keyPoints").forEach(point -> user.append("; ").append(point.asText().strip()));
            user.append('\n');
        }
        user.append("\nNĂNG LỰC (id: tên):\n");
        competencies.forEach(item -> user.append("- ").append(item.id()).append(": ").append(item.name())
            .append('\n'));

        return user.toString();
    }

    /**
     * The proposal the author sees: points, the competency when it is in the list (else null and a note), and the
     * questions with each section key replaced by the section's title.
     */
    Proposal checkedRules(JsonNode answer, Map<String, String> sections, List<Choice> competencies,
                          JsonNode madeSummary) {
        Set<Long> known = competencies.stream().map(Choice::id).collect(Collectors.toSet());
        Long competency = id(answer, "competencyId").filter(known::contains).orElse(null);

        ObjectNode proposal = json.createObjectNode();
        int points = answer.path("rewardPoints").asInt();
        proposal.put("rewardPoints", points);
        if (competency == null) {
            proposal.putNull("competencyId");
        } else {
            proposal.put("competencyId", competency);
        }
        var questions = proposal.putArray("questions");
        for (JsonNode question : answer.path("questions")) {
            ObjectNode out = questions.addObject();
            out.put("type", question.path("type").asText());
            out.put("content", question.path("content").asText().strip());
            String explanation = question.path("explanation").asText("").strip();
            if (explanation.isEmpty()) {
                out.putNull("explanation");
            } else {
                out.put("explanation", explanation);
            }
            out.put("difficulty", question.path("difficulty").asText());
            var options = out.putArray("options");
            question.path("options").forEach(option -> options.addObject()
                .put("content", option.path("content").asText().strip())
                .put("correct", option.path("correct").asBoolean()));
            out.put("sourceSection", sections.get(question.path("section").asText().strip()));
        }

        List<String> notes = new ArrayList<>();
        notes.add("Đã đề xuất " + questions.size() + " câu hỏi nháp và " + points + " điểm thưởng; câu hỏi chỉ vào "
            + "ngân hàng khi bạn chốt bước này.");
        if (competency == null) {
            notes.add(NO_COMPETENCY);
        }

        return new Proposal(proposal, List.copyOf(notes), madeSummary);
    }

    // ---- step 4 ----------------------------------------------------------------------------------------------------

    /**
     * Who the course is assigned to and what still blocks publishing. The model picks targets by key from the units
     * and teams the author may assign to now ({@link DraftingTargets}), reading the author's hint and the source; a key
     * outside that list is dropped, never asked again. The blockers are the page's own pre-publish check on the saved
     * draft ({@link DraftingPublishCheck}). Nothing is assigned, shared or published here or when the step is confirmed.
     */
    private Proposal audience(SessionRow session) {
        if (session.revisionId() == null) {
            throw new GenerationException(NO_DRAFT);
        }
        List<String> blockers = publishCheck.blockers(session.revisionId());
        List<DraftingTargets.Target> assignable = targets.assignable(session.ownerUserId(), session.ownerOrgId(),
            CANDIDATES);
        JsonNode answer = json.createArrayNode();
        JsonNode made = null;
        if (!assignable.isEmpty()) {
            Source source = source(session);
            made = source.made();
            Optional<CourseBrief> course = drafting.courseBrief(session.revisionId());
            answer = summariser.ask(GenerationSchemas.DRAFT_AUDIENCE, audienceMessage(source.summary(),
                parse(session.hints()), course, assignable), GenerationSchemas::draftAudience).path("targets");
        }

        Proposal checked = checkedAudience(answer, assignable, blockers);
        return new Proposal(checked.proposal(), checked.notes(), made);
    }

    private static String targetKey(DraftingTargets.Target target) {
        return ("org".equals(target.targetType()) ? "o" : "t") + target.id();
    }

    private static String audienceMessage(JsonNode summary, JsonNode hints, Optional<CourseBrief> course,
                                          List<DraftingTargets.Target> assignable) {
        StringBuilder user = new StringBuilder("DỮ LIỆU\n");
        course.ifPresent(brief -> {
            user.append("Tên khóa học: ").append(brief.title()).append('\n');
            if (brief.summary() != null && !brief.summary().isBlank()) {
                user.append("Mô tả ngắn: ").append(brief.summary().strip()).append('\n');
            }
            if (brief.level() != null) {
                user.append("Mức độ: ").append(brief.level()).append('\n');
            }
        });
        appendHint(user, hints, "targetLearners", "Đối tượng học viên người dùng nêu");
        user.append("\nCÁC PHẦN CỦA TÀI LIỆU NGUỒN:\n");
        for (JsonNode section : topSections(summary)) {
            user.append("- ").append(section.path("title").asText().strip()).append(": ")
                .append(section.path("summary").asText().strip()).append('\n');
        }
        user.append("\nĐƠN VỊ VÀ PHÒNG/NHÓM CÓ THỂ GIAO (khóa: tên [loại]):\n");
        assignable.forEach(target -> user.append("- ").append(targetKey(target)).append(": ").append(target.name())
            .append("org".equals(target.targetType()) ? " [đơn vị]" : " [phòng/nhóm]").append('\n'));

        return user.toString();
    }

    /**
     * The proposal the author sees: each target the model named that is in the list, once, as the page's assign row
     * takes it ({@code TargetPick}); "include the units below" only where the author may assign to all of them.
     */
    Proposal checkedAudience(JsonNode answer, List<DraftingTargets.Target> assignable, List<String> blockers) {
        Map<String, DraftingTargets.Target> byKey = new LinkedHashMap<>();
        assignable.forEach(target -> byKey.put(targetKey(target), target));
        Set<String> seen = new LinkedHashSet<>();
        ObjectNode proposal = json.createObjectNode();
        var picks = proposal.putArray("targets");
        List<String> names = new ArrayList<>();
        for (JsonNode item : answer) {
            String key = item.path("key").asText().strip();
            DraftingTargets.Target target = byKey.get(key);
            if (target == null || !seen.add(key)) {
                continue;
            }
            ObjectNode pick = picks.addObject().put("targetType", target.targetType());
            if ("org".equals(target.targetType())) {
                pick.put("orgId", target.id());
            } else {
                pick.put("teamId", target.id());
            }
            pick.put("includeDescendants", target.withDescendants() && item.path("includeDescendants").asBoolean());
            pick.put("label", target.name());
            names.add(target.name());
        }
        var blockerList = proposal.putArray("publishBlockers");
        blockers.forEach(blockerList::add);

        List<String> notes = new ArrayList<>();
        notes.add(names.isEmpty() ? NO_AUDIENCE : "Đề xuất giao cho: " + String.join(", ", names) + ".");
        notes.add(blockers.isEmpty() ? PUBLISHABLE
            : "Trước khi xuất bản còn: " + String.join("; ", blockers) + ".");
        notes.add(NOTHING_ASSIGNED);

        return new Proposal(proposal, List.copyOf(notes), null);
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
