package vn.com.coreacademy.assistant.tool;

import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.course.GenerationSchemas;
import vn.com.coreacademy.assistant.drafting.CourseDraftingService;
import vn.com.coreacademy.assistant.drafting.DraftingViews.SessionView;
import vn.com.coreacademy.assistant.drafting.DraftingViews.StartFields;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.constants.RoleCodes;
import vn.com.coreacademy.identity.dto.RoleGrant;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;

/**
 * Soạn cùng trợ lý from the chat: with the document the user attached (already uploaded as their own course source),
 * it starts a drafting session whose step messages come back to this conversation, and sends the app to the new course
 * page that follows the session. Without a document it asks for one. The model only supplies what the user said
 * (title, learners, level); the document, the caller and the conversation come from the turn's own context, never from
 * the model. Every answer is the tool's own text: the model adds nothing, so it cannot claim a course was made.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartCourseDraftingTool implements AgentTool {
    static final String PATH = "/admin/courses/new?session=";
    static final String TITLE = "Khóa học mới";
    static final String ANSWER = "Mình đang đọc tài liệu và mở trang Khóa học mới. Mình sẽ điền từng bước để bạn "
        + "xem, sửa nếu cần rồi bấm \"Đã chốt\"; mỗi bước xong mình báo ở đây.";
    /** The size is the upload limit itself ({@code app.knowledge.max-file-size}), so the two cannot disagree. */
    static final String ASK_FOR_DOCUMENT = "Bạn đính kèm tài liệu (.pdf, .docx hoặc .md, tối đa %d MB) rồi gửi lại "
        + "yêu cầu, mình sẽ đọc và soạn khóa học từ đó.";
    static final String FOR_INSTRUCTORS = "Tính năng tạo khóa học dành cho giảng viên.";

    private final CourseDraftingService drafting;
    private final KnowledgeProperties knowledge;

    @Override
    public boolean actsForUser() {
        return true;
    }

    @Override
    public String name() {
        return ToolDefinitions.START_COURSE_DRAFTING;
    }

    @Override
    public boolean availableTo(AgentContext context) {
        return roles(context).anyMatch(RoleCodes.COURSE_MANAGERS::contains);
    }

    @Override
    public ToolDefinition definition(AgentContext context) {
        return ToolDefinitions.startCourseDrafting();
    }

    @Override
    public ToolOutcome run(JsonNode arguments, AgentContext context, TurnState turn) {
        if (!availableTo(context)) {
            return new ToolOutcome.Reply(FOR_INSTRUCTORS);
        }
        if (context.attachedDocumentId() == null) {
            return new ToolOutcome.Reply(ASK_FOR_DOCUMENT.formatted(knowledge.maxFileSize().toMegabytes()));
        }
        String level = ToolArguments.text(arguments, "level");
        StartFields hints = new StartFields(text(arguments, "title", GenerationSchemas.MAX_TITLE),
            text(arguments, "targetLearners", GenerationSchemas.MAX_TARGET_LEARNERS),
            level != null && GenerationSchemas.LEVELS.contains(level) ? level : null, null);
        try {
            SessionView started = drafting.startFromSource(context.userId(), context.attachedDocumentId(), hints,
                context.conversationId());
            return new ToolOutcome.Navigate(PATH + started.id(), TITLE, ANSWER);
        } catch (AccessDeniedException exception) {
            return new ToolOutcome.Reply(FOR_INSTRUCTORS);
        } catch (BusinessException exception) {
            log.info("Course drafting not started from the chat code={}", exception.getResponseCode().getCode());
            return new ToolOutcome.Reply(message(exception));
        }
    }

    /** The refusal as the page would show it: the field's message (limit, unit, document), else the code's. */
    private static String message(BusinessException exception) {
        return exception.getErrors().isEmpty() ? exception.getResponseCode().getMessage()
            : exception.getErrors().get(0).message();
    }

    private static String text(JsonNode arguments, String name, int max) {
        String value = ToolArguments.text(arguments, name);

        return value == null || value.isEmpty() || value.length() > max ? null : value;
    }

    private static Stream<String> roles(AgentContext context) {
        if (context.rights() == null || context.rights().roles() == null) {
            return Stream.empty();
        }

        return context.rights().roles().stream().map(RoleGrant::roleCode);
    }
}
