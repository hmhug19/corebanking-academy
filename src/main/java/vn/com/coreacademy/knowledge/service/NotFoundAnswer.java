package vn.com.coreacademy.knowledge.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** The one sentence that means "the documents do not say", and the check for the model having said it. */
public final class NotFoundAnswer {
    public static final String TEXT = "Không tìm thấy thông tin này trong tài liệu.";

    private static final Pattern FIRST_PARAGRAPH = Pattern.compile("\\R\\s*\\R");
    private static final String PREFIX = normalize("Không tìm thấy thông tin này trong tài liệu");
    // Wordings a small model uses instead of the fixed sentence.
    private static final List<String> REWORDED = List.of("không tìm thấy", "không được đề cập", "không đề cập",
        "không có thông tin", "không có trong tài liệu", "không có nội dung").stream()
        .map(NotFoundAnswer::normalize).toList();

    private NotFoundAnswer() {
    }

    /** Tolerates leading whitespace or quotes and a different case; the model is asked for the exact sentence. */
    public static boolean isNotFound(String answer) {
        int start = 0;
        while (start < answer.length() && !Character.isLetterOrDigit(answer.charAt(start))) {
            start++;
        }

        return normalize(answer.substring(start)).startsWith(PREFIX);
    }

    /**
     * Whether the first sentence says in other words that the documents do not have it. Meant for an answer that cites
     * nothing: a later "not mentioned" inside a real answer does not count.
     */
    public static boolean saysNothingWasFound(String answer) {
        String text = normalize(answer.strip());
        int end = text.length();
        for (char stop : new char[] {'.', '!', '?', '\n'}) {
            int at = text.indexOf(stop);
            if (at >= 0 && at < end) {
                end = at;
            }
        }
        String first = text.substring(0, end);

        return REWORDED.stream().anyMatch(first::contains);
    }

    /**
     * Whether the fixed sentence stands anywhere in the first paragraph, markers or not: a model that writes one line
     * and then refuses has still refused, and its marked line must not be shown with a source.
     */
    public static boolean refusesInFirstParagraph(String answer) {
        String first = FIRST_PARAGRAPH.split(CitationSelector.keepMarkers(answer.strip(), Set.of()), 2)[0];

        return normalize(first).contains(PREFIX);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}
