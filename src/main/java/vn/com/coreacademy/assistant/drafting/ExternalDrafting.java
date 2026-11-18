package vn.com.coreacademy.assistant.drafting;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.assistant.course.GenerationSchemas;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.Candidate;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.Choice;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.DraftingStepRow;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.SessionRow;
import vn.com.coreacademy.assistant.drafting.DraftingStepProposer.Proposal;
import vn.com.coreacademy.assistant.drafting.DraftingViews.SessionView;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;

/**
 * What a Trợ lý ngoài does in its own drafting sessions besides what the web page does: read the lists a step's
 * proposal picks ids from (the same lists the in-house assistant is given), propose the waiting step, and read back what
 * the author confirmed. Ids and names only; nothing stored in courses, lessons or documents.
 */
@Component
@RequiredArgsConstructor
public class ExternalDrafting {
    /** At most this many schema problems are named in one refusal. */
    private static final int PROBLEMS_SHOWN = 5;

    private final CourseDraftingService sessions;
    private final CourseDraftingRepository drafting;
    private final DraftingTargets targets;
    private final DraftingPublishCheck publishCheck;
    private final LearningAccessPolicy access;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    /** A programme or module: step 1's {@code programId} / {@code moduleId} (a module of the programme's field). */
    public record CategoryOption(long id, String name, long fieldId) {
    }

    /** A competency: step 3's {@code competencyId}. */
    public record CompetencyOption(long id, String name) {
    }

    /** A unit ({@code org}) or team ({@code team}) step 4 may target by {@code key}. */
    public record AudienceOption(String key, String type, String name, boolean canIncludeSubunits) {
    }

    public record ReferenceData(List<CategoryOption> programmes, List<CategoryOption> modules,
                                List<CompetencyOption> competencies, List<AudienceOption> audience,
                                List<String> levels, List<String> requirements) {
    }

    /**
     * {@code propose_step} refused: an English message for the model naming the step's state or the proposal's field,
     * never stored content.
     */
    public static final class ProposalRefused extends RuntimeException {
        ProposalRefused(String message) {
            super(message);
        }
    }

    /** The lists for the caller's sessions, which are always for the caller's own unit. */
    public ReferenceData referenceData() {
        Actor actor = access.actor();
        long ownerOrgId = sessions.externalOwnerOrg(actor);
        int limit = CourseDraftingProposer.CANDIDATES;

        return new ReferenceData(categories(drafting.programmes(ownerOrgId, limit)),
            categories(drafting.modules(ownerOrgId, limit)),
            drafting.competencies(limit).stream().map(ExternalDrafting::competency).toList(),
            targets.assignable(actor.person().id(), ownerOrgId, limit).stream()
                .map(target -> new AudienceOption(CourseDraftingProposer.targetKey(target), target.targetType(),
                    target.name(), target.withDescendants()))
                .toList(),
            GenerationSchemas.LEVELS, GenerationSchemas.REQUIREMENTS);
    }

