package vn.com.coreacademy.knowledge.service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaClient.Message;
import vn.com.coreacademy.knowledge.ai.OllamaException;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.dto.ChatStreamEvent;
import vn.com.coreacademy.knowledge.dto.ChatViews.ChatAnswerView;
import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;
import vn.com.coreacademy.knowledge.exception.KnowledgeResponseCode;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;

/**
 * Answers a question from the shared knowledge base only: embed, find the closest chunks, and let the local model
 * answer from them. Citations are built from the retrieved chunks' metadata, never parsed from the model's text. The
 * question and the answer are never logged; only sizes and durations are.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChatService {
    private static final int MAX_QUESTION_LENGTH = 300;
    private static final int SNIPPET_LENGTH = 280;

    private final OllamaClient ollama;
    private final DocumentChunkRepository chunks;
    private final ChatPromptBuilder prompts;
    private final KnowledgeProperties properties;
    private final ObjectMapper json;

    /** The validated question and the chunks that passed the similarity threshold, best first. */
    public record Retrieval(String question, List<ChunkHit> hits) {
    }

    /** Validate, embed, search, filter. Refusals are thrown so a caller can still answer with an envelope. */
    public Retrieval retrieve(String rawQuestion) {
        String question = rawQuestion == null ? "" : rawQuestion.strip();
        if (question.isEmpty() || question.length() > MAX_QUESTION_LENGTH) {
            throw BusinessException.validation("question", "OUT_OF_RANGE", "Câu hỏi phải từ 1 đến 300 ký tự.");
        }

        List<ChunkHit> hits;
        try {
            float[] vector = ollama.embed(List.of(question)).get(0);
            KnowledgeProperties.Retrieval retrieval = properties.retrieval();
            hits = chunks.search(vector, retrieval.topK()).stream()
                .filter(hit -> hit.similarity() >= retrieval.minSimilarity()).toList();
        } catch (OllamaUnavailableException | OllamaException exception) {
            throw new BusinessException(KnowledgeResponseCode.AI_UNAVAILABLE);
        }
        log.info("Knowledge retrieval questionLength={} hits={}", question.length(), hits.size());

        return new Retrieval(question, hits);
    }

    /** One complete answer, the contract of record for tests and tooling. */
    public ChatAnswerView answer(String rawQuestion) {
        Retrieval retrieval = retrieve(rawQuestion);
        if (retrieval.hits().isEmpty()) {
            return notFound();
        }

        String text;
        try {
            text = ollama.chat(prompts.build(retrieval.question(), retrieval.hits()), OllamaClient.Options.GROUNDED)
                .strip();
        } catch (OllamaUnavailableException | OllamaException exception) {
            throw new BusinessException(KnowledgeResponseCode.AI_UNAVAILABLE);
        }

        return conclude(text, retrieval.hits());
    }

    /**
     * Retrieval runs now, on the request thread, so refusals are still ordinary envelopes. Generation runs inside the
     * returned body: one JSON line per event, flushed at once.
     */
    public StreamingResponseBody stream(String rawQuestion) {
        Retrieval retrieval = retrieve(rawQuestion);
        return output -> {
            write(output, ChatStreamEvent.retrieved(retrieval.hits().size()));
            if (retrieval.hits().isEmpty()) {
                write(output, ChatStreamEvent.delta(NotFoundAnswer.TEXT));
                write(output, ChatStreamEvent.done(false, List.of()));
                return;
            }

            StringBuilder text = new StringBuilder();
            try {
                ollama.chatStream(prompts.build(retrieval.question(), retrieval.hits()), OllamaClient.Options.GROUNDED,
                    delta -> {
                        text.append(delta);
                        write(output, ChatStreamEvent.delta(delta));
                    });
            } catch (OllamaUnavailableException | OllamaException exception) {
                write(output, ChatStreamEvent.error(KnowledgeResponseCode.AI_UNAVAILABLE.getCode(),
                    KnowledgeResponseCode.AI_UNAVAILABLE.getMessage()));
                return;
            }

            ChatAnswerView answer = conclude(text.toString().strip(), retrieval.hits());
            write(output, ChatStreamEvent.done(answer.found(), answer.citations()));
        };
    }

    private ChatAnswerView conclude(String text, List<ChunkHit> hits) {
        if (NotFoundAnswer.isNotFound(text)) {
            return notFound();
        }

        // Every retrieved chunk is a source, numbered as the model saw it; the model's [n] markers are not trusted.
        List<CitationView> citations = IntStream.range(0, hits.size()).mapToObj(i -> citation(i + 1, hits.get(i)))
            .toList();

        return new ChatAnswerView(text, true, citations);
    }

    private static ChatAnswerView notFound() {
        return new ChatAnswerView(NotFoundAnswer.TEXT, false, List.of());
    }

    private static CitationView citation(int n, ChunkHit hit) {
        return new CitationView(n, hit.documentId(), hit.fileName(), hit.page(), hit.lineStart(), hit.lineEnd(),
            snippet(hit.content()));
    }

    /** The first 280 characters, cut back to a word boundary. */
    static String snippet(String content) {
        if (content.length() <= SNIPPET_LENGTH) {
            return content;
        }
        int cut = content.lastIndexOf(' ', SNIPPET_LENGTH);
        int end = cut > 0 ? cut : SNIPPET_LENGTH;

        return content.substring(0, end).stripTrailing() + "…";
    }

    private void write(OutputStream output, ChatStreamEvent event) {
        try {
            output.write(json.writeValueAsString(event).getBytes(StandardCharsets.UTF_8));
            output.write('\n');
            output.flush();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialise a chat event", exception);
        } catch (IOException exception) {
            // The client went away: stop generating (the caller closes the Ollama stream when this propagates).
            throw new UncheckedIOException(exception);
        }
    }
}
