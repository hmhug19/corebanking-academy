package vn.com.coreacademy.assistant.tool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.action.PendingAction;
import vn.com.coreacademy.assistant.action.PendingActionStore;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.course.CourseJobRepository;
import vn.com.coreacademy.assistant.course.CourseJobRepository.Named;
import vn.com.coreacademy.assistant.course.CourseJobService;
import vn.com.coreacademy.assistant.course.CourseJobViews.Inputs;
import vn.com.coreacademy.assistant.course.CourseJobViews.JobView;
import vn.com.coreacademy.assistant.dto.ActionResultView;
import vn.com.coreacademy.assistant.dto.PendingActionView.Change;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.dto.RoleGrant;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;

/**
 * Proposes to start a course generation from the file the user attached. Like every tool that acts for the user it only
 * stores a pending action and ends the turn with a card; the job starts in {@link #execute} after the user confirms,
 * through {@link CourseJobService} as that user, so the role, unit and daily limit are checked again there. The model
 * only passes what the user said: programme and competency are looked up by name and an ambiguous name is put back to the
 * user, the document comes from the context and never from the model, and the unit is never an argument (an
 * administrator picks it in the start form instead).
 */
@Component
@RequiredArgsConstructor
public class StartCourseJobTool implements AgentTool {
    static final String ATTACH_FIRST =
        "Hãy đính kèm một tệp .pdf, .docx hoặc .md bằng nút ghim ở ô soạn tin rồi gửi lại yêu cầu.";
    static final String ADMIN_USES_FORM =
        "Quản trị viên cần chọn đơn vị sở hữu khóa học: hãy mở Khóa học mới rồi bấm \"Tạo bằng AI\".";
    private static final Set<String> MANAGERS = Set.of(RoleCodes.SUPER_ADMIN, RoleCodes.ADMIN, RoleCodes.INSTRUCTOR);
    private static final Set<String> ADMINS = Set.of(RoleCodes.SUPER_ADMIN, RoleCodes.ADMIN);
    private static final Set<String> LEVELS = Set.of("BEGINNER", "INTERMEDIATE", "ADVANCED");
    private static final Map<String, String> LEVEL_LABELS = Map.of("BEGINNER", "Cơ bản", "INTERMEDIATE", "Trung cấp",
        "ADVANCED", "Nâng cao");
    private static final String TITLE = "Tạo khóa học bằng AI";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PendingActionStore store;
    private final CourseJobRepository jobs;
    private final CourseJobService service;

    @Override
    public boolean actsForUser() {
        return true;
    }

    @Override
    public String name() {
        return ToolDefinitions.START_COURSE_JOB;
    }

    @Override
    public boolean availableTo(AgentContext context) {
        return roles(context).anyMatch(MANAGERS::contains);
    }

    @Override
    public ToolDefinition definition(AgentContext context) {
        return ToolDefinitions.startCourseJob();
    }

