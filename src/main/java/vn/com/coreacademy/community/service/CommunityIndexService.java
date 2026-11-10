package vn.com.coreacademy.community.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vn.com.coreacademy.community.entity.CommunityContent;
import vn.com.coreacademy.community.entity.ContentRevision;
import vn.com.coreacademy.community.repository.CommunityIndexRepository;
import vn.com.coreacademy.community.repository.CommunityRelations;
import vn.com.coreacademy.community.repository.CommunityRepository;
import vn.com.coreacademy.community.repository.CommunityTranscriptRepository;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;

/** Chuẩn bị snapshot trong transaction ngắn; worker embed ngoài transaction và chỉ commit nếu nguồn chưa đổi. */
@Service
@RequiredArgsConstructor
public class CommunityIndexService {
    public record Chunk(Long transcriptId, int number, String text, BigDecimal start, BigDecimal end) {
    }

    public record Plan(Long contentId, Long revisionId, Long orgId, List<Chunk> chunks, List<Long> transcriptIds) {
    }

    private final CommunityRepository repository;
    private final CommunityIndexRepository index;
    private final CommunityRelations relations;
    private final CommunityTranscriptRepository transcripts;
    private final OllamaClient ollama;
    private final KnowledgeProperties properties;

    public Plan prepare(Long contentId, Long ownerOrg) {
        var content = repository.require(CommunityContent.class, contentId);
        if (!Objects.equals(ownerOrg, content.getOwnerOrgId())) {
            throw new IllegalStateException("Index scope differs from event owner");
        }
        if (content.getCurrentPublishedRevisionId() == null || content.getDeletedAt() != null
            || "hidden".equals(content.getLifecycleStatus())) {
            return new Plan(contentId, null, ownerOrg, List.of(), List.of());
        }
        var revision = repository.require(ContentRevision.class, content.getCurrentPublishedRevisionId());
        List<Chunk> chunks = new ArrayList<>();
        split(chunks, null, revision.getTitle() + "\n" + revision.getBodyMarkdown(), null, null);
        List<Long> ids = new ArrayList<>();
        for (var asset : relations.assets(revision.getRevisionId())) {
            for (var transcript : transcripts.published(asset.getAssetId())) {
                ids.add(transcript.getTranscriptId());
                for (var segment : transcript.getSegmentsJson()) {
                    split(chunks, transcript.getTranscriptId(), segment.path("text").asText(),
                        segment.path("startSeconds").decimalValue(), segment.path("endSeconds").decimalValue());
                }
            }
        }
        return new Plan(contentId, revision.getRevisionId(), ownerOrg, chunks, ids);
    }

    public List<float[]> embed(Plan plan) {
        // Any embed model works as long as its vectors are 1024-wide; OllamaClient.embed enforces that.
        return plan.chunks().isEmpty() ? List.of() : ollama.embed(plan.chunks().stream().map(Chunk::text).toList());
    }

    public boolean publish(Plan plan, List<float[]> vectors, String actor) {
        var current = prepare(plan.contentId(), plan.orgId());
        if (!Objects.equals(current.revisionId(), plan.revisionId()) || !current.chunks().equals(plan.chunks())
            || !current.transcriptIds().equals(plan.transcriptIds())) {
            return false;
        }
        index.stale(plan.contentId(), actor);
        if (vectors.size() != plan.chunks().size()) {
            throw new IllegalStateException("Embedding batch length differs from source chunks");
        }
        for (int i = 0; i < vectors.size(); i++) {
            var chunk = plan.chunks().get(i);
            index.upsert(plan.contentId(), plan.revisionId(), chunk.transcriptId(), chunk.number(), chunk.text(),
                properties.ollama().embedModel(), vectors.get(i), chunk.start(), chunk.end(), actor);
        }
        return true;
    }

    private void split(List<Chunk> result, Long transcript, String text, BigDecimal start, BigDecimal end) {
        int number = result.size();
        for (int offset = 0; offset < text.length(); offset += 1400) {
            result.add(new Chunk(transcript, number++, text.substring(offset, Math.min(text.length(), offset + 1500)),
                start, end));
        }
    }
}
