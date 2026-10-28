package vn.com.coreacademy.community.repository;

import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.community.entity.LearningLink;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Repository
@RequiredArgsConstructor
public class CommunityLearningRepository {
    private final CommunityRepository repository;
    private final EntityManager entityManager;

    public List<LearningLink> links(Long revisionId) {
        return repository.list("""
            select l from LearningLink l where l.courseRevisionId = :revision and l.status = :status
            order by l.sortNo, l.learningLinkId
            """, LearningLink.class, Map.of("revision", revisionId, "status", "active"));
    }

    public Page<LearningLink> visible(Long revisionId, Long lessonId, Actor actor, int page, int size) {
        var query = new NativeSearchQuery("""
            from learning_hub.community_learning_links l
            join learning_hub.community_content_revisions r on r.revision_id = l.content_revision_id
            """);
        query.and("l.course_revision_id = :revision and l.status = :visibleActive");
        query.bind("revision", revisionId);
        if (lessonId != null) {
            query.and("l.lesson_id = :lesson");
            query.bind("lesson", lessonId);
        }
        query.and("""
            r.deleted_at is null and r.revision_status in ('published', 'superseded') and r.content_id in (
            """ + CommunityVisibleSql.IDS + ")");
        CommunityVisibleSql.bind(query, actor);
        return query.page(entityManager, "select l.*\n", LearningLink.class,
            PageRequest.of(page, size, Sort.by("sortNo", "id")),
            Map.of("sortNo", "l.sort_no", "id", "l.learning_link_id"));
    }
}
