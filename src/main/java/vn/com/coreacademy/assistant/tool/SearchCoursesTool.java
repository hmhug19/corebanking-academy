package vn.com.coreacademy.assistant.tool;

import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.knowledge.ai.OllamaException;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.service.ChatPromptBuilder;
import vn.com.coreacademy.learning.index.CourseQuestionSearch;

/**
 * Searches the lessons of published courses the user may read (their enrolments, or the courses they manage). Like
 * the document search it only reads, so it runs inside the loop, and its chunks join the numbered sources of the turn.
 */
@Component
@RequiredArgsConstructor
public class SearchCoursesTool implements AgentTool {
    private final CourseQuestionSearch courses;

    @Override
    public String name() {
        return ToolDefinitions.SEARCH_COURSES;
    }

    @Override
    public boolean availableTo(AgentContext context) {
        return true;
    }

    @Override
    public boolean readsSharedContent() {
        return true;
    }

    @Override
    public ToolDefinition definition(AgentContext context) {
        return ToolDefinitions.searchCourses();
    }

    @Override
    public ToolOutcome run(JsonNode arguments, AgentContext context, TurnState turn) {
        String query = ToolArguments.text(arguments, "query");
        if (query == null || query.isEmpty() || query.length() > SearchDocumentsTool.MAX_QUERY_LENGTH) {
            return new ToolOutcome.Result(SearchDocumentsTool.INVALID_QUERY);
        }

        List<ChunkHit> hits;
        try {
            hits = context.lesson() == null ? courses.hits(query)
                : courses.hits(query, context.lesson().lessonId(), context.lesson().courseId());
        } catch (OllamaException exception) {
            // The runtime ends the stream with ASSISTANT-AI_UNAVAILABLE for this exception.
            throw new OllamaUnavailableException("Retrieval needs Ollama", exception);
        }

        RetrievedChunks chunks = turn.chunks();
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