    /**
     * {@code propose_step}: the waiting current step of one of the caller's Trợ lý ngoài sessions holds
     * {@code proposal}, checked with the step's generation schema and the same id lists as the in-house assistant's
     * answer, and turned into the proposal the page shows the same way. Unlike the in-house answer, an id outside the
     * lists is refused by name rather than dropped, so the Trợ lý ngoài can pick again. Lesson content is never part
     * of it: the outline's schema has no field for it.
     */
    public SessionView propose(long sessionId, int step, JsonNode proposal) {
        Actor actor = access.actor();
        long ownerOrgId = sessions.externalOwnerOrg(actor);
        SessionRow session = sessions.ownedExternal(sessionId);
        requireWaiting(session, step);
        if (!(proposal instanceof ObjectNode object)) {
            throw new ProposalRefused("proposal must be an object with the fields of step " + step + ".");
        }
        Proposal checked = switch (step) {
            case DraftingStates.BASICS_STEP -> basics(object.deepCopy(), ownerOrgId);
            case DraftingStates.OUTLINE_STEP -> outline(object);
            case DraftingStates.RULES_STEP -> rules(object);
            default -> audience(object, session, actor, ownerOrgId);
        };

        transaction.executeWithoutResult(status -> {
            SessionRow locked = drafting.lockSession(sessionId).orElseThrow();
            requireWaiting(locked, step);
            if (!drafting.storeExternalProposal(sessionId, step, write(checked.proposal()), write(checked.notes()))) {
                throw new ProposalRefused("Step " + step + " no longer waits for a proposal.");
            }
            // As with the worker's proposal: the outline and questions are AI content on the page from now on.
            if ((step == DraftingStates.OUTLINE_STEP || step == DraftingStates.RULES_STEP)
                && locked.revisionId() != null) {
                drafting.markAiGenerated(locked.revisionId());
            }
            drafting.touchSession(sessionId);
        });

        return sessions.getExternal(sessionId);
    }

    /** Step number to what the author confirmed there, for the caller's own Trợ lý ngoài session. */
    public Map<Integer, JsonNode> confirmed(long sessionId) {
        sessions.ownedExternal(sessionId);
        Map<Integer, JsonNode> values = new LinkedHashMap<>();
        drafting.confirmedValues(sessionId).forEach((step, text) -> values.put(step, read(text)));
        return values;
    }

    // ---- the step's state ------------------------------------------------------------------------------------------

    private void requireWaiting(SessionRow session, int step) {
        if (step < DraftingStates.FIRST_STEP || step > DraftingStates.LAST_STEP) {
            throw new ProposalRefused("step must be 1 to 4.");
        }
        if (!DraftingStates.ACTIVE.equals(session.status())) {
            throw new ProposalRefused("The session is " + session.status() + ": nothing more to propose.");
        }
        DraftingStepRow row = drafting.steps(session.id()).stream().filter(item -> item.stepNo() == step)
            .findFirst().orElse(null);
        if (row != null && !DraftingStates.PROPOSING.equals(row.state())) {
            throw new ProposalRefused("Step " + step + " is " + row.state() + ", not waiting for a proposal. "
                + whatNow(row.state()));
        }
        if (row == null || session.currentStep() != step) {
            throw new ProposalRefused("Step " + step + " is not the current step (step " + session.currentStep()
                + " is). Propose only the step get_drafting_session gives as waitingStep.");
        }
    }

    private static String whatNow(String state) {
        return switch (state) {
            case DraftingStates.PROPOSED -> "The author reviews it on the page; they press \"Đề xuất lại\" to ask "
                + "for another one.";
            case DraftingStates.CONFIRMED -> "Read the session for the values the author confirmed.";
            default -> "The author fills this step by hand.";
        };
    }

    // ---- the proposal of each step ---------------------------------------------------------------------------------

    private Proposal basics(ObjectNode answer, long ownerOrgId) {
        if (!answer.has("unsure")) {
            answer.putArray("unsure");
        }
        requireSchema(1, GenerationSchemas.draftBasics(answer));
        int limit = CourseDraftingProposer.CANDIDATES;
        List<Candidate> programmes = drafting.programmes(ownerOrgId, limit);
        List<Candidate> modules = drafting.modules(ownerOrgId, limit);
        Map<Long, Candidate> programmeById = programmes.stream()
            .collect(Collectors.toMap(Candidate::id, Function.identity(), (a, b) -> a));
        Map<Long, Candidate> moduleById = modules.stream()
            .collect(Collectors.toMap(Candidate::id, Function.identity(), (a, b) -> a));

        Long programId = id(answer, "programId");
        Long moduleId = id(answer, "moduleId");
        Candidate programme = programId == null ? null : programmeById.get(programId);
        if (programId != null && programme == null) {
            throw new ProposalRefused("proposal.programId is not one of the programmes get_reference_data lists. "
                + "Pick one of them or leave it out for the author to choose.");
        }
        if (moduleId != null) {
            Candidate module = moduleById.get(moduleId);
            if (module == null) {
                throw new ProposalRefused("proposal.moduleId is not one of the modules get_reference_data lists. "
                    + "Pick one of them or leave it out for the author to choose.");
            }
            if (programme == null || module.fieldId() != programme.fieldId()) {
                throw new ProposalRefused("proposal.moduleId must be a module of the programme's fieldId; give "
                    + "proposal.programId too, or leave the module out.");
            }
        }

        return CourseDraftingProposer.checkedBasics(answer, json.createObjectNode(), programmes, modules,
            CourseDraftingProposer.freeCode(drafting), null);
    }

