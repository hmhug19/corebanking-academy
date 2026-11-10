package vn.com.coreacademy.learning.index;

import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.LessonSource;
import vn.com.coreacademy.learning.index.CourseContentRetrieval.CourseHit;

/**
 * Embeds a question and returns the lesson chunks the caller may read, as the same {@link ChunkHit} the document search
 * returns, so numbering, citations and prompts are shared. The question is never logged; only sizes are.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseQuestionSearch {
    // Neighbouring chunks of one lesson are alike, so ask for more than top-k and keep the best.
    private static final int CANDIDATE_FACTOR = 3;

    private final OllamaClient ollama;
    private final CourseContentRetrieval retrieval;
    private final KnowledgeProperties properties;

    /** Throws the Ollama exceptions as they are; the caller decides how to tell the user. */
    public List<ChunkHit> hits(String question) {
        KnowledgeProperties.Retrieval limits = properties.retrieval();
        float[] vector = ollama.embed(List.of(question)).get(0);
        List<ChunkHit> hits = retrieval.search(vector, limits.topK() * CANDIDATE_FACTOR).stream()
            .filter(hit -> hit.similarity() >= limits.minSimilarity()).limit(limits.topK())
            .map(CourseQuestionSearch::asChunkHit).toList();
        log.info("Course retrieval questionLength={} hits={}", question.length(), hits.size());

        return hits;
    }

    static ChunkHit asChunkHit(CourseHit hit) {
        LessonSource lesson = new LessonSource(hit.courseId(), hit.courseTitle(), hit.revisionId(), hit.chapterId(),
            hit.chapterTitle(), hit.lessonId(), hit.lessonTitle(), hit.blockId(), hit.blockTitle(), hit.url());

        return new ChunkHit(hit.chunkId(), 0L, hit.courseTitle() + " › " + hit.lessonTitle(), null, 0, 0,
            hit.content(), hit.similarity(), lesson);
    }
}
