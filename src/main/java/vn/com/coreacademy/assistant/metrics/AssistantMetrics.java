package vn.com.coreacademy.assistant.metrics;

import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.conversation.TurnRecord;

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

    public void toolCall(String tool) {
        count("assistant.tool.calls", "Tool calls by tool name", "tool", tool);
    }

    public void skill(String skill) {
        count("assistant.skill.turns", "Turns by skill", "skill", skill);
    }

    /** An answer that said the documents do not have it. */
    public void notFound(String surface) {
        count("assistant.answers.not_found", "Answers that found nothing in the documents", "surface", surface);
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
