package vn.com.coreacademy.assistant.drafting;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.Unit;
import vn.com.coreacademy.identity.service.LearningIdentityService;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;

/**
 * The units and teams the step 4 proposal may name: those of the course's owner unit that the author may assign to
 * now, by the same scope rule the assignment itself applies ({@link LearningAccessPolicy#canManage}). The worker has no
 * signed-in user, so the author is read from the session's owner; an author who lost their role gets no targets.
 */
@Component
@RequiredArgsConstructor
public class DraftingTargets {
    private final CourseDraftingRepository drafting;
    private final LearningIdentityService identity;
    private final LearningAccessPolicy access;

    /**
     * A unit or team the author may assign the course to; {@code withDescendants} says whether the author may also
     * include every unit below it.
     */
    public record Target(String targetType, long id, String name, boolean withDescendants) {
    }

    public List<Target> assignable(String ownerUserId, long ownerOrgId, int limit) {
        Actor actor = identity.actor(ownerUserId);
        List<Unit> units = drafting.units(ownerOrgId, limit);
        Set<Long> manageable = new HashSet<>();
        units.stream().filter(unit -> "org".equals(unit.targetType()))
            .filter(unit -> access.canManage(actor, unit.id(), null, null)).forEach(unit -> manageable.add(unit.id()));

        return units.stream().filter(unit -> "org".equals(unit.targetType()) ? manageable.contains(unit.id())
                : access.canManage(actor, unit.orgId(), unit.id(), null))
            .map(unit -> new Target(unit.targetType(), unit.id(), unit.name(), "org".equals(unit.targetType())
                && manageable.containsAll(identity.activeOrgIds(unit.id(), true))))
            .toList();
    }
}
