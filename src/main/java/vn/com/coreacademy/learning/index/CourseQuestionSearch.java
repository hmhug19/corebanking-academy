package vn.com.coreacademy.learning.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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

    // A summary reads the lesson itself; this many chunks fit the model's context with the prompt.
    static final int LESSON_CHUNKS = 8;

    /** Throws the Ollama exceptions as they are; the caller decides how to tell the user. */
    public List<ChunkHit> hits(String question) {
        return hits(question, null);
    }

    /**
     * With a {@code lessonId} the chunks of that lesson come first and are kept whatever their similarity, since the
     * user asked about that lesson ("bài này nói gì?" is close to nothing in particular); other chunks fill the rest.
     */
    public List<ChunkHit> hits(String question, Long lessonId) {
        KnowledgeProperties.Retrieval limits = properties.retrieval();
        float[] vector = ollama.embed(List.of(question)).get(0);
        List<CourseHit> chosen = new ArrayList<>();
        if (lessonId != null) {
            chosen.addAll(retrieval.searchInLesson(vector, lessonId, limits.topK()));
        }
        Set<Long> seen = chosen.stream().map(CourseHit::chunkId).collect(Collectors.toSet());
        retrieval.search(vector, limits.topK() * CANDIDATE_FACTOR).stream()
            .filter(hit -> hit.similarity() >= limits.minSimilarity() && seen.add(hit.chunkId()))
            .limit(Math.max(0, limits.topK() - chosen.size())).forEach(chosen::add);
        List<ChunkHit> hits = chosen.stream().map(CourseQuestionSearch::asChunkHit).toList();
        log.info("Course retrieval questionLength={} inLesson={} hits={}", question.length(), lessonId != null,
            hits.size());

        return hits;
    }

    /** The first chunks of a lesson in reading order, for a summary; empty when the caller may not read it. */
    public List<ChunkHit> lessonText(long lessonId) {
        return retrieval.lessonChunks(lessonId, LESSON_CHUNKS).stream().map(CourseQuestionSearch::asChunkHit).toList();
    }

    static ChunkHit asChunkHit(CourseHit hit) {
        LessonSource lesson = new LessonSource(hit.courseId(), hit.courseTitle(), hit.revisionId(), hit.chapterId(),
            hit.chapterTitle(), hit.lessonId(), hit.lessonTitle(), hit.blockId(), hit.blockTitle(), hit.url());

        return new ChunkHit(hit.chunkId(), 0L, hit.courseTitle() + " › " + hit.lessonTitle(), null, 0, 0,
            hit.content(), hit.similarity(), lesson);
    }
}
