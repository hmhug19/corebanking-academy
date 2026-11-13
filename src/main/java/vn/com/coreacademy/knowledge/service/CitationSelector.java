package vn.com.coreacademy.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;
import vn.com.coreacademy.knowledge.dto.ChatViews.LessonCitation;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.LessonSource;

/**
 * Chooses the sources shown with an answer. Every field of a citation comes from the retrieved chunk's metadata; the
 * model's text only decides which of the chunks it marked with [n] are shown.
 */
public final class CitationSelector {
    private static final int SNIPPET_LENGTH = 280;
    // [1], [2][3] and [1, 2] all name chunks; anything else in brackets is ignored.
    private static final Pattern MARKER = Pattern.compile("\\[(\\d+(?:\\s*,\\s*\\d+)*)]");

    // A marker with the space before it, or the literal placeholder [n] a model copies from its instructions.
    private static final Pattern MARKER_WITH_SPACE = Pattern.compile("([ \\t]*)\\[(\\d+(?:\\s*,\\s*\\d+)*|[nN])]");

    private CitationSelector() {
    }

    /**
     * {@code numberedHits} are numbered [1]…[size] in the order given, the numbers the model saw. Only the chunks it
     * marked are returned, in number order; markers outside 1..size are ignored, and an answer with no usable marker
     * returns none (see {@link CitationRule}).
     */
    public static List<CitationView> select(String answer, List<ChunkHit> numberedHits) {
        return select(answer, numberedHits, Map.of());
    }

    /** As above; the snippet of chunk n is the part of it that overlaps most with {@code citingWords.get(n)}. */
    public static List<CitationView> select(String answer, List<ChunkHit> numberedHits,
                                            Map<Integer, Set<String>> citingWords) {
        Set<Integer> cited = citedNumbers(answer, numberedHits.size());
        List<CitationView> citations = new ArrayList<>();
        for (int n = 1; n <= numberedHits.size(); n++) {
            if (cited.contains(n)) {
                citations.add(citation(n, numberedHits.get(n - 1), citingWords.getOrDefault(n, Set.of())));
            }
        }

        return citations;
    }

    /** Chunk numbers named by [n] markers in the answer, limited to 1..size. */
    public static Set<Integer> citedNumbers(String text, int size) {
        Set<Integer> numbers = new TreeSet<>();
        Matcher matcher = MARKER.matcher(text);
        while (matcher.find()) {
            for (String part : matcher.group(1).split(",")) {
                String digits = part.strip();
                // More than 3 digits cannot be a chunk number (top-k is small) and could overflow parseInt.
                if (digits.length() <= 3) {
                    int n = Integer.parseInt(digits);
                    if (n >= 1 && n <= size) {
                        numbers.add(n);
                    }
                }
            }
        }

        return numbers;
    }

    /** The text without its [n] markers and the space before them, for earlier answers replayed as history. */
    static String stripMarkers(String text) {
        return keepMarkers(text, Set.of());
    }

    /**
     * The text with only the marker numbers in {@code kept}: a marker naming none of them goes with the space before
     * it, and so does the literal placeholder [n], so the reader never sees a number that opens no source.
     */
    public static String keepMarkers(String text, Set<Integer> kept) {
        Matcher matcher = MARKER_WITH_SPACE.matcher(text);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            List<String> numbers = new ArrayList<>();
            if (!matcher.group(2).equalsIgnoreCase("n")) {
                for (String part : matcher.group(2).split(",")) {
                    String digits = part.strip();
                    if (digits.length() <= 3 && kept.contains(Integer.parseInt(digits))) {
                        numbers.add(digits);
                    }
                }
            }
            String replacement = numbers.isEmpty() ? "" : matcher.group(1) + "[" + String.join(", ", numbers) + "]";
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);

        return out.toString();
    }

    private static CitationView citation(int n, ChunkHit hit, Set<String> citingWords) {
        LessonSource lesson = hit.lesson();
        LessonCitation cited = lesson == null ? null : new LessonCitation(lesson.courseId(), lesson.courseTitle(),
            lesson.revisionId(), lesson.chapterTitle(), lesson.lessonId(), lesson.lessonTitle(), lesson.blockId(),
            lesson.url());

        return new CitationView(n, hit.documentId(), hit.fileName(), hit.page(), hit.lineStart(), hit.lineEnd(),
            citingWords.isEmpty() ? snippet(hit.content()) : CitationSupport.snippet(hit.content(), citingWords), cited,
            hit.sectionPath(), lesson == null ? hit.chunkId() : null);
    }

    /** The first 280 characters, cut back to a word boundary. */
    static String snippet(String content) {
        if (content.length() <= SNIPPET_LENGTH) {
            return content;
        }
        int cut = content.lastIndexOf(' ', SNIPPET_LENGTH);
        int end = cut > 0 ? cut : SNIPPET_LENGTH;

        return content.substring(0, end).stripTrailing() + "…";
    }
}
