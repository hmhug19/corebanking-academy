package vn.com.coreacademy.community.service;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.community.constants.CommunityCodes;
import vn.com.coreacademy.community.dto.CollectionDtos.ContentRef;
import vn.com.coreacademy.community.dto.ContentViews.Capabilities;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.ContentViews.PersonRef;
import vn.com.coreacademy.community.dto.ContentViews.TagView;
import vn.com.coreacademy.community.repository.CommunityCardRepository;
import vn.com.coreacademy.identity.service.LearningIdentityService;

/** Read model theo lô của cả trang; không lấy grants/role để serialize ra client. */
@Service
@RequiredArgsConstructor
public class CommunityCardService {
    private final CommunityCardRepository cards;
    private final CommunityPolicy policy;
    private final LearningIdentityService identity;
    private final ObjectMapper json;

    public List<ContentCard> enrich(List<ContentCard> input) {
        if (input.isEmpty()) {
            return List.of();
        }
        var actor = policy.actor();
        var extras = cards.extras(input.stream().map(ContentCard::contentId).toList(), actor);
        var names = identity.fullNames(input.stream().map(ContentCard::authorUserId).distinct().toList());
        return input.stream().map(card -> {
            Object[] row = extras.get(card.contentId());
            boolean author = Objects.equals(card.authorUserId(), actor.person().id());
            boolean active = Boolean.TRUE.equals(row[17]);
            boolean editable = Boolean.TRUE.equals(row[16]);
            boolean published = card.publishedRevisionId() != null;
            boolean question = "question".equals(card.kind());
            var capabilities = new Capabilities(active && editable,
                active && Boolean.TRUE.equals(row[18]) && !CommunityCodes.REVIEWED_KINDS.contains(card.kind()),
                active && published && question, active && published,
                active && published && !author && CommunityCodes.VOTABLE_KINDS.contains(card.kind()),
                published && author && question && List.of("active", "closed").contains(card.lifecycleStatus()),
                active && author && !"answer".equals(card.kind()), published, false, false, false, false, false);
            ContentRef duplicate = row[11] == null ? null : new ContentRef(number(row[11]), row[12].toString(),
                number(row[13]), row[14].toString(), "/community/contents/" + row[11]);
            return new ContentCard(card.contentId(), card.kind(), card.title(), card.summary(), card.authorUserId(),
                card.lifecycleStatus(), card.visibility(), card.primaryGuildId(), card.publishedAt(),
                    card.lastActivityAt(),
                card.contentVersion(), card.publishedRevisionId(), new PersonRef(card.authorUserId(),
                    names.get(card.authorUserId())),
                tags(row[15].toString()), number(row[1]), (int) number(row[2]), capabilities,
                row[3] == null ? null : row[3].toString(), number(row[6]), nullable(row[4]),
                    Boolean.TRUE.equals(row[10]),
                duplicate, nullable(row[7]), card.publishedRevisionId(), Boolean.TRUE.equals(row[8]), nullable(row[5]),
                Boolean.TRUE.equals(row[9]), editable && Boolean.TRUE.equals(row[19]),
                row[20] == null ? null : row[20].toString());
        }).toList();
    }

    private List<TagView> tags(String value) {
        try {
            return json.readValue(value, json.getTypeFactory().constructCollectionType(List.class, TagView.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Invalid tag projection", exception);
        }
    }

    private long number(Object value) {
        return value == null ? 0 : ((Number) value).longValue();
    }

    private Long nullable(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }
}
