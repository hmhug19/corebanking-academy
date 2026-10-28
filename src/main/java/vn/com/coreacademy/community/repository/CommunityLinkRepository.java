package vn.com.coreacademy.community.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.common.persistence.NativeSearchQuery;
import vn.com.coreacademy.common.search.SearchContributor;
import vn.com.coreacademy.community.dto.LinkDtos.LinkView;
import vn.com.coreacademy.community.entity.ContentLink;
import vn.com.coreacademy.community.entity.QuestionLink;
import vn.com.coreacademy.identity.service.LearningIdentityService.Actor;

@Repository
@RequiredArgsConstructor
public class CommunityLinkRepository {
    private final CommunityRepository repository;
    private final EntityManager entityManager;
    private final List<SearchContributor> contributors;

    public List<ContentLink> editableLinks(Long contentId) {
        return repository.list("""
            select l from ContentLink l where l.sourceContentId = :content and l.relationType
              in ('related', 'summarizes', 'supersedes', 'learning_reference')
            """, ContentLink.class, Map.of("content", contentId));
    }

    public List<QuestionLink> relatedQuestions(Long contentId) {
        return repository.list("""
            select l from QuestionLink l where l.sourceQuestionId = :content and l.relationType = 'related'
            """, QuestionLink.class, Map.of("content", contentId));
    }

    public boolean supersedesCycle(Long source, Long target) {
        return Boolean.TRUE.equals(entityManager.createNativeQuery("""
            with recursive chain(id) as (
              select cast(:target as bigint)
              union select l.target_content_id from learning_hub.community_content_links l
                join chain c on c.id = l.source_content_id where l.relation_type in ('supersedes', 'follow_up')
                  and l.target_content_id is not null
            ) select exists(select 1 from chain where id = :source)
            """).setParameter("source", source).setParameter("target", target).getSingleResult());
    }

    public Page<LinkView> visible(Long contentId, String relationType, Actor actor, int page, int size) {
        var targets = targets(actor);
        String union = targets.stream().map(part -> "(" + part.sql() + ")").collect(Collectors.joining(" union all "));
        var query = new NativeSearchQuery("""
            from (
              select l.link_id, l.source_content_id, l.relation_type,
                case when l.target_content_id is not null then 'community' when l.target_course_id is not null
                  then 'learning' else 'knowledge' end target_type,
                coalesce(l.target_content_id, l.target_course_id, l.target_knowledge_document_id) target_id
              from learning_hub.community_content_links l
              union all select q.question_link_id, q.source_question_id, q.relation_type,
                'community' target_type, q.target_question_id from learning_hub.community_question_links q
            ) links join (
            """ + union + """
            ) target on target.source_type = links.target_type and target.source_id = links.target_id
            """);
        query.and("links.source_content_id = :linkSource");
        query.bind("linkSource", contentId);
        if (relationType != null) {
            query.and("links.relation_type = :linkRelation");
            query.bind("linkRelation", relationType);
        }
        targets.forEach(part -> part.parameters().forEach(query::bind));
        return query.page(entityManager, """
            select links.link_id, links.source_content_id, links.relation_type, links.target_type,
              links.target_id, target.revision_id, target.title, target.url
            """, LinkView.class, PageRequest.of(page, size, Sort.by("id", "type")),
            Map.of("id", "links.link_id", "type", "links.target_type"));
    }

    public boolean readableTarget(String sourceType, Long targetId, Actor actor) {
        for (var part : targets(actor)) {
            if (!sourceType.equals(part.sourceType())) {
                continue;
            }
            var query = entityManager.createNativeQuery("select count(*) from (" + part.sql()
                + ") target where target.source_id = :linkTarget");
            part.parameters().forEach(query::setParameter);
            query.setParameter("linkTarget", targetId);
            return ((Number) query.getSingleResult()).longValue() > 0;
        }
        return false;
    }

    private List<SearchContributor.Part> targets(Actor actor) {
        List<SearchContributor.Part> parts = new ArrayList<>();
        parts.add(new SearchContributor.Part("community", """
            select 'community'::text source_type, c.content_id source_id, r.revision_id,
              r.title, ''::text snippet, 0::double precision score, extract(epoch from c.update_time) updated_epoch,
              '/community/contents/' || c.content_id url, null::bigint answer_id
            from learning_hub.community_contents c join learning_hub.community_content_revisions r
              on r.revision_id = c.current_published_revision_id where c.content_id in (
            """ + CommunityVisibleSql.IDS + ")", CommunityVisibleSql.parameters(actor)));
        contributors.stream().filter(contributor -> !"community".equals(contributor.sourceType()))
            .forEach(contributor -> parts.add(contributor.contribute(new SearchContributor.Filter("", null, null,
                null))));
        return parts;
    }
}
