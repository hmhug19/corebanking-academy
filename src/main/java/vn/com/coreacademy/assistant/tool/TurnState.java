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
    private TurnLinks links;

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

    /** The searches of this turn so far; empty when the turn searched nothing. */
    public java.util.List<vn.com.coreacademy.assistant.conversation.TurnRecord.Search> retrievalTrace() {
        Object chunks = state.get(RetrievedChunks.class);
        return chunks == null ? java.util.List.of() : ((RetrievedChunks) chunks).trace();
    }

    /**
     * Shapes the final answer from the turn's retrieved chunks (citations, fixed sentences). The tools that retrieve
     * chunks all finish from the same chunks, so the latest one stands for all of them.
     */
    public void finishWith(TurnFinisher finisher) {
        this.finisher = finisher;
    }

    /** Links the server adds after the answer (Community posts); they never replace the sources of the chunks. */
    public void linkWith(TurnLinks links) {
        this.links = links;
    }

    /**
     * The finisher of the turn: chunks alone, links alone, or both, where the answer keeps the sources of its chunks
     * and the links follow it.
     */
    public Optional<TurnFinisher> finisher() {
        if (links == null) {
            return Optional.ofNullable(finisher);
        }
        if (finisher == null) {
            return Optional.of(links::alone);
        }
        TurnFinisher chunks = finisher;
        TurnLinks after = links;

        return Optional.of(answer -> {
            FinishedAnswer finished = chunks.finish(answer);
            String extra = after.appended();
            return extra.isEmpty() ? finished : new FinishedAnswer(finished.answer() + "\n\n" + extra,
                finished.citations(), finished.grounded(), finished.checkedMarkers(), finished.removedMarkers());
        });
    }

    /** Server-built links of one turn. */
    public interface TurnLinks {
        /** The whole answer when the turn retrieved no chunks: the model's text is only a lead to the links. */
        FinishedAnswer alone(String answer);

        /** The links to put after an answer that has its own sources; empty when there are none to show. */
        String appended();
    }
}
