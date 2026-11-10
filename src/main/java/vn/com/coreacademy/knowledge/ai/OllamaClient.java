package vn.com.coreacademy.knowledge.ai;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;

/**
 * The only door to the local Ollama server. Prompts and document text never reach the logs: a call logs the model,
 * the duration and sizes only.
 */
@Slf4j
@Component
public class OllamaClient {
    /** bge-m3 output size; the {@code vector(1024)} column rejects anything else. */
    public static final int EMBEDDING_DIMENSIONS = 1024;

    private static final int EMBED_BATCH_SIZE = 16;
    private static final Duration AVAILABILITY_TIMEOUT = Duration.ofSeconds(2);

    private final KnowledgeProperties.Ollama config;
    private final ObjectMapper json = new ObjectMapper();
    private final RestClient client;
    private final RestClient probe;

    public OllamaClient(KnowledgeProperties properties) {
        this.config = properties.ollama();
        this.client = restClient(config.connectTimeout(), config.readTimeout());
        this.probe = restClient(AVAILABILITY_TIMEOUT, AVAILABILITY_TIMEOUT);
    }

    public record Message(String role, String content) {
        public static Message system(String content) {
            return new Message("system", content);
        }

        public static Message user(String content) {
            return new Message("user", content);
        }

        public static Message assistant(String content) {
            return new Message("assistant", content);
        }
    }

    public record Options(double temperature, int numCtx) {
        /** Low temperature and a context wide enough for the retrieved chunks. */
        public static final Options GROUNDED = new Options(0.1, 8192);

        /** The agent loop: the same settings as {@link #GROUNDED}, named for its own use. */
        public static final Options AGENT = new Options(0.1, 8192);
    }

