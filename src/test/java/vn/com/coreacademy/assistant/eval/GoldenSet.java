package vn.com.coreacademy.assistant.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.ObjectMapper;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.LessonSource;

/**
 * The versioned golden set the model evals run on ({@code assistant/eval/golden-set.json}): a small corpus of passages
 * and the cases that refer to them. Retrieval is not part of the set: each case names the passages a good search hands
 * the model, so the evals measure the answer, its sources, the not-found rule, injection resistance and tool choice.
 */
public record GoldenSet(String version, String note, Approval approval, List<Passage> passages, List<Answer> answers,
                        List<NotFound> notFound, List<Injection> injection, List<ToolCase> toolChoice,
                        List<FollowUp> followUps) {
    static final String RESOURCE = "/assistant/eval/golden-set.json";

    public record Approval(String approvedBy, String approvedAt) {
    }

    public record Lesson(String course, String chapter, String lesson) {
    }

    public record Passage(String id, String file, Integer page, Integer lineStart, Integer lineEnd, Lesson lesson,
                          String text) {
    }

    /** A question a document answers: the answer must cover every point (any one of the alternatives of a point). */
    public record Answer(String id, String question, List<String> passages, List<List<String>> points,
                         List<String> sources) {
    }

    /** A question the documents do not answer: the answer must be the fixed not-found sentence with no sources. */
    public record NotFound(String id, String question, List<String> passages) {
    }

    /** A passage carrying an instruction: the answer must not obey it, and must still answer the question. */
    public record Injection(String id, String question, String file, String injected, String canary,
                            String forbiddenTool, List<List<String>> points) {
    }

    public record ToolCase(String id, String prompt, boolean lesson, String requestedSkill, String expectedSkill,
                           String expectedTool) {
    }

    /**
     * A question that only makes sense after the previous exchange ("còn trường hợp kia?"): the classifier is given
     * that exchange and must still pick the skill of the earlier topic.
     */
    public record FollowUp(String id, String previousQuestion, String previousAnswer, String prompt,
                           String expectedSkill) {
    }

    public static GoldenSet load() {
        try (InputStream in = GoldenSet.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + RESOURCE);
            }
            return new ObjectMapper().readValue(in, GoldenSet.class);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public boolean approved() {
        return approval != null && approval.approvedBy() != null && !approval.approvedBy().isBlank()
            && approval.approvedAt() != null && !approval.approvedAt().isBlank();
    }

    public Map<String, Passage> passageById() {
        return passages.stream().collect(Collectors.toMap(Passage::id, Function.identity()));
    }

    /** The chunk a search would return for a passage; the number the model reads is given by the order seen. */
    public static ChunkHit hit(int index, Passage passage) {
        long id = index + 1L;
        LessonSource lesson = passage.lesson() == null ? null : new LessonSource(id, passage.lesson().course(), 1, 1,
            passage.lesson().chapter(), id, passage.lesson().lesson(), 1, null, "/lesson/" + id + "/" + id);
        String file = passage.file() == null ? passage.lesson().lesson() : passage.file();
        int start = passage.lineStart() == null ? 0 : passage.lineStart();
        int end = passage.lineEnd() == null ? 0 : passage.lineEnd();

        return new ChunkHit(id, id, file, passage.page(), start, end, passage.text(), 0.9, lesson);
    }
}
