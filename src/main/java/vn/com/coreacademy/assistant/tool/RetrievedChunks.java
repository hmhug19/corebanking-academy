package vn.com.coreacademy.assistant.tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import vn.com.coreacademy.assistant.config.AssistantBudget;
import vn.com.coreacademy.assistant.conversation.TurnRecord;
import vn.com.coreacademy.knowledge.chunking.TokenEstimator;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.service.CitationRule;

/**
 * The chunks retrieved in one turn, numbered [1]…[n] in the order they were first seen. The numbers are the ones the
 * model reads in the tool results, so they keep counting across several searches and a chunk found twice keeps its
 * first number. It also turns the model's final text into the answer and citations the app shows.
 */
public final class RetrievedChunks {
    /** A chunk first seen in the latest search, with its number. */
    public record Added(int n, ChunkHit hit) {
    }

    private final List<ChunkHit> hits = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private final int budgetTokens;
    private int usedTokens;
    private boolean dropped;
    private final List<TurnRecord.Search> trace = new ArrayList<>();

    public RetrievedChunks() {
        this(AssistantBudget.defaults().retrieved());
    }

    public RetrievedChunks(int budgetTokens) {
        this.budgetTokens = budgetTokens;
    }

    /**
     * Adds the chunks not seen before in this turn and returns them with their new numbers. A chunk that would take
     * the turn past its retrieved-content budget is left out whole, and so are the lower-ranked ones after it.
     */
    public List<Added> add(List<ChunkHit> found) {
        List<Added> added = new ArrayList<>();
        for (ChunkHit hit : found) {
            if (seen.contains(hit.key())) {
                continue;
            }
            int size = TokenEstimator.estimate(hit.content());
            if (usedTokens + size > budgetTokens) {
                dropped = true;
                break;
            }
            seen.add(hit.key());
            usedTokens += size;
            hits.add(hit);
            added.add(new Added(hits.size(), hit));
        }

        return added;
    }

    /** As {@link #add(List)}, and notes the search in the turn's retrieval trace (ids and numbers only). */
    public List<Added> add(String tool, List<ChunkHit> found) {
        List<Added> added = add(found);
        trace.add(new TurnRecord.Search(tool, found.size(), added.stream().map(chunk -> new TurnRecord.Given(
            chunk.hit().lesson() == null ? "document" : "lesson", chunk.hit().chunkId(), chunk.n(),
            chunk.hit().similarity())).toList()));

        return added;
    }

    /** The searches of the turn so far, in order. */
    public List<TurnRecord.Search> trace() {
        return List.copyOf(trace);
    }

    /** True when a chunk was left out of the turn because it did not fit the budget. */
    public boolean droppedForBudget() {
        return dropped;
    }

    public boolean isEmpty() {
        return hits.isEmpty();
    }

    /** The turn's answer under the shared {@link CitationRule}: sources are only the chunks the answer marks. */
    public FinishedAnswer finish(String answer) {
        CitationRule.Outcome outcome = CitationRule.apply(answer, hits);

        return new FinishedAnswer(outcome.answer(), outcome.citations(), outcome.grounded(), outcome.checkedMarkers(),
            outcome.removedMarkers());
    }
}
