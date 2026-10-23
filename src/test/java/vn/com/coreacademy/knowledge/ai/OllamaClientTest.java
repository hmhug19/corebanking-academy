package vn.com.coreacademy.knowledge.ai;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Talks to a real HTTP server on localhost, so serialisation, streaming and refused connections are all real. */
class OllamaClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private Function<HttpExchange, Reply> handler;

    private record Reply(int status, String body) {
    }

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            if ("POST".equals(exchange.getRequestMethod())) {
                requests.add(json.readTree(exchange.getRequestBody()));
            }
            Reply reply = handler.apply(exchange);
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void isAvailableIsTrueWhenVersionAnswers() {
        handler = exchange -> new Reply(200, "{\"version\":\"0.40.7\"}");

        assertThat(client().isAvailable()).isTrue();
        assertThat(paths).containsExactly("/api/version");
    }

    @Test
    void isAvailableIsFalseAndNeverThrowsWhenNothingListens() throws IOException {
        assertThat(clientOn(closedPort()).isAvailable()).isFalse();
    }

    @Test
    void embedSendsTheConfiguredModelAndReturnsOneVectorPerInput() {
        handler = exchange -> new Reply(200, embeddings(2, 1024));

        List<float[]> vectors = client().embed(List.of("a", "b"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).hasSize(1024);
        assertThat(vectors.get(1)[1]).isEqualTo(1f);
        JsonNode request = requests.get(0);
        assertThat(request.path("model").asText()).isEqualTo("test-embed");
        assertThat(request.path("input").size()).isEqualTo(2);
        assertThat(request.path("keep_alive").asText()).isEqualTo("600s");
    }

    @Test
    void embedSplitsInputsIntoBatchesOfSixteen() {
        handler = exchange -> {
            int size = requests.get(requests.size() - 1).path("input").size();
            return new Reply(200, embeddings(size, 1024));
        };
        List<String> inputs = IntStream.range(0, 35).mapToObj(i -> "text " + i).collect(Collectors.toList());

        List<float[]> vectors = client().embed(inputs);

        assertThat(vectors).hasSize(35);
        assertThat(requests.stream().map(request -> request.path("input").size())).containsExactly(16, 16, 3);
    }

    @Test
    void embedRejectsAVectorWithTheWrongDimensions() {
        handler = exchange -> new Reply(200, embeddings(1, 768));

        assertThatThrownBy(() -> client().embed(List.of("a")))
            .isInstanceOf(OllamaException.class).hasMessageContaining("768");
    }

    @Test
    void chatSendsThinkFalseAndTheGroundedOptions() {
        handler = exchange -> new Reply(200, "{\"message\":{\"role\":\"assistant\",\"content\":\"xin chào\"},\"done\":true}");

        String answer = client().chat(List.of(OllamaClient.Message.system("s"), OllamaClient.Message.user("u")),
            OllamaClient.Options.GROUNDED);

        assertThat(answer).isEqualTo("xin chào");
        JsonNode request = requests.get(0);
        assertThat(request.path("model").asText()).isEqualTo("test-chat");
        assertThat(request.path("stream").asBoolean(true)).isFalse();
        assertThat(request.path("think").asBoolean(true)).isFalse();
        assertThat(request.path("options").path("temperature").asDouble()).isEqualTo(0.1);
        assertThat(request.path("options").path("num_ctx").asInt()).isEqualTo(8192);
        assertThat(request.path("messages").get(1).path("content").asText()).isEqualTo("u");
    }

    @Test
    void chatStreamForwardsEachDeltaInOrder() {
        handler = exchange -> new Reply(200, """
            {"message":{"role":"assistant","content":"CIF "},"done":false}
            {"message":{"role":"assistant","content":"là mã"},"done":false}
            {"message":{"role":"assistant","content":""},"done":true}
            """);
        List<String> deltas = new ArrayList<>();

        client().chatStream(List.of(OllamaClient.Message.user("u")), OllamaClient.Options.GROUNDED, deltas::add);

        assertThat(deltas).containsExactly("CIF ", "là mã");
        assertThat(requests.get(0).path("stream").asBoolean(false)).isTrue();
    }

    @Test
    void chatStreamFailsWhenOllamaReportsAnErrorLine() {
        handler = exchange -> new Reply(200, "{\"error\":\"model runner crashed\"}\n");

        assertThatThrownBy(() -> client().chatStream(List.of(OllamaClient.Message.user("u")),
            OllamaClient.Options.GROUNDED, delta -> { }))
            .isInstanceOf(OllamaException.class);
    }

    @Test
    void aNon2xxStatusIsAnOllamaException() {
        handler = exchange -> new Reply(500, "{\"error\":\"boom\"}");

        assertThatThrownBy(() -> client().chat(List.of(OllamaClient.Message.user("u")), OllamaClient.Options.GROUNDED))
            .isInstanceOf(OllamaException.class).hasMessageContaining("500");
    }

    @Test
    void connectionRefusedIsOllamaUnavailable() throws IOException {
        OllamaClient offline = clientOn(closedPort());

        assertThatThrownBy(() -> offline.embed(List.of("a"))).isInstanceOf(OllamaUnavailableException.class);
        assertThatThrownBy(() -> offline.chat(List.of(OllamaClient.Message.user("u")), OllamaClient.Options.GROUNDED))
            .isInstanceOf(OllamaUnavailableException.class);
        assertThatThrownBy(() -> offline.chatStream(List.of(OllamaClient.Message.user("u")),
            OllamaClient.Options.GROUNDED, delta -> { })).isInstanceOf(OllamaUnavailableException.class);
    }

    private OllamaClient client() {
        return clientOn(server.getAddress().getPort());
    }

    private OllamaClient clientOn(int port) {
        KnowledgeProperties.Ollama ollama = new KnowledgeProperties.Ollama("http://127.0.0.1:" + port, "test-chat",
            "test-embed", Duration.ofSeconds(2), Duration.ofSeconds(5), Duration.ofMinutes(10));
        return new OllamaClient(new KnowledgeProperties(Path.of("unused"), DataSize.ofMegabytes(20),
            new KnowledgeProperties.Worker(Duration.ofSeconds(2), 1, 3),
            new KnowledgeProperties.Chunk(600, 800, 80), new KnowledgeProperties.Retrieval(5, 0.35), ollama));
    }

    private static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    /** {@code count} vectors of {@code dimensions} zeros, each with a 1 at its own index (so they differ). */
    private String embeddings(int count, int dimensions) {
        List<List<Integer>> vectors = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            List<Integer> vector = new ArrayList<>(Collections.nCopies(dimensions, 0));
            vector.set(i % dimensions, 1);
            vectors.add(vector);
        }
        try {
            return json.writeValueAsString(Map.of("embeddings", vectors));
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