    private Proposal outline(ObjectNode answer) {
        requireSchema(2, GenerationSchemas.draftOutline(answer));
        Map<String, String> sections = new LinkedHashMap<>();
        answer.path("chapters").forEach(chapter -> chapter.path("lessons").forEach(lesson -> section(lesson,
            sections)));

        return CourseDraftingProposer.checkedOutline(answer, sections, null);
    }

    private Proposal rules(ObjectNode answer) {
        requireSchema(3, GenerationSchemas.draftRules(answer));
        List<Choice> competencies = drafting.competencies(CourseDraftingProposer.CANDIDATES);
        Long competencyId = id(answer, "competencyId");
        if (competencyId != null && competencies.stream().noneMatch(choice -> choice.id() == competencyId)) {
            throw new ProposalRefused("proposal.competencyId is not one of the competencies get_reference_data "
                + "lists. Pick one of them or leave it out for the author to choose.");
        }
        Map<String, String> sections = new LinkedHashMap<>();
        answer.path("questions").forEach(question -> section(question, sections));

        return CourseDraftingProposer.checkedRules(answer, sections, competencies, null);
    }

    private Proposal audience(ObjectNode answer, SessionRow session, Actor actor, long ownerOrgId) {
        requireSchema(4, GenerationSchemas.draftAudience(answer));
        List<DraftingTargets.Target> assignable = targets.assignable(actor.person().id(), ownerOrgId,
            CourseDraftingProposer.CANDIDATES);
        Set<String> keys = assignable.stream().map(CourseDraftingProposer::targetKey).collect(Collectors.toSet());
        JsonNode picks = answer.path("targets");
        for (int i = 0; i < picks.size(); i++) {
            if (!keys.contains(picks.get(i).path("key").asText().strip())) {
                throw new ProposalRefused("proposal.targets[" + i + "].key is not one of the audience keys "
                    + "get_reference_data lists.");
            }
        }
        List<String> blockers = session.revisionId() == null ? List.of() : publishCheck.blockers(session.revisionId());

        return CourseDraftingProposer.checkedAudience(picks, assignable, blockers);
    }

    /** The source part a lesson or question names stands for itself: the server never sees the document. */
    private static void section(JsonNode item, Map<String, String> sections) {
        String label = item.path("section").asText().strip();
        sections.put(label, label);
    }

    private static void requireSchema(int step, List<String> problems) {
        if (!problems.isEmpty()) {
            throw new ProposalRefused("The proposal for step " + step + " is not valid: "
                + String.join("; ", problems.stream().distinct().limit(PROBLEMS_SHOWN).toList()) + ".");
        }
    }

    private static Long id(JsonNode answer, String field) {
        JsonNode value = answer.path(field);
        return value.isIntegralNumber() ? value.asLong() : null;
    }

    private static List<CategoryOption> categories(List<Candidate> candidates) {
        return candidates.stream().map(item -> new CategoryOption(item.id(), item.name(), item.fieldId())).toList();
    }

    private static CompetencyOption competency(Choice choice) {
        return new CompetencyOption(choice.id(), choice.name());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private JsonNode read(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