    @Override
    public ToolOutcome run(JsonNode arguments, AgentContext context, TurnState turn) {
        if (roles(context).anyMatch(ADMINS::contains)) {
            return new ToolOutcome.Result(ADMIN_USES_FORM);
        }
        Long documentId = context.attachedDocumentId();
        if (documentId == null || !jobs.isFreePersonalDocument(documentId, context.userId())) {
            return new ToolOutcome.Result(ATTACH_FIRST);
        }

        String learners = ToolArguments.text(arguments, "targetLearners");
        String level = ToolArguments.text(arguments, "level");
        Integer chapters = number(arguments, "chapters");
        Integer minutes = number(arguments, "durationMinutes");
        String programmeName = ToolArguments.text(arguments, "programmeName");
        String competencyName = ToolArguments.text(arguments, "competencyName");

        List<String> missing = new ArrayList<>();
        if (learners == null || learners.isEmpty()) {
            missing.add("đối tượng học viên");
        }
        if (level == null || !LEVELS.contains(level)) {
            missing.add("trình độ (cơ bản, trung cấp hoặc nâng cao)");
        }
        if (chapters == null && minutes == null) {
            missing.add("số chương hoặc thời lượng mong muốn");
        }
        if (programmeName == null || programmeName.isEmpty()) {
            missing.add("chương trình đào tạo");
        }
        if (competencyName == null || competencyName.isEmpty()) {
            missing.add("năng lực gán cho câu hỏi");
        }
        if (!missing.isEmpty()) {
            return new ToolOutcome.Result("Còn thiếu: " + String.join("; ", missing)
                + ". Hãy hỏi người dùng những thông tin này, không tự đặt giá trị.");
        }

        List<Named> programmes = jobs.programmesNamed(programmeName);
        Named programme = pick(programmes, programmeName);
        if (programme == null) {
            return new ToolOutcome.Result(unclear("chương trình đào tạo", programmeName, programmes));
        }
        List<Named> competencies = jobs.competenciesNamed(competencyName);
        Named competency = pick(competencies, competencyName);
        if (competency == null) {
            return new ToolOutcome.Result(unclear("năng lực", competencyName, competencies));
        }

        ObjectNode values = JSON.createObjectNode();
        values.put("documentId", documentId);
        values.put("targetLearners", learners);
        values.put("level", level);
        if (chapters != null) {
            values.put("chapters", chapters);
        }
        if (minutes != null) {
            values.put("durationMinutes", minutes);
        }
        values.put("programmeId", programme.id());
        values.put("competencyId", competency.id());

        List<Change> shown = new ArrayList<>();
        shown.add(new Change("targetLearners", "Đối tượng học viên", null, learners));
        shown.add(new Change("level", "Trình độ", null, LEVEL_LABELS.get(level)));
        if (chapters != null) {
            shown.add(new Change("chapters", "Số chương", null, String.valueOf(chapters)));
        }
        if (minutes != null) {
            shown.add(new Change("durationMinutes", "Thời lượng (phút)", null, String.valueOf(minutes)));
        }
        shown.add(new Change("programmeId", "Chương trình đào tạo", null, programme.name()));
        shown.add(new Change("competencyId", "Năng lực", null, competency.name()));
        PendingAction action = store.add(context.userId(), name(), values, TITLE, shown);

        return new ToolOutcome.ActionRequired(action, "Vui lòng kiểm tra và xác nhận để bắt đầu tạo khóa học.");
    }

    /** Runs after the user confirmed, on the request thread with their security context. */
    @Override
    public ActionResultView execute(PendingAction action, AgentContext context) {
        JsonNode in = action.arguments();
        Inputs inputs = new Inputs(in.path("targetLearners").asText(), in.path("level").asText(),
            in.hasNonNull("chapters") ? in.path("chapters").asInt() : null,
            in.hasNonNull("durationMinutes") ? in.path("durationMinutes").asInt() : null,
            in.path("programmeId").asLong(), in.path("competencyId").asLong(), null);
        JobView job = service.createFromDocument(in.path("documentId").asLong(), inputs);

        return new ActionResultView(action.id(), name(), "Đã bắt đầu tạo khóa học. Mở màn hình rà soát để theo dõi.",
            Map.of("jobId", job.jobId(), "path", "/admin/courses/ai-drafts/" + job.jobId()));
    }

    private static Stream<String> roles(AgentContext context) {
        if (context.rights() == null || context.rights().roles() == null) {
            return Stream.empty();
        }

        return context.rights().roles().stream().map(RoleGrant::roleCode);
    }

    private static Integer number(JsonNode arguments, String name) {
        return arguments != null && arguments.path(name).isInt() ? arguments.path(name).asInt() : null;
    }

    /** The one exact match, or the only match; null when there is none or the name could mean several. */
    private static Named pick(List<Named> found, String text) {
        List<Named> exact = found.stream().filter(named -> named.name().equalsIgnoreCase(text.strip())).toList();
        if (exact.size() == 1) {
            return exact.get(0);
        }

        return exact.isEmpty() && found.size() == 1 ? found.get(0) : null;
    }

    private static String unclear(String what, String said, List<Named> found) {
        if (found.isEmpty()) {
            return "Không có " + what + " nào tên \"" + said.replace('"', ' ') + "\". Hãy hỏi lại người dùng tên đúng.";
        }

        return "Có nhiều " + what + " gần giống: " + String.join("; ", found.stream().map(Named::name).toList())
            + ". Hãy hỏi người dùng chọn một.";
    }
}
