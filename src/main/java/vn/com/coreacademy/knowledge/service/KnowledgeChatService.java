package vn.com.coreacademy.knowledge.service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import vn.com.coreacademy.assistant.agent.TurnLimiter;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.security.SecurityContextUtils;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaClient.Message;
import vn.com.coreacademy.knowledge.ai.OllamaException;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.dto.ChatStreamEvent;
import vn.com.coreacademy.knowledge.dto.ChatViews.ChatAnswerView;
import vn.com.coreacademy.knowledge.exception.KnowledgeResponseCode;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;

/**
 * Answers a question from the shared knowledge base only: embed, find the closest chunks, and let the local model
 * answer from them. Citations are built from the retrieved chunks' metadata; the model's [n] markers only choose which
 * of those chunks are shown. The question and the answer are never logged; only sizes and durations are.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeChatService {
    private static final int MAX_QUESTION_LENGTH = 300;
    // Re-uploaded files store the same text again, so search asks for more than top-k and keeps the distinct ones.
    private static final int CANDIDATE_FACTOR = 3;
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final OllamaClient ollama;
    private final DocumentChunkRepository chunks;
    private final ChatPromptBuilder prompts;
    private final KnowledgeProperties properties;
    private final ObjectMapper json;
    private final TurnLimiter limiter;

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
            List<ChunkHit> candidates = chunks.search(vector, retrieval.topK() * CANDIDATE_FACTOR).stream()
                .filter(hit -> hit.similarity() >= retrieval.minSimilarity()).toList();
            hits = distinct(candidates, retrieval.topK());
        } catch (OllamaUnavailableException | OllamaException exception) {
            throw new BusinessException(KnowledgeResponseCode.AI_UNAVAILABLE);
        }
        log.info("Knowledge retrieval questionLength={} hits={}", question.length(), hits.size());

        return new Retrieval(question, hits);
    }

    /** One complete answer, the contract of record for tests and tooling. */
    public ChatAnswerView answer(String rawQuestion) {
        Retrieval retrieval = retrieve(rawQuestion);
        try (TurnLimiter.Turn ignored = admitOrRefuse(SecurityContextUtils.getUserId())) {
            if (retrieval.hits().isEmpty()) {
                return notFound();
            }

            String text;
            try {
                text = ollama.chat(prompts.build(retrieval.question(), retrieval.hits()),
                    OllamaClient.Options.GROUNDED).strip();
            } catch (OllamaUnavailableException | OllamaException exception) {
                throw new BusinessException(KnowledgeResponseCode.AI_UNAVAILABLE);
            }

            return conclude(text, retrieval.hits());
        }
    }

    /** The shared per-user limits of the agent apply to the document chat too; a refusal is an ordinary envelope. */
    private TurnLimiter.Turn admitOrRefuse(String userId) {
        TurnLimiter.Admission admission = limiter.admit(userId);
        if (admission instanceof TurnLimiter.Admission.Started started) {
            return started.turn();
        }
        if (admission instanceof TurnLimiter.Admission.RateLimited limited) {
            throw BusinessException.field(AssistantResponseCode.RATE_LIMITED, "question", "RATE_LIMITED",
                limited.message());
        }

        throw new BusinessException(AssistantResponseCode.BUSY);
    }

    /**
     * Retrieval runs now, on the request thread, so refusals are still ordinary envelopes. Generation runs inside the
     * returned body: one JSON line per event, flushed at once.
     */
    public StreamingResponseBody stream(String rawQuestion) {
        Retrieval retrieval = retrieve(rawQuestion);
        String userId = SecurityContextUtils.getUserId();
        return output -> {
            TurnLimiter.Admission admission = limiter.admit(userId);
            if (!(admission instanceof TurnLimiter.Admission.Started started)) {
                write(output, refusal(admission));
                return;
            }
            try (TurnLimiter.Turn ignored = started.turn()) {
                streamAnswer(output, retrieval);
            }
        };
    }

    private static ChatStreamEvent refusal(TurnLimiter.Admission admission) {
        if (admission instanceof TurnLimiter.Admission.RateLimited limited) {
            return ChatStreamEvent.error(AssistantResponseCode.RATE_LIMITED.getCode(), limited.message());
        }

        return ChatStreamEvent.error(AssistantResponseCode.BUSY.getCode(), AssistantResponseCode.BUSY.getMessage());
    }

    private void streamAnswer(OutputStream output, Retrieval retrieval) {
        write(output, ChatStreamEvent.retrieved(retrieval.hits().size()));
        if (retrieval.hits().isEmpty()) {
            write(output, ChatStreamEvent.delta(NotFoundAnswer.TEXT));
            write(output, ChatStreamEvent.done(false, NotFoundAnswer.TEXT, List.of()));
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
        write(output, ChatStreamEvent.done(answer.found(), answer.answer(), answer.citations()));
    }

    private ChatAnswerView conclude(String text, List<ChunkHit> hits) {
        if (NotFoundAnswer.isNotFound(text)) {
            return notFound();
        }

        return new ChatAnswerView(text, true, CitationSelector.select(text, hits));
    }

    /** The best {@code limit} hits whose text differs, best first; a copy of a file already seen adds nothing. */
    static List<ChunkHit> distinct(List<ChunkHit> hits, int limit) {
        Set<String> seen = new HashSet<>();
        List<ChunkHit> kept = new ArrayList<>();
        for (ChunkHit hit : hits) {
            if (kept.size() == limit) {
                break;
            }
            if (seen.add(WHITESPACE.matcher(hit.content().strip()).replaceAll(" "))) {
                kept.add(hit);
            }
        }

        return kept;
    }

    private static ChatAnswerView notFound() {
        return new ChatAnswerView(NotFoundAnswer.TEXT, false, List.of());
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
