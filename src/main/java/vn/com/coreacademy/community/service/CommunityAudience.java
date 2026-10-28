package vn.com.coreacademy.community.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.community.entity.Answer;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.repository.CommunityRelations;
import vn.com.coreacademy.community.repository.CommunityRepository;

/** Chứng minh audience nguồn nằm trong audience đích bằng grant khai báo, không snapshot membership dễ thay đổi. */
@Component
@RequiredArgsConstructor
public class CommunityAudience {
    private final CommunityRepository repository;
    private final CommunityRelations relations;

    private record Reader(String userId, Long guildId, Instant expiresAt) {
    }

    public boolean covered(CommunityContent request, CommunityContent target) {
        if (target.getDeletedAt() != null || "hidden".equals(target.getLifecycleStatus())
            || target.getCurrentPublishedRevisionId() == null || !Objects.equals(request.getOwnerOrgId(),
                target.getOwnerOrgId())
            || Objects.equals(request.getContentId(), target.getContentId())) {
            return false;
        }
        request = root(request);
        target = root(target);
        if (target.getDeletedAt() != null || "hidden".equals(target.getLifecycleStatus())
            || target.getCurrentPublishedRevisionId() == null) {
            return false;
        }
        if ("organization".equals(target.getVisibility())) {
            return true;
        }
        if ("organization".equals(request.getVisibility())) {
            return false;
        }
        List<Reader> audience = readers(request);
        List<Reader> permitted = readers(target);
        return audience.stream().allMatch(reader -> permitted.stream().anyMatch(allowed ->
            Objects.equals(reader.userId(), allowed.userId()) && Objects.equals(reader.guildId(), allowed.guildId())
                && (allowed.expiresAt() == null || reader.expiresAt() != null
                    && !allowed.expiresAt().isBefore(reader.expiresAt()))));
    }

    private CommunityContent root(CommunityContent content) {
        return "answer".equals(content.getKind()) ? repository.require(CommunityContent.class,
            repository.require(Answer.class, content.getContentId()).getQuestionContentId()) : content;
    }

    private List<Reader> readers(CommunityContent content) {
        if ("guild".equals(content.getVisibility())) {
            return List.of(new Reader(null, content.getPrimaryGuildId(), null));
        }
        List<Reader> readers = new ArrayList<>();
        readers.add(new Reader(content.getAuthorUserId(), null, null));
        readers.add(new Reader(content.getMaintainerUserId(), null, null));
        relations.grants(content.getContentId()).stream()
            .filter(grant -> grant.getExpiresAt() == null || grant.getExpiresAt().isAfter(Instant.now()))
            .forEach(grant -> readers.add(new Reader(grant.getUserId(), grant.getGuildId(), grant.getExpiresAt())));
        return List.copyOf(readers);
    }
}