    /** Whether Ollama answers {@code GET /api/version}; never throws. */
    public boolean isAvailable() {
        try {
            probe.get().uri("/api/version").retrieve().toBodilessEntity();
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** Embeds the inputs in batches of 16 and returns one vector per input, in order. */
    public List<float[]> embed(List<String> inputs) {
        List<float[]> vectors = new ArrayList<>(inputs.size());
        for (int from = 0; from < inputs.size(); from += EMBED_BATCH_SIZE) {
            List<String> batch = inputs.subList(from, Math.min(from + EMBED_BATCH_SIZE, inputs.size()));
            vectors.addAll(embedBatch(batch));
        }
        return vectors;
    }

    /** One complete answer ({@code stream:false}). */
    public String chat(List<Message> messages, Options options) {
        long started = System.nanoTime();
        JsonNode response = post("/api/chat", chatBody(messages, options, false), JsonNode.class);
        log.info("Ollama chat model={} messages={} durationMs={}", config.chatModel(), messages.size(),
            millisSince(started));
        return response.path("message").path("content").asText("");
    }

    /** Streams the answer; every non-empty {@code message.content} delta goes to {@code onDelta} as it arrives. */
    public void chatStream(List<Message> messages, Options options, Consumer<String> onDelta) {
        long started = System.nanoTime();
        try {
            client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON)
                .body(chatBody(messages, options, true))
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw new OllamaException("Ollama answered HTTP " + response.getStatusCode().value());
                    }
                    readStream(new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8)),
                        onDelta);
                    return null;
                });
        } catch (ResourceAccessException exception) {
            throw new OllamaUnavailableException("Ollama is not reachable", exception);
        }
        log.info("Ollama chat stream model={} messages={} durationMs={}", config.chatModel(), messages.size(),
            millisSince(started));
    }

    /**
     * One model call with tools ({@code stream:true}). Content deltas go to {@code onDelta} as they arrive; the
     * {@code tool_calls} of every line are collected in order. Neither the messages nor the arguments are logged.
     */
    public ChatTurn chatWithTools(List<ChatMessage> messages, List<ToolDefinition> tools, Options options,
                                  Consumer<String> onDelta) {
        long started = System.nanoTime();
        Map<String, Object> body = chatBody(messages, options, true);
        body.put("tools", tools.stream().map(OllamaClient::toolBody).toList());
        StringBuilder content = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        try {
            client.post().uri("/api/chat").contentType(MediaType.APPLICATION_JSON).body(body)
                .exchange((request, response) -> {
                    if (response.getStatusCode().isError()) {
                        throw new OllamaException("Ollama answered HTTP " + response.getStatusCode().value());
                    }
                    BufferedReader reader =
                        new BufferedReader(new InputStreamReader(response.getBody(), StandardCharsets.UTF_8));
                    readStream(reader, delta -> {
                        content.append(delta);
                        onDelta.accept(delta);
                    }, toolCalls);
                    return null;
                });
        } catch (ResourceAccessException exception) {
            throw new OllamaUnavailableException("Ollama is not reachable", exception);
        }
        log.info("Ollama chat tools model={} messages={} tools={} calls={} durationMs={}", config.chatModel(),
            messages.size(), tools.size(), loggedToolNames(toolCalls, tools), millisSince(started));
        return new ChatTurn(content.toString(), List.copyOf(toolCalls));
    }

    /** The model writes the call's name, so it may hold any text; only the names of offered tools are logged. */
    static List<String> loggedToolNames(List<ToolCall> calls, List<ToolDefinition> tools) {
        Set<String> offered = tools.stream().map(ToolDefinition::name).collect(Collectors.toSet());
        return calls.stream().map(call -> offered.contains(call.name()) ? call.name() : "unknown").toList();
    }

    private void readStream(BufferedReader reader, Consumer<String> onDelta) throws IOException {
        readStream(reader, onDelta, new ArrayList<>());
    }

    private void readStream(BufferedReader reader, Consumer<String> onDelta, List<ToolCall> toolCalls)
        throws IOException {
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode event = json.readTree(line);
            if (event.hasNonNull("error")) {
                throw new OllamaException("Ollama reported an error while streaming");
            }
            String delta = event.path("message").path("content").asText("");
            if (!delta.isEmpty()) {
                onDelta.accept(delta);
            }
            for (JsonNode call : event.path("message").path("tool_calls")) {
                JsonNode function = call.path("function");
                toolCalls.add(ToolCall.of(function.path("name").asText(""), function.path("arguments")));
            }
            if (event.path("done").asBoolean(false)) {
                return;
            }
        }
        // A model runner that dies mid-answer closes the connection without a done line; the text is incomplete.
        throw new OllamaException("Ollama closed the stream before its done line");
    }

    private List<float[]> embedBatch(List<String> batch) {
        long started = System.nanoTime();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.embedModel());
        body.put("input", batch);
        body.put("keep_alive", keepAlive());

        JsonNode embeddings = post("/api/embed", body, JsonNode.class).path("embeddings");
        if (!embeddings.isArray() || embeddings.size() != batch.size()) {
            throw new OllamaException("Ollama returned an unexpected number of embeddings");
        }

        List<float[]> vectors = new ArrayList<>(batch.size());
        for (JsonNode embedding : embeddings) {
            if (embedding.size() != EMBEDDING_DIMENSIONS) {
                throw new OllamaException("Embedding has " + embedding.size() + " dimensions, expected "
                    + EMBEDDING_DIMENSIONS);
            }
            float[] vector = new float[EMBEDDING_DIMENSIONS];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) embedding.get(i).asDouble();
            }
            vectors.add(vector);
        }
        log.info("Ollama embed model={} inputs={} durationMs={}", config.embedModel(), batch.size(),
            millisSince(started));
        return vectors;
    }

    private static Map<String, Object> toolBody(ToolDefinition tool) {
        Map<String, Object> function = new LinkedHashMap<>();
        function.put("name", tool.name());
        function.put("description", tool.description());
        function.put("parameters", tool.parameters());
        return Map.of("type", "function", "function", function);
    }

    private Map<String, Object> chatBody(List<?> messages, Options options, boolean stream) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", config.chatModel());
        body.put("messages", messages);
        body.put("stream", stream);
        // qwen3 would otherwise spend the budget on a hidden reasoning pass.
        body.put("think", false);
        body.put("options", Map.of("temperature", options.temperature(), "num_ctx", options.numCtx()));
        body.put("keep_alive", keepAlive());
        return body;
    }

    private <T> T post(String path, Object body, Class<T> type) {
        try {
            return client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve()
                .body(type);
        } catch (ResourceAccessException exception) {
            throw new OllamaUnavailableException("Ollama is not reachable", exception);
        } catch (RestClientResponseException exception) {
            throw new OllamaException("Ollama answered HTTP " + exception.getStatusCode().value());
        }
    }

    private RestClient restClient(Duration connectTimeout, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(config.baseUrl()).requestFactory(factory).build();
    }

    private String keepAlive() {
        return config.keepAlive().toSeconds() + "s";
    }

    private static long millisSince(long startedNanos) {
        return Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
    }
}
