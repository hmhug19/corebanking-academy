package vn.com.coreacademy.learning.repository;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.search.KnowledgeSource;
import vn.com.coreacademy.common.search.SearchContributor;
import vn.com.coreacademy.learning.service.CommunityLearningAdapter;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;

/** Learning giữ nguyên quyền enrollment và nguồn revision đã ghim; không index khóa học vào Community private. */
@Repository
@RequiredArgsConstructor
public class CommunityLearningRetrieval implements KnowledgeSource {
    private final EntityManager entityManager;
    private final CommunityCourseSearchContributor contributor;
    private final CommunityLearningAdapter adapter;
    private final LearningRepository repository;
    private final LearningAccessPolicy access;

    @Override
    public String sourceType() {
        return "learning";
    }

    @Override
    public List<Excerpt> retrieve(float[] vector, String question, Long guildId, int limit, String model) {
        var part = contributor.contribute(new SearchContributor.Filter(question, null, null, null));
        var actor = access.actor();
        var scope = access.searchScope(actor);
        var query = entityManager.createNativeQuery("""
            select candidate.source_id,candidate.revision_id from (
            """ + part.sql() + """
            ) candidate join learning_hub.courses course on course.course_id=candidate.source_id
            where :learningGlobal=true or course.owner_org_id in (:learningOrgs)
              or course.owner_team_id in (:learningTeams)
              or (:learningOwn=true and course.owner_user_id=:learningUser and course.owner_org_id=:learningOrg)
              or (course.lifecycle_status='active' and exists (
                select 1 from learning_hub.course_enrollments e where e.user_id=:learningUser
                  and e.revision_id=candidate.revision_id and e.status <> 'withdrawn' and (
                    exists (select 1 from learning_hub.assignment_recipients ar
                      join learning_hub.course_assignments a on a.assignment_id=ar.assignment_id
                      where ar.enrollment_id=e.enrollment_id and ar.status='active' and a.status='active'
                        and ar.effective_start_at<=current_timestamp)
                    or (e.self_enrolled_at is not null and (course.owner_org_id=:learningOrg or exists (
                      select 1 from learning_hub.course_shares s where s.course_id=course.course_id
                        and s.status='active' and s.can_self_enroll and (s.org_id=:learningOrg
                          or (s.include_descendants and s.org_id in (:learningAncestors)))))))))
            order by candidate.score desc,candidate.source_id
            """);
        part.parameters().forEach(query::setParameter);
        query.setMaxResults(limit);
        List<?> rows = query.getResultList();
        return rows.stream().map(Object[].class::cast).map(row -> resolve(new Pointer(sourceType(),
            ((Number) row[0]).longValue(), ((Number) row[1]).longValue(), null))).filter(Objects::nonNull).toList();
    }

    @Override
    public Excerpt resolve(Pointer pointer) {
        try {
            var context = adapter.readable(pointer.revisionId());
            if (pointer.sourceId() != null && !Objects.equals(context.courseId(), pointer.sourceId())
                || !"published".equals(context.state())) {
                return null;
            }
            String text = repository.blocks(context.revisionId()).stream()
                .map(block -> block.getTitle() + "\n" + (block.getBody() == null ? "" : block.getBody()))
                .collect(Collectors.joining("\n"));
            text = context.title() + "\n" + text;
            return new Excerpt(new Pointer(sourceType(), context.courseId(), context.revisionId(), null), context.title(),
                text.substring(0, Math.min(2000, text.length())), "/learning/courses/" + context.courseId()
                    + "?revisionId=" + context.revisionId(), Map.of("courseRevisionId", context.revisionId()), 1);
        } catch (BusinessException | AccessDeniedException exception) {
            return null;
        }
    }
}
