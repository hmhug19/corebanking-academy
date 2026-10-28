package vn.com.coreacademy.community.repository;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import vn.com.coreacademy.community.entity.MediaTranscript;

@Repository
@RequiredArgsConstructor
public class CommunityTranscriptRepository {
    private final CommunityRepository repository;

    public List<MediaTranscript> byRequest(String actorId, UUID requestId) {
        return repository.list("""
            select t from MediaTranscript t where t.createdBy = :actor and t.clientRequestId = :request
            """, MediaTranscript.class, Map.of("actor", actorId, "request", requestId));
    }

    public int nextVersion(Long assetId) {
        Integer value = repository.one("select max(t.versionNo) from MediaTranscript t where t.assetId = :asset",
            Integer.class, Map.of("asset", assetId));
        return value == null ? 1 : Math.addExact(value, 1);
    }

    public List<MediaTranscript> published(Long assetId) {
        return repository.list("""
            select t from MediaTranscript t where t.assetId = :asset and t.status = :status order by t.transcriptId
            """, MediaTranscript.class, Map.of("asset", assetId, "status", "published"));
    }
}
