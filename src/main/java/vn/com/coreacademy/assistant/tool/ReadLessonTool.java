package vn.com.coreacademy.assistant.tool;

import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;
import vn.com.coreacademy.knowledge.service.ChatPromptBuilder;
import vn.com.coreacademy.learning.index.LessonReader;
import vn.com.coreacademy.learning.index.LessonReader.LessonText;

/**
 * Reads the lesson the user opened the assistant from, in reading order, for a summary. It takes no argument: the lesson
 * comes from the turn's context, which the server checked against the user's access, never from the model.
 */
@Component
@RequiredArgsConstructor
public class ReadLessonTool implements AgentTool {
    static final String NO_LESSON = "Không có bài học nào đang mở.";
    /** The reply of a lesson with no text at all (a video without transcript, an image), whatever the model writes. */
    public static final String NO_TEXT = "Bài này chưa có nội dung văn bản để mình đọc.";
    static final String CUT_NOTE = "Chỉ đọc được phần đầu của bài, nói rõ điều đó trong câu trả lời. ";

    private final LessonReader reader;

    @Override
    public String name() {
        return ToolDefinitions.READ_LESSON;
    }

    @Override
    public boolean availableTo(AgentContext context) {
        return context.lesson() != null;
    }

    @Override
    public boolean readsSharedContent() {
        return true;
    }

    @Override
    public ToolDefinition definition(AgentContext context) {
        return ToolDefinitions.readLesson();
    }

    @Override
    public ToolOutcome run(JsonNode arguments, AgentContext context, TurnState turn) {
        if (context.lesson() == null) {
            return new ToolOutcome.Result(NO_LESSON);
        }

        var lesson = context.lesson();
        LessonText read = reader.read(lesson.courseId(), lesson.courseTitle(), lesson.revisionId(), lesson.lessonId(),
            lesson.lessonTitle());
        if (read.noText()) {
            turn.finishWith(answer -> new FinishedAnswer(NO_TEXT, java.util.List.of()));
            return new ToolOutcome.Result(NO_TEXT);
        }
        RetrievedChunks chunks = turn.state(RetrievedChunks.class, RetrievedChunks::new);
        var added = chunks.add(read.hits());
        turn.finishWith(chunks::finish);
        if (added.isEmpty()) {
            return new ToolOutcome.Result(SearchDocumentsTool.NOTHING_NEW + SearchDocumentsTool.INSTRUCTION);
        }

        String listing = added.stream().map(chunk -> ChatPromptBuilder.formatChunk(chunk.n(), chunk.hit()))
            .collect(Collectors.joining("\n\n"));
        return new ToolOutcome.Result(SearchDocumentsTool.DATA_NOTE + (read.truncated() ? CUT_NOTE : "") + listing + "\n\n"
            + SearchDocumentsTool.INSTRUCTION);
    }
}
