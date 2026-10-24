package vn.com.coreacademy.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vn.com.coreacademy.knowledge.dto.ChatViews.CitationView;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;

/**
 * Chooses the sources shown with an answer. Every field of a citation comes from the retrieved chunk's metadata; the
 * model's text only decides which of the chunks it marked with [n] are shown.
 */
public final class CitationSelector {
    private static final int SNIPPET_LENGTH = 280;
    // [1], [2][3] and [1, 2] all name chunks; anything else in brackets is ignored.
    private static final Pattern MARKER = Pattern.compile("\\[(\\d+(?:\\s*,\\s*\\d+)*)]");

    private CitationSelector() {
    }

    /**
     * {@code numberedHits} are numbered [1]…[size] in the order given, the numbers the model saw. Only the chunks it
     * marked are returned, in number order; markers outside 1..size are ignored, and an answer with no usable marker
     * returns every chunk rather than none.
     */
    public static List<CitationView> select(String answer, List<ChunkHit> numberedHits) {
        Set<Integer> cited = citedNumbers(answer, numberedHits.size());
        List<CitationView> citations = new ArrayList<>();
        for (int n = 1; n <= numberedHits.size(); n++) {
            if (cited.isEmpty() || cited.contains(n)) {
                citations.add(citation(n, numberedHits.get(n - 1)));
            }
        }

        return citations;
    }

    /** Chunk numbers named by [n] markers in the answer, limited to 1..size. */
    static Set<Integer> citedNumbers(String text, int size) {
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

    private static CitationView citation(int n, ChunkHit hit) {
        return new CitationView(n, hit.documentId(), hit.fileName(), hit.page(), hit.lineStart(), hit.lineEnd(),
            snippet(hit.content()));
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
