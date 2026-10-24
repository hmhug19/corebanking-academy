package vn.com.coreacademy.assistant.tool;

import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;
import vn.com.coreacademy.knowledge.ai.ToolDefinition;
import vn.com.coreacademy.knowledge.exception.KnowledgeResponseCode;
import vn.com.coreacademy.knowledge.service.ChatPromptBuilder;
import vn.com.coreacademy.knowledge.service.KnowledgeChatService;
import vn.com.coreacademy.knowledge.service.KnowledgeChatService.Retrieval;
import vn.com.coreacademy.knowledge.service.NotFoundAnswer;

/**
 * Searches the shared documents with the retrieval of the knowledge chat. It only reads, so it runs inside the loop;
 * the numbered chunks go back to the model and the turn keeps them to build the citations of the final answer.
 */
@Component
@RequiredArgsConstructor
public class SearchDocumentsTool implements AgentTool {
    private static final int MAX_QUERY_LENGTH = 300;
    private static final String INVALID_QUERY = "Câu tìm kiếm phải từ 1 đến 300 ký tự.";
    private static final String INSTRUCTION = "Chỉ trả lời từ các đoạn trên, ghi [n] sau mỗi ý. "
        + "Nếu các đoạn không có câu trả lời, trả lời đúng một câu: \"" + NotFoundAnswer.TEXT + "\"";
    private static final String NOTHING_FOUND = "Không có đoạn tài liệu nào liên quan. "
        + "Trả lời đúng một câu: \"" + NotFoundAnswer.TEXT + "\"";
    private static final String NOTHING_NEW = "Không có đoạn mới nào ngoài các đoạn đã nêu. ";

    private final KnowledgeChatService knowledge;

    @Override
    public String name() {
        return ToolDefinitions.SEARCH_DOCUMENTS;
    }

    @Override
    public boolean availableTo(AgentContext context) {
        return true;
    }

    @Override
    public ToolDefinition definition(AgentContext context) {
        return ToolDefinitions.searchDocuments();
    }

    @Override
    public ToolOutcome run(JsonNode arguments, AgentContext context, TurnState turn) {
        String query = ToolArguments.text(arguments, "query");
        if (query == null || query.isEmpty() || query.length() > MAX_QUERY_LENGTH) {
            return new ToolOutcome.Result(INVALID_QUERY);
        }

        Retrieval retrieval;
        try {
            retrieval = knowledge.retrieve(query);
        } catch (BusinessException exception) {
            if (exception.getResponseCode() == KnowledgeResponseCode.AI_UNAVAILABLE) {
                // The runtime ends the stream with ASSISTANT-AI_UNAVAILABLE for this exception.
                throw new OllamaUnavailableException("Retrieval needs Ollama", exception);
            }
            throw exception;
        }

        RetrievedChunks chunks = turn.state(RetrievedChunks.class, RetrievedChunks::new);
        var added = chunks.add(retrieval.hits());
        turn.finishWith(chunks::finish);
        if (chunks.isEmpty()) {
            return new ToolOutcome.Result(NOTHING_FOUND);
        }
        if (added.isEmpty()) {
            return new ToolOutcome.Result(NOTHING_NEW + INSTRUCTION);
        }

        String listing = added.stream().map(chunk -> ChatPromptBuilder.formatChunk(chunk.n(), chunk.hit()))
            .collect(Collectors.joining("\n\n"));
        return new ToolOutcome.Result(listing + "\n\n" + INSTRUCTION);
    }
}
