package vn.com.coreacademy.learning.index;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.ai.OllamaUnavailableException;
import vn.com.coreacademy.knowledge.chunking.Chunk;
import vn.com.coreacademy.knowledge.chunking.Chunker;
import vn.com.coreacademy.knowledge.extraction.TextLine;
import vn.com.coreacademy.learning.index.CourseContentChunkRepository.NewChunk;
import vn.com.coreacademy.learning.index.CourseContentChunkRepository.PendingRevision;
import vn.com.coreacademy.learning.index.CourseContentChunkRepository.SourceBlock;

/**
 * Keeps the course index in step with the published revisions. Each run drops what a newer publication superseded,
 * then indexes every published revision that has no completed entry. It acts on rows and never reads the security
 * context; the chunks it writes are filtered per user at search time. Log lines carry ids only, never course text.
 *
 * <p>Embedding happens outside any transaction; the chunks and the completion marker are written together, so a
 * restart halfway leaves the revision pending and the next run simply does it again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CourseContentIndexer {
    private static final int REVISIONS_PER_RUN = 5;
    private static final Pattern TAG = Pattern.compile("<[^>]+>");
    private static final Pattern MARKDOWN_NOISE = Pattern.compile("[#*_`>]+");

    private final CourseContentChunkRepository index;
    private final Chunker chunker;
    private final OllamaClient ollama;
    private final TransactionTemplate transaction;

    /** Revisions published while the server was down are picked up on the first poll after start-up. */
    @EventListener(ApplicationReadyEvent.class)
    public void dropSupersededAtStart() {
        int dropped = index.dropSuperseded();
        if (dropped > 0) {
            log.info("Dropped superseded course chunks count={}", dropped);
        }
    }

    @Scheduled(fixedDelayString = "${app.learning.course-index.poll-interval}",
        initialDelayString = "${app.learning.course-index.poll-interval}")
    public void poll() {
        if (ollama.isAvailable()) {
            runOnce();
        }
    }

    /** One pass; returns how many revisions were indexed. A failing revision does not stop the others. */
    public int runOnce() {
        index.dropSuperseded();
        int indexed = 0;
        for (PendingRevision revision : index.pending(REVISIONS_PER_RUN)) {
            try {
                indexRevision(revision);
                indexed++;
            } catch (OllamaUnavailableException exception) {
                log.warn("Course indexing paused: Ollama unavailable revisionId={}", revision.revisionId());
                return indexed;
            } catch (RuntimeException exception) {
                // The message could quote course text; the type is enough to find the cause in the stack.
                log.error("Course indexing failed revisionId={} type={}", revision.revisionId(),
                    exception.getClass().getName());
            }
        }

        return indexed;
    }

    private void indexRevision(PendingRevision revision) {
        List<NewChunk> chunks = embed(chunk(index.blocks(revision.revisionId())));
        transaction.executeWithoutResult(status ->
            index.replaceRevision(revision.courseId(), revision.revisionId(), chunks));
        log.info("Course revision indexed courseId={} revisionId={} chunks={}", revision.courseId(),
            revision.revisionId(), chunks.size());
    }

    /** Text of each block cut with the shared chunker, so a chunk never crosses a block. */
    private List<NewChunk> chunk(List<SourceBlock> blocks) {
        List<NewChunk> chunks = new ArrayList<>();
        for (SourceBlock block : blocks) {
            List<TextLine> lines = lines(text(block));
            for (Chunk chunk : chunker.chunk(lines)) {
                chunks.add(new NewChunk(block, chunks.size(), chunk.content(), chunk.tokenEstimate(), null));
            }
        }

        return chunks;
    }

    /** The lesson title leads each embedded text, so a question naming the lesson finds its chunks. */
    private List<NewChunk> embed(List<NewChunk> chunks) {
        if (chunks.isEmpty()) {
            return chunks;
        }
        List<float[]> vectors = ollama.embed(chunks.stream()
            .map(chunk -> chunk.block().lessonTitle() + "\n" + chunk.content()).toList());
        List<NewChunk> embedded = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            NewChunk chunk = chunks.get(i);
            embedded.add(new NewChunk(chunk.block(), chunk.index(), chunk.content(), chunk.tokenEstimate(),
                vectors.get(i)));
        }

        return embedded;
    }

    /** Text, process and video blocks carry words; pdf, image and attachment blocks do not. */
    static String text(SourceBlock block) {
        return switch (block.blockType()) {
            case "text" -> plain(block.body(), block.bodyFormat());
            case "process" -> block.steps() == null ? "" : block.steps();
            case "video" -> transcript(block.transcript());
            default -> "";
        };
    }

    private static String plain(String body, String format) {
        if (body == null) {
            return "";
        }
        String text = "html".equals(format) ? TAG.matcher(body).replaceAll(" ") : body;

        return "markdown".equals(format) ? MARKDOWN_NOISE.matcher(text).replaceAll("") : text;
    }

    private static String transcript(JsonNode transcript) {
        if (transcript == null || !transcript.isArray()) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode entry : transcript) {
            text.append(entry.path("text").asText("")).append('\n');
        }

        return text.toString();
    }

    private static List<TextLine> lines(String text) {
        String[] raw = text.split("\\R");
        List<TextLine> lines = new ArrayList<>(raw.length);
        for (int i = 0; i < raw.length; i++) {
            lines.add(new TextLine(null, i + 1, raw[i].strip()));
        }

        return lines;
    }
}
