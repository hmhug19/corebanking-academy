package vn.com.coreacademy.knowledge.repository;

import java.util.Set;

/**
 * Which documents one user may find: their own personal ones, unit documents of their unit or any unit above it, and
 * every global one. {@code orgIds} is the user's unit and its ancestors, so a document of a parent unit reaches its
 * children but never a sibling.
 */
public record DocumentVisibility(String userId, Set<Long> orgIds) {
    // A placeholder keeps "IN ()" valid when a user has no unit; no unit has this id.
    private static final Long NO_UNIT = -1L;

    public DocumentVisibility {
        orgIds = Set.copyOf(orgIds);
    }

    /** The unit ids to bind to an SQL {@code IN} list; never empty. */
    public Set<Long> orgIdsOrPlaceholder() {
        return orgIds.isEmpty() ? Set.of(NO_UNIT) : orgIds;
    }
}
