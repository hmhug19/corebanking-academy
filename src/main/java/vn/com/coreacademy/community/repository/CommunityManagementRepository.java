package vn.com.coreacademy.community.repository;

import java.util.List;
import java.util.Map;

import jakarta.persistence.EntityManager;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.community.entity.ContentGuild;
import vn.com.coreacademy.community.entity.QuestionLink;

@Repository
@RequiredArgsConstructor
public class CommunityManagementRepository {
    private final EntityManager entityManager;
    private final CommunityRepository repository;

    public List<QuestionLink> duplicateLinks(Long questionId) {
        return repository.list("""
            select l from QuestionLink l where l.sourceQuestionId = :question and l.relationType = :type
            """, QuestionLink.class, Map.of("question", questionId, "type", "duplicate"));
    }

    public boolean duplicateCycle(Long source, Long target) {
        return ((Number) entityManager.createNativeQuery("""
            with recursive chain(id) as (
              select cast(:target as bigint)
              union
              select l.target_question_id from learning_hub.community_question_links l
              join chain p on p.id = l.source_question_id where l.relation_type = :type)
            select count(*) from chain where id = :source
            """).setParameter("source", source).setParameter("target", target)
            .setParameter("type", "duplicate").getSingleResult()).longValue() > 0;
    }

    public List<ContentGuild> pins(Long guildId, Long contentId) {
        return repository.list("""
            select g from ContentGuild g where g.guildId = :guild and g.contentId = :content
            """, ContentGuild.class, Map.of("guild", guildId, "content", contentId));
    }

    /** Hai grain trùng giữ dòng target; các tham chiếu còn lại di chuyển trong cùng transaction/khóa ghi. */
    public void mergeTag(Long source, Long target, String actor) {
        execute("""
            update learning_hub.community_contents set row_version = row_version + 1,
              update_time = now(), updated_by = :actor
            where content_id in (select content_id from learning_hub.community_content_tags where tag_id = :source)
            """, Map.of("source", source, "actor", actor));
        execute("""
            delete from learning_hub.community_content_tags s where s.tag_id = :source and exists (
              select 1 from learning_hub.community_content_tags t
              where t.tag_id = :target and t.content_id = s.content_id)
            """, Map.of("source", source, "target", target));
        execute("""
            update learning_hub.community_content_tags set tag_id = :target, updated_by = :actor,
              update_time = now(), row_version = row_version + 1 where tag_id = :source
            """, Map.of("source", source, "target", target, "actor", actor));
        execute("""
            delete from learning_hub.community_subscriptions s where s.tag_id = :source and exists (
              select 1 from learning_hub.community_subscriptions t where t.tag_id = :target and t.user_id = s.user_id)
            """, Map.of("source", source, "target", target));
        execute("""
            update learning_hub.community_subscriptions set tag_id = :target, updated_by = :actor,
              update_time = now(), row_version = row_version + 1 where tag_id = :source
            """, Map.of("source", source, "target", target, "actor", actor));
        execute("""
            delete from learning_hub.community_user_expertise s where s.tag_id = :source and exists (
              select 1 from learning_hub.community_user_expertise t where t.tag_id = :target and t.user_id = s.user_id)
            """, Map.of("source", source, "target", target));
        execute("""
            update learning_hub.community_user_expertise set tag_id = :target, updated_by = :actor,
              update_time = now(), row_version = row_version + 1 where tag_id = :source
            """, Map.of("source", source, "target", target, "actor", actor));
        execute("""
            delete from learning_hub.community_tag_aliases s where s.tag_id = :source and exists (
              select 1 from learning_hub.community_tag_aliases t
              where t.tag_id = :target and t.normalized_alias = s.normalized_alias)
            """, Map.of("source", source, "target", target));
        execute("""
            update learning_hub.community_tag_aliases set tag_id = :target, updated_by = :actor,
              update_time = now(), row_version = row_version + 1 where tag_id = :source
            """, Map.of("source", source, "target", target, "actor", actor));
    }

    private void execute(String sql, Map<String, ?> parameters) {
        var query = entityManager.createNativeQuery(sql);
        parameters.forEach(query::setParameter);
        query.executeUpdate();
    }
}
