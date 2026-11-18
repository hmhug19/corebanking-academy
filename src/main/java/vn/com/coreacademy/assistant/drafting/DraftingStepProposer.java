package vn.com.coreacademy.assistant.drafting;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.DraftingStepRow;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.SessionRow;

/**
 * Does the model work of one drafting step. The worker claims the step, calls this and stores what it returns; the
 * proposer never changes a step's or a session's state itself. A failure fit to show the author is a
 * {@code GenerationException}; a source still being read is a {@code StepNotReadyException}.
 */
public interface DraftingStepProposer {
    /**
     * The step's proposal (already checked and with unknown ids dropped), the lines the chat shows the author, and the
     * source summary when this call made it ({@code null} when the session already had one).
     */
    record Proposal(JsonNode proposal, List<String> notes, JsonNode sourceSummary) {
    }

    Proposal propose(SessionRow session, DraftingStepRow step);
}
