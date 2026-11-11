package vn.com.coreacademy.assistant.metrics;

import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.conversation.TurnRecord;
import vn.com.coreacademy.knowledge.ai.Usage;

/**
 * The meters of both assistants. Every tag is one of a small fixed set (a surface, a status, a tool or skill the server
 * registered, an error code, a feedback reason): never a user id, a conversation id, a question or an answer, so the
 * meters can be read, scraped and kept without personal data. Read them through the actuator {@code metrics} endpoint
 * (see docs/operations/ASSISTANT_METRICS.md).
 */
@Component
@RequiredArgsConstructor
public class AssistantMetrics {
    public static final String AGENT = "agent";
    public static final String KNOWLEDGE = "knowledge";
    /** The tag of a tool call whose name the server does not know, so a model inventing names cannot grow the meter. */
    public static final String EMPTY_RETRIEVAL = "empty_retrieval";
    public static final String MODEL_SAID_SO = "model_said_so";
    public static final String UNKNOWN_TOOL = "unknown";

    private final MeterRegistry registry;

    /** Surface tag of a stored turn: {@code agent} or {@code knowledge}. */
    public static String surfaceOf(String storedSurface) {
        return TurnRecord.KNOWLEDGE.equals(storedSurface) ? KNOWLEDGE : AGENT;
    }

    /** One finished turn: how long it took, and how long until the first piece of the answer. */
    public void turn(String surface, TurnRecord.Status status, Integer firstTokenMs, int durationMs) {
        Timer.builder("assistant.turn.duration").description("Time from the question to the end of the turn")
            .tag("surface", surface).tag("status", status.name().toLowerCase(java.util.Locale.ROOT))
            .register(registry).record(Duration.ofMillis(durationMs));
        if (firstTokenMs != null) {
            Timer.builder("assistant.turn.first_token").description("Time from the question to the first text")
                .tag("surface", surface).register(registry).record(Duration.ofMillis(firstTokenMs));
        }
    }

    /** The model calls one agent turn took. */
    public void steps(String surface, int steps) {
        DistributionSummary.builder("assistant.turn.steps").description("Model calls in one turn")
            .tag("surface", surface).register(registry).record(steps);
    }

    /** One course generation step: how long it ran, by step kind and outcome (done, failed, waiting). */
    public void generationStep(String kind, String outcome, long durationMs) {
        Timer.builder("assistant.generation.step.duration").description("Time one course generation step took")
            .tag("kind", kind).tag("outcome", outcome).register(registry).record(Duration.ofMillis(durationMs));
    }

    /**
     * The token counts of one model call, by surface, and a count of the calls whose prompt filled more than 90% of
     * {@code numCtx}, where Ollama starts cutting the prompt without saying so. Calls that reported nothing are skipped.
     */
    public void modelCall(String surface, Usage usage, int numCtx) {
        if (usage == null || usage.promptTokens() <= 0) {
            return;
        }
        DistributionSummary.builder("assistant.model.prompt_tokens").description("Prompt tokens of one model call")
            .tag("surface", surface).register(registry).record(usage.promptTokens());
        DistributionSummary.builder("assistant.model.answer_tokens").description("Answer tokens of one model call")
            .tag("surface", surface).register(registry).record(usage.answerTokens());
        if (usage.promptTokens() > numCtx * 0.9) {
            count("assistant.model.near_context_limit", "Model calls whose prompt used over 90% of num_ctx", "surface",
                surface);
        }
    }

    /** One search of a turn: how many chunks it found, and the best similarity of those given to the model. */
    public void search(String surface, TurnRecord.Search search) {
        DistributionSummary.builder("assistant.retrieval.hits").description("Candidates found by one search")
            .tag("surface", surface).tag("tool", search.tool()).register(registry).record(search.candidates());
        if (search.candidates() == 0) {
            Counter.builder("assistant.retrieval.empty").description("Searches that returned nothing")
                .tag("surface", surface).tag("tool", search.tool()).register(registry).increment();
            return;
        }
        search.given().stream().mapToDouble(TurnRecord.Given::similarity).max().ifPresent(top ->
            DistributionSummary.builder("assistant.retrieval.top_similarity")
                .description("Similarity of the best chunk given to the model, per search")
                .tag("surface", surface).tag("tool", search.tool())
                .serviceLevelObjectives(0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9).register(registry).record(top));
    }

    /** How long one embedding call took. */
    public void embed(Duration took) {
        Timer.builder("assistant.retrieval.embed").description("Time Ollama took to embed the inputs of one call")
            .register(registry).record(took);
    }

    public void toolCall(String tool) {
        count("assistant.tool.calls", "Tool calls by tool name", "tool", tool);
    }

    public void skill(String skill) {
        count("assistant.skill.turns", "Turns by skill", "skill", skill);
    }

    /** An answer that said the documents do not have it. */
    public void notFound(String surface) {
        notFound(surface, MODEL_SAID_SO);
    }

    /** {@code cause} is {@link #EMPTY_RETRIEVAL} (nothing was retrieved) or {@link #MODEL_SAID_SO} (the model gave up). */
    public void notFound(String surface, String cause) {
        Counter.builder("assistant.answers.not_found").description("Answers that found nothing in the documents")
            .tag("surface", surface).tag("cause", cause).register(registry).increment();
    }

    /** A stream that ended with an error line; {@code code} is the response code the user was shown. */
    public void error(String surface, String code) {
        Counter.builder("assistant.errors").description("Turns that ended in an error, by error code")
            .tag("surface", surface).tag("code", code).register(registry).increment();
    }

    /** A question refused before it started: {@code rate_limited} or {@code busy}. */
    public void refused(String surface, String reason) {
        Counter.builder("assistant.refusals").description("Questions refused before they started")
            .tag("surface", surface).tag("reason", reason).register(registry).increment();
    }

    /** A thumbs on an answer; {@code reason} is a feedback reason code, or null for none. */
    public void feedback(String surface, boolean helpful, String reason) {
        Counter.builder("assistant.feedback").description("Answer feedback by thumbs and reason")
            .tag("surface", surface).tag("helpful", Boolean.toString(helpful)).tag("reason", reason == null ? "none" : reason)
            .register(registry).increment();
    }

    private void count(String name, String description, String tag, String value) {
        Counter.builder(name).description(description).tag(tag, value).register(registry).increment();
    }
}
