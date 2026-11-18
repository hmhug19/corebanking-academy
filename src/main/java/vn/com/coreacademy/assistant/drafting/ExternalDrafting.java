package vn.com.coreacademy.assistant.drafting;

import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.course.GenerationSchemas;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.Candidate;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.Choice;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;

/**
 * What a Trợ lý ngoài may read besides its own sessions: the lists a step's proposal picks ids from, the same lists the
 * in-house assistant is given. Ids and names only; nothing stored in courses, lessons or documents.
 */
@Component
@RequiredArgsConstructor
public class ExternalDrafting {
    private final CourseDraftingService sessions;
    private final CourseDraftingRepository drafting;
    private final DraftingTargets targets;
    private final LearningAccessPolicy access;

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

    private static List<CategoryOption> categories(List<Candidate> candidates) {
        return candidates.stream().map(item -> new CategoryOption(item.id(), item.name(), item.fieldId())).toList();
    }

    private static CompetencyOption competency(Choice choice) {
        return new CompetencyOption(choice.id(), choice.name());
    }
}
