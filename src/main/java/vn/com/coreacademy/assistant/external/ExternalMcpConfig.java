package vn.com.coreacademy.assistant.external;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncPromptSpecification;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.GetPromptResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Prompt;
import io.modelcontextprotocol.spec.McpSchema.PromptArgument;
import io.modelcontextprotocol.spec.McpSchema.PromptMessage;
import io.modelcontextprotocol.spec.McpSchema.Role;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The tools and the one prompt of {@code /api/v1/mcp} (ADR 0004). Exactly these, nothing that reads stored documents,
 * lessons, courses, the knowledge base or the community: the Trợ lý ngoài only sees the document the author gives it.
 * Each description carries the rules the model must follow. The Spring AI starter serves them (stateless, sync).
 */
@Configuration(proxyBeanMethods = false)
public class ExternalMcpConfig {
    static final String SESSION_ID = "session_id";
    private static final Map<String, Object> SESSION_ID_SCHEMA = Map.of("type", "integer", "minimum", 1,
        "description", "The drafting session id start_course_drafting returned.");

    @Bean
    List<SyncToolSpecification> externalMcpTools(ExternalAssistantTools tools) {
        return List.of(
            tool(ExternalAssistantTools.START, """
                Start drafting a course from the document the author gave you in this chat. The server never \
                receives the document: pass only its file name. Returns the session, the editorUrl to give the author \
                and waitingStep (1). Then call get_reference_data and propose_step for step 1.""",
                schema(Map.of("source_name", Map.of("type", "string", "minLength", 1, "maxLength", 255,
                    "description", "The document's file name or title, shown to the author.")), "source_name"),
                (context, request) -> tools.start(request.arguments())),
            tool(ExternalAssistantTools.REFERENCE_DATA, """
                The lists a proposal may pick ids from: programmes and modules (step 1, a module of the programme's \
                fieldId), competencies (step 3), audience keys (step 4), levels and requirements. Ids and names \
                only.""", schema(Map.of()), (context, request) -> tools.referenceData(request.arguments())),
            tool(ExternalAssistantTools.GET_SESSION, """
                Read one of your drafting sessions: each step's state (proposing, proposed, confirmed, failed, \
                manual), your proposal and the values the author confirmed, including their own edits. Read it after \
                the author says "tiếp" and build the next step on what they confirmed.""",
                schema(Map.of(SESSION_ID, SESSION_ID_SCHEMA), SESSION_ID),
                (context, request) -> tools.get(request.arguments())),
            tool(ExternalAssistantTools.STOP, """
                Stop a drafting session when the author asks to. What was proposed and confirmed stays; the author \
                fills the rest by hand on the page.""",
                schema(Map.of(SESSION_ID, SESSION_ID_SCHEMA), SESSION_ID),
                (context, request) -> tools.stop(request.arguments())));
    }

    @Bean
    List<SyncPromptSpecification> externalMcpPrompts() {
        Prompt prompt = new Prompt("soan_khoa_hoc", "Soạn khóa học",
            "Draft a CHAN Academy course from a document, one step at a time.",
            List.of(new PromptArgument("tai_lieu", "Tài liệu", "The document's file name", false)));
        return List.of(new SyncPromptSpecification(prompt, (context, request) -> {
            Object name = request.arguments() == null ? null : request.arguments().get("tai_lieu");
            String document = name == null ? "the document I attached" : "the document " + name;
            return new GetPromptResult("Soạn khóa học", List.of(new PromptMessage(Role.USER, new TextContent("""
                Draft a CHAN Academy course from %s. Call start_course_drafting and give me the editor link. Then \
                propose step 1 only. After each step I open the link, edit and press "Chốt bước", and say "tiếp"; \
                read the session again and propose the next step. Do not write lesson content and do not say the \
                course was created or published.""".formatted(document)))));
        }));
    }

    static SyncToolSpecification tool(String name, String description, JsonSchema input,
                                      BiFunction<McpTransportContext, CallToolRequest, CallToolResult> handler) {
        return new SyncToolSpecification(Tool.builder().name(name).description(description).inputSchema(input).build(),
            handler);
    }

    static JsonSchema schema(Map<String, Object> properties, String... required) {
        return new JsonSchema("object", properties, List.of(required), false, null, null);
    }
}
