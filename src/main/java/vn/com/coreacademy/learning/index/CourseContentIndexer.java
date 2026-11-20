package vn.com.coreacademy.learning.index;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import vn.com.coreacademy.knowledge.chunking.ChunkText;
import vn.com.coreacademy.knowledge.chunking.Chunker;
import vn.com.coreacademy.knowledge.extraction.TextLine;
import vn.com.coreacademy.learning.index.CourseContentChunkRepository.BlockAsset;
import vn.com.coreacademy.learning.index.CourseContentChunkRepository.NewChunk;
import vn.com.coreacademy.learning.index.CourseContentChunkRepository.PendingRevision;
import vn.com.coreacademy.learning.index.CourseContentChunkRepository.SourceBlock;
import vn.com.coreacademy.learning.mapper.FlowModelJson;
import vn.com.coreacademy.learning.service.FlowModelRules;

/**
 * Keeps the course index in step with the published revisions. Each run indexes the revisions that have no completed
 * entry (the published one of each course, and any revision a learner is still enrolled on), and only then drops what
 * no published revision or active enrolment needs any more, so the previous revision answers until the new one is in.
 * A revision that fails is retried with a back-off and marked failed after the configured attempts. It acts on rows
 * and never reads the security context; the chunks it writes are filtered per user at search time. Log lines carry
 * ids only, never course text.
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
    private final CourseAssetText assetText;
    private final CourseIndexProperties properties;

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
                markFailed(revision, exception);
            }
        }
        // After the new revisions are in, never before: the old chunks keep answering while this runs.
        index.dropSuperseded();

        return indexed;
    }

    private void markFailed(PendingRevision revision, RuntimeException exception) {
        try {
            index.recordFailure(revision.courseId(), revision.revisionId(), exception.getClass().getName(),
                properties.maxAttempts(), properties.retryDelay());
        } catch (RuntimeException recording) {
            log.error("Cannot record index failure revisionId={} type={}", revision.revisionId(),
                recording.getClass().getName());
        }
    }

    private void indexRevision(PendingRevision revision) {
        List<NewChunk> chunks = embed(index.courseTitle(revision.revisionId()),
            chunk(index.blocks(revision.revisionId()), index.blockAssets(revision.revisionId())));
        transaction.executeWithoutResult(status ->
            index.replaceRevision(revision.courseId(), revision.revisionId(), chunks));
        log.info("Course revision indexed courseId={} revisionId={} chunks={}", revision.courseId(),
            revision.revisionId(), chunks.size());
    }

    /**
     * Cuts and embeds a revision again with the current chunker and embed model and swaps its chunks in one
     * transaction: the old chunks answer until the commit. Throws on failure and leaves the old chunks as they were.
     */
    public void reindex(PendingRevision revision) {
        indexRevision(revision);
    }

    /**
     * Text of each block cut with the shared chunker, so a chunk never crosses a block. The words of a pdf or
     * attachment block are those of its files, and each chunk keeps the page it starts on.
     */
    private List<NewChunk> chunk(List<SourceBlock> blocks, Map<Long, List<BlockAsset>> assets) {
        List<NewChunk> chunks = new ArrayList<>();
        for (SourceBlock block : blocks) {
            List<TextLine> lines = isFile(block)
                ? assetText.lines(block.blockId(), assets.getOrDefault(block.blockId(), List.of()))
                : lines(text(block));
            for (Chunk chunk : chunker.chunk(lines)) {
                chunks.add(new NewChunk(block, chunks.size(), chunk.content(), chunk.tokenEstimate(), null,
                    chunk.page(), sectionPath(block, chunk.sectionPath())));
            }
        }

        return chunks;
    }

    /** Chapter, lesson and block, then the headings inside the block; the course title is the head of the text. */
    private static String sectionPath(SourceBlock block, List<String> inner) {
        List<String> path = new ArrayList<>();
        for (String part : List.of(block.chapterTitle(), block.lessonTitle(),
            block.blockTitle() == null ? "" : block.blockTitle())) {
            if (!part.isBlank()) {
                path.add(part);
            }
        }
        path.addAll(inner);

        return ChunkText.join(path);
    }

    /** {@code course › chapter › lesson › block} leads each embedded text, so a question naming any of them finds it. */
    private List<NewChunk> embed(String courseTitle, List<NewChunk> chunks) {
        if (chunks.isEmpty()) {
            return chunks;
        }
        List<float[]> vectors = ollama.embed(chunks.stream()
            .map(chunk -> ChunkText.withContext(courseTitle, chunk.sectionPath(), chunk.content())).toList());
        List<NewChunk> embedded = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            NewChunk chunk = chunks.get(i);
            embedded.add(new NewChunk(chunk.block(), chunk.index(), chunk.content(), chunk.tokenEstimate(),
                vectors.get(i), chunk.page(), chunk.sectionPath()));
        }

        return embedded;
    }

    private static boolean isFile(SourceBlock block) {
        return "pdf".equals(block.blockType()) || "attachment".equals(block.blockType());
    }

    /**
     * Text, process, video and flow blocks carry words here; the words of pdf and attachment blocks come from
     * their files ({@link CourseAssetText}) and an image has none.
     */
    static String text(SourceBlock block) {
        return switch (block.blockType()) {
            case "text" -> plain(block.body(), block.bodyFormat());
            case "process" -> block.steps() == null ? "" : block.steps();
            case "video" -> transcript(block.transcript());
            case "flow" -> FlowModelRules.text(FlowModelJson.read(block.flow()));
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
