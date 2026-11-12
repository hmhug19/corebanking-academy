package vn.com.coreacademy.knowledge.service;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;

/**
 * Checks that the chunk a sentence cites says what the sentence says. The test is cheap and needs no model: the share
 * of the sentence's content words that the chunk also has. A marker that fails is taken out of the answer, and the
 * sentence it sat on stays. The same words choose the part of the chunk shown as the snippet.
 *
 * <p>Content words are Vietnamese syllables (the text is already split on spaces) without stop words; a token with a
 * digit (a number, a rate, a code such as {@code TK-001} or {@code 12.5%}) is kept whole.
 */
public final class CitationSupport {
    /**
     * The least share of a sentence's content words its chunk must have. A paraphrase keeps most key words; a sentence
     * about something else shares a few common ones at most. Set against the sentences of the golden set (see
     * CitationRuleTest and GoldenSetEval) so the correct citations there stay.
     */
    static final double MIN_SHARE = 0.4;
    /** A best sentence shorter than this is shown with the sentence after it. */
    private static final int SHORT_SENTENCE = 30;
    private static final int SNIPPET_LENGTH = 280;

    private static final Pattern MARKER = Pattern.compile("[ \\t]*\\[(\\d+(?:\\s*,\\s*\\d+)*)]");
    private static final Pattern MARKERS_ONLY = Pattern.compile("\\[\\d+(?:\\s*,\\s*\\d+)*]");
    // A sentence ends at . ! ? … plus any markers that follow it ("... hàng. [1]"), before a space or the end; a line
    // break always ends one. "10.000" and "0,05%" have no space after the dot and stay whole.
    private static final Pattern BOUNDARY = Pattern.compile(
        "[.!?…]+((?:\\s*\\[\\d+(?:\\s*,\\s*\\d+)*])*)(?:\\s+|$)|\\n+");
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+(?:[.,\\-/][\\p{L}\\p{N}]+)*%?");
    private static final Pattern DIGIT = Pattern.compile("\\p{N}");
    private static final Pattern NON_LETTER = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Set<String> STOP_WORDS = Set.of("và", "là", "của", "các", "những", "một", "có", "được", "cho",
        "trong", "với", "khi", "để", "thì", "mà", "này", "đó", "không", "cũng", "như", "theo", "từ", "tại", "về", "bị",
        "đã", "sẽ", "đang", "rằng", "nên", "hay", "hoặc", "nếu", "do", "bởi", "vì", "sau", "trước", "rất", "lại",
        "còn", "ra", "vào", "lên", "xuống", "ở", "đây", "kia", "nào", "gì", "đều", "mỗi", "cả", "tất", "mình", "bạn",
        "thế", "nhưng", "tuy", "nhiên", "ạ", "nhé", "đến", "tới", "bằng", "trên", "dưới");

    /**
     * What the check made of an answer: the text without the markers that failed, how many in-range markers were
     * checked and how many were removed, and for each chunk number the content words of the sentences that cite it.
     */
    public record Checked(String answer, int checked, int removed, Map<Integer, Set<String>> citingWords) {
    }

    private CitationSupport() {
    }

    /** The content words of a text, lower case, in order of first appearance; markers are not words. */
    public static Set<String> contentWords(String text) {
        Set<String> words = new LinkedHashSet<>();
        String plain = Normalizer.normalize(MARKERS_ONLY.matcher(text).replaceAll(" "), Normalizer.Form.NFC)
            .toLowerCase(Locale.ROOT);
        Matcher matcher = TOKEN.matcher(plain);
        while (matcher.find()) {
            String token = matcher.group();
            if (DIGIT.matcher(token).find()) {
                words.add(token);
                continue;
            }
            for (String part : NON_LETTER.split(token)) {
                if (!part.isEmpty() && !STOP_WORDS.contains(part)) {
                    words.add(part);
                }
            }
        }

        return words;
    }

    /** Removes the markers whose chunk does not support the sentence they sit on; see the class comment. */
    public static Checked check(String answer, List<ChunkHit> numberedHits) {
        List<Set<String>> chunkWords = new ArrayList<>();
        for (ChunkHit hit : numberedHits) {
            chunkWords.add(contentWords(hit.content() + " " + hit.sectionPath()));
        }
        StringBuilder rebuilt = new StringBuilder();
        Map<Integer, Set<String>> citing = new TreeMap<>();
        int checked = 0;
        int removed = 0;
        for (String sentence : sentences(answer)) {
            Set<String> words = contentWords(sentence);
            Matcher matcher = MARKER.matcher(sentence);
            StringBuilder out = new StringBuilder();
            while (matcher.find()) {
                List<String> kept = new ArrayList<>();
                boolean changed = false;
                for (String part : matcher.group(1).split(",")) {
                    String digits = part.strip();
                    int n = digits.length() <= 3 ? Integer.parseInt(digits) : 0;
                    if (n < 1 || n > numberedHits.size()) {
                        kept.add(digits);
                        continue;
                    }
                    checked++;
                    if (supports(words, chunkWords.get(n - 1))) {
                        kept.add(digits);
                        citing.computeIfAbsent(n, key -> new HashSet<>()).addAll(words);
                    } else {
                        removed++;
                        changed = true;
                    }
                }
                String replacement = !changed ? matcher.group()
                    : kept.isEmpty() ? "" : " [" + String.join(", ", kept) + "]";
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(out);
            rebuilt.append(out);
        }

        return new Checked(rebuilt.toString(), checked, removed, citing);
    }

    /**
     * The sentence of the chunk that shares the most content words with the citing sentences, with the next one when
     * it is short, cut to 280 characters at a word boundary. A chunk with no word in common shows its opening.
     */
    static String snippet(String content, Set<String> citingWords) {
        List<String> sentences = sentences(content).stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
        int best = -1;
        int bestScore = 0;
        for (int i = 0; i < sentences.size(); i++) {
            int score = 0;
            for (String word : contentWords(sentences.get(i))) {
                score += citingWords.contains(word) ? 1 : 0;
            }
            if (score > bestScore) {
                best = i;
                bestScore = score;
            }
        }
        if (best < 0) {
            return cut(content);
        }
        String text = sentences.get(best);
        if (text.length() < SHORT_SENTENCE && best + 1 < sentences.size()) {
            text = text + " " + sentences.get(best + 1);
        }

        return cut(text);
    }

    private static boolean supports(Set<String> sentenceWords, Set<String> chunkWords) {
        if (sentenceWords.isEmpty()) {
            return true;
        }
        long shared = sentenceWords.stream().filter(chunkWords::contains).count();

        return (double) shared / sentenceWords.size() >= MIN_SHARE;
    }

    /** The text cut into sentences, markers that follow the full stop kept with their sentence; nothing is lost. */
    private static List<String> sentences(String text) {
        List<String> parts = new ArrayList<>();
        Matcher matcher = BOUNDARY.matcher(text);
        int start = 0;
        while (matcher.find()) {
            // A line break ends the sentence before it; the break belongs to the next one.
            int end = matcher.group(1) == null ? matcher.start() : matcher.start(1) + matcher.group(1).length();
            if (end > start) {
                parts.add(text.substring(start, end));
                start = end;
            }
        }
        if (start < text.length()) {
            parts.add(text.substring(start));
        }

        return parts;
    }

    private static String cut(String text) {
        if (text.length() <= SNIPPET_LENGTH) {
            return text;
        }
        int at = text.lastIndexOf(' ', SNIPPET_LENGTH);
        int end = at > 0 ? at : SNIPPET_LENGTH;

        return text.substring(0, end).stripTrailing() + "…";
    }
}
