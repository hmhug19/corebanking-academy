package vn.com.coreacademy.assistant.tool;

import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.service.ChatPromptBuilder;
import vn.com.coreacademy.learning.index.CourseQuestionSearch;

/**
 * Reads the lesson the user opened the assistant from, in reading order, for a summary. It takes no argument: the lesson
 * comes from the turn's context, which the server checked against the user's access, never from the model.
 */
@Component
@RequiredArgsConstructor
public class ReadLessonTool implements AgentTool {
    static final String NO_LESSON = "Không có bài học nào đang mở.";

    private final CourseQuestionSearch courses;

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

        List<ChunkHit> hits = courses.lessonText(context.lesson().lessonId());
        RetrievedChunks chunks = turn.state(RetrievedChunks.class, RetrievedChunks::new);
        var added = chunks.add(hits);
        turn.finishWith(chunks::finish);
        if (chunks.isEmpty()) {
            return new ToolOutcome.Result(SearchDocumentsTool.NOTHING_FOUND);
        }
        if (added.isEmpty()) {
            return new ToolOutcome.Result(SearchDocumentsTool.NOTHING_NEW + SearchDocumentsTool.INSTRUCTION);
        }

        String listing = added.stream().map(chunk -> ChatPromptBuilder.formatChunk(chunk.n(), chunk.hit()))
            .collect(Collectors.joining("\n\n"));
        return new ToolOutcome.Result(SearchDocumentsTool.DATA_NOTE + listing + "\n\n"
            + SearchDocumentsTool.INSTRUCTION);
    }
}
