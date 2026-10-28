package vn.com.coreacademy.community.repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Repository
@RequiredArgsConstructor
public class CommunityTagReadRepository {
    private final EntityManager entityManager;

    public Map<Long, Long> usage(List<Long> tags, Actor actor) {
        if (tags.isEmpty()) {
            return Map.of();
        }
        var query = entityManager.createNativeQuery("""
            select ct.tag_id,count(distinct ct.content_id) from learning_hub.community_content_tags ct
            where ct.tag_id in (:tags) and ct.content_id in (
            """ + CommunityVisibleSql.IDS + ") group by ct.tag_id");
        CommunityVisibleSql.parameters(actor).forEach(query::setParameter);
        query.setParameter("tags", tags);
        List<?> rows = query.getResultList();
        return rows.stream().map(Object[].class::cast).collect(Collectors.toMap(row -> ((Number) row[0]).longValue(),
            row -> ((Number) row[1]).longValue()));
    }
}
