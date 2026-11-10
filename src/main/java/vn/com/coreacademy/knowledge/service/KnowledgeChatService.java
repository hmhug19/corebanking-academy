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
import vn.com.coreacademy.assistant.conversation.Conversations;
import vn.com.coreacademy.assistant.conversation.TurnRecord;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.assistant.metrics.AssistantMetrics;
import vn.com.coreacademy.assistant.config.AssistantModels;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.assistant.skill.Suggestions;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.security.SecurityContextUtils;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaClient.Message;
import vn.com.coreacademy.knowledge.ai.OllamaException;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.dto.ChatStreamEvent;
import vn.com.coreacademy.knowledge.dto.ChatViews;
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
    private final Conversations conversations;
    private final AssistantMetrics metrics;
    private final AssistantModels models;
    private final SkillCatalog catalog;

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
                    models.groundedOptions()).strip();
            } catch (OllamaUnavailableException | OllamaException exception) {
                throw new BusinessException(KnowledgeResponseCode.AI_UNAVAILABLE);
            }

            return conclude(Suggestions.split(text).answer(), retrieval.hits());
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

    /** A stream that is not part of a conversation yet; kept for callers that do not name one. */
    public StreamingResponseBody stream(String rawQuestion) {
        return stream(rawQuestion, null);
    }

    /**
     * Retrieval runs now, on the request thread, so refusals are still ordinary envelopes. Generation runs inside the
     * returned body: one JSON line per event, flushed at once. The first line names the conversation; the earlier
     * turns of that conversation go to the model as plain history, retrieval still uses the new question alone.
     */
    public StreamingResponseBody stream(String rawQuestion, Long conversationId) {
        String userId = SecurityContextUtils.getUserId();
        conversations.requireOwned(userId, conversationId);
        Retrieval retrieval = retrieve(rawQuestion);
        return output -> {
            TurnLimiter.Admission admission = limiter.admit(userId);
            if (!(admission instanceof TurnLimiter.Admission.Started started)) {
                metrics.refused(AssistantMetrics.KNOWLEDGE,
                    admission instanceof TurnLimiter.Admission.RateLimited ? "rate_limited" : "busy");
                write(output, refusal(admission));
                return;
            }
            try (TurnLimiter.Turn ignored = started.turn()) {
                Conversations.Opened opened = conversations.open(userId, conversationId, retrieval.question());
                write(output, ChatStreamEvent.conversation(opened.conversationId()));
                metrics.skill(SkillCatalog.DEFAULT);
                TurnLog turnLog = new TurnLog(opened.conversationId(), retrieval.question(), catalog.version());
                try {
                    streamAnswer(output, retrieval, opened.history(), turnLog);
                } finally {
                    store(turnLog);
                }
            }
        };
    }

    private static ChatStreamEvent refusal(TurnLimiter.Admission admission) {
        if (admission instanceof TurnLimiter.Admission.RateLimited limited) {
            return ChatStreamEvent.error(AssistantResponseCode.RATE_LIMITED.getCode(), limited.message());
        }

        return ChatStreamEvent.error(AssistantResponseCode.BUSY.getCode(), AssistantResponseCode.BUSY.getMessage());
    }

    private void streamAnswer(OutputStream output, Retrieval retrieval, List<Conversations.Exchange> history,
                              TurnLog turnLog) {
        emit(output, turnLog, ChatStreamEvent.retrieved(retrieval.hits().size()));
        if (retrieval.hits().isEmpty()) {
            emit(output, turnLog, ChatStreamEvent.delta(NotFoundAnswer.TEXT));
            emit(output, turnLog, ChatStreamEvent.done(false, NotFoundAnswer.TEXT, List.of()));
            return;
        }

        StringBuilder text = new StringBuilder();
        Suggestions.Filter filter = new Suggestions.Filter();
        try {
            ollama.chatStream(prompts.build(retrieval.question(), retrieval.hits(), history), models.groundedOptions(),
                delta -> {
                    text.append(delta);
                    String shown = filter.accept(delta);
                    if (!shown.isEmpty()) {
                        emit(output, turnLog, ChatStreamEvent.delta(shown));
                    }
                });
        } catch (UncheckedIOException exception) {
            turnLog.stopped();
            throw exception;
        } catch (OllamaUnavailableException | OllamaException exception) {
            emit(output, turnLog, ChatStreamEvent.error(KnowledgeResponseCode.AI_UNAVAILABLE.getCode(),
                KnowledgeResponseCode.AI_UNAVAILABLE.getMessage()));
            return;
        }

        Suggestions.Split split = Suggestions.split(text.toString().strip());
        ChatAnswerView answer = conclude(split.answer(), retrieval.hits());
        emit(output, turnLog, ChatStreamEvent.done(answer.found(), answer.answer(), answer.citations(),
            answer.found() ? split.suggestions() : List.of()));
    }

    private void emit(OutputStream output, TurnLog turnLog, ChatStreamEvent event) {
        turnLog.observe(event);
        if (event instanceof ChatStreamEvent.Error error) {
            metrics.error(AssistantMetrics.KNOWLEDGE, error.messageCode());
        }
        if (event instanceof ChatStreamEvent.Done done) {
            if (!done.found()) {
                metrics.notFound(AssistantMetrics.KNOWLEDGE);
            }
            // The turn is stored before its last line goes out, so the line can name the turn the user may rate.
            Long turnId = store(turnLog);
            write(output, turnId == null ? done : done.withTurnId(turnId));
            return;
        }
        write(output, event);
    }

    /**
     * Stores the turn once, without ever failing the stream: the user already has the answer. Returns the stored id,
     * or null when the turn could not be stored.
     */
    private Long store(TurnLog turnLog) {
        if (turnLog.attempted) {
            return turnLog.storedId;
        }
        turnLog.attempted = true;
        TurnRecord record = turnLog.record();
        // Counted whether or not the store works: the meters describe what the user went through.
        metrics.turn(AssistantMetrics.KNOWLEDGE, record.status(), record.firstTokenMs(), record.durationMs());
        try {
            turnLog.storedId = conversations.record(turnLog.conversationId, record);
        } catch (RuntimeException exception) {
            // The message could quote the user's text; log the type only.
            log.error("Knowledge turn not stored conversationId={} type={}", turnLog.conversationId,
                exception.getClass().getName());
        }

        return turnLog.storedId;
    }

    /** What the events of one turn add up to, for the stored turn. */
    private static final class TurnLog {
        private final String question;
        private final long conversationId;
        private final String promptVersion;
        private boolean attempted;
        private Long storedId;
        private final long started = System.nanoTime();
        private TurnRecord.Status status = TurnRecord.Status.STOPPED;
        private String answer;
        private List<ChatViews.CitationView> citations = List.of();
        private Integer firstTokenMs;

        TurnLog(long conversationId, String question, String promptVersion) {
            this.conversationId = conversationId;
            this.question = question;
            this.promptVersion = promptVersion;
        }

        void stopped() {
            if (status != TurnRecord.Status.DONE && status != TurnRecord.Status.ERROR) {
                status = TurnRecord.Status.STOPPED;
            }
        }

        void observe(ChatStreamEvent event) {
            if (event instanceof ChatStreamEvent.Delta && firstTokenMs == null) {
                firstTokenMs = millisSince();
            } else if (event instanceof ChatStreamEvent.Done done) {
                status = TurnRecord.Status.DONE;
                answer = done.answer();
                citations = done.citations();
            } else if (event instanceof ChatStreamEvent.Error) {
                status = TurnRecord.Status.ERROR;
            }
        }

        private int millisSince() {
            return (int) ((System.nanoTime() - started) / 1_000_000);
        }

        TurnRecord record() {
            return new TurnRecord(TurnRecord.KNOWLEDGE, question, answer, citations, List.of(), null, null, status,
                firstTokenMs, millisSince(), SkillCatalog.DEFAULT, promptVersion);
        }
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
