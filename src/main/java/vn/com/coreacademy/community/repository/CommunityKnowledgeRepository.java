package vn.com.coreacademy.community.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.community.entity.ContentLink;
import vn.com.coreacademy.community.entity.HelpRequest;

@Repository
@RequiredArgsConstructor
public class CommunityKnowledgeRepository {
    private final CommunityRepository repository;

    public List<HelpRequest> openHelp(Long questionId, String userId, Long guildId) {
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("question", questionId);
        parameters.put("user", userId);
        parameters.put("guild", guildId);
        return repository.list("""
            select h from HelpRequest h where h.questionContentId = :question and h.status in ('pending', 'accepted')
              and ((:user is not null and h.targetUserId = :user) or (:guild is not null and h.targetGuildId = :guild))
            """, HelpRequest.class, parameters);
    }

    public List<ContentLink> fulfillments(Long contentId) {
        return repository.list("""
            select l from ContentLink l where l.sourceContentId = :content and l.relationType = :type
            order by l.linkId
            """, ContentLink.class, Map.of("content", contentId, "type", "fulfills"));
    }
}
