package vn.com.coreacademy.assistant.tool;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import vn.com.coreacademy.assistant.config.AssistantBudget;

/**
 * Scratch space of one user turn, shared by the tools called in it. It lives and dies with the stream body and is
 * touched by one thread, so it needs no locking.
 */
public final class TurnState {
    private final Map<Class<?>, Object> state = new HashMap<>();
    private final int retrievedBudget;
    private TurnFinisher finisher;

    public TurnState() {
        this(AssistantBudget.defaults());
    }

    public TurnState(AssistantBudget budget) {
        this.retrievedBudget = budget.retrieved();
    }

    /** The chunks retrieved in this turn, kept within the retrieved-content budget. */
    public RetrievedChunks chunks() {
        return state(RetrievedChunks.class, () -> new RetrievedChunks(retrievedBudget));
    }

    /** The turn's value of {@code type}, created on first use. */
    public <T> T state(Class<T> type, Supplier<T> create) {
        return type.cast(state.computeIfAbsent(type, key -> create.get()));
    }

    /** Shapes the final answer; a tool that needs to (citations, fixed sentences) registers it. */
    public void finishWith(TurnFinisher finisher) {
        this.finisher = finisher;
    }

    public Optional<TurnFinisher> finisher() {
        return Optional.ofNullable(finisher);
    }
}
