package vn.com.coreacademy.knowledge.extraction;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Tells readable text from a text layer in a legacy font or code page (TCVN3, VNI, a symbol font), which extracts as
 * letters that are not Vietnamese. A word counts as readable when it is a plain Latin word (a-z) or a valid Vietnamese
 * syllable; digits and punctuation are ignored.
 */
final class TextQuality {
    private static final Pattern WORD = Pattern.compile("\\p{L}+");
    private static final Pattern EDGES = Pattern.compile("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$");
    private static final Pattern HAS_LETTER = Pattern.compile("\\p{L}");
    /** Letters, digits and the punctuation that appears inside ordinary words and codes; anything else is a symbol. */
    private static final Pattern INNER = Pattern.compile("[\\p{L}\\p{N}\\p{M}_'’./@:&+#%(),-]+");
    private static final Pattern PLAIN = Pattern.compile("[a-z]+");
    /** Combining tone marks: grave, acute, hook above, tilde, dot below. The vowel marks (breve, circumflex, horn) stay. */
    private static final Pattern TONES = Pattern.compile("[\u0300\u0301\u0309\u0303\u0323]");
    private static final String NUCLEUS = "(?:uyê|iêu|yêu|oai|oay|oeo|uây|uôi|ươi|ươu|uya|uyu|ai|ao|au|ay|âu|ây|eo|êu|ia|iê|iu|oa|oă|oe|oi|ôi|ơi|oo|ua|uâ|uê|ui|uô|uơ|ưa|ưi|ươ|ưu|uy|yê|[aăâeêioôơuưy])";
    private static final Pattern SYLLABLE = Pattern.compile(
        "(?:ngh|ng|nh|ch|gh|gi|kh|ph|th|tr|qu|[bcdđghklmnpqrstvx])?" + NUCLEUS + "(?:ng|nh|ch|[cmnpt])?");

    private TextQuality() {
    }

    /**
     * Share of readable words, 1.0 when the text has no words at all (nothing to judge). A word is a run between
     * spaces; a stray symbol inside it ({@code tr×nh}) makes it unreadable, because that is how a legacy font shows.
     */
    static double readableWordShare(List<String> lines) {
        long total = 0;
        long readable = 0;
        for (String line : lines) {
            for (String token : line.split("\\s+")) {
                String word = EDGES.matcher(token).replaceAll("");
                if (word.isEmpty() || !HAS_LETTER.matcher(word).find()) {
                    continue;
                }
                total++;
                if (isReadableToken(word)) {
                    readable++;
                }
            }
        }

        return total == 0 ? 1.0 : (double) readable / total;
    }

    private static boolean isReadableToken(String word) {
        if (!INNER.matcher(word).matches()) {
            return false;
        }
        var runs = WORD.matcher(word);
        while (runs.find()) {
            if (!isReadable(runs.group())) {
                return false;
            }
        }

        return true;
    }

    static boolean isReadable(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        if (PLAIN.matcher(lower).matches()) {
            return true;
        }
        String bare = Normalizer.normalize(
            TONES.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll(""), Normalizer.Form.NFC);

        return SYLLABLE.matcher(bare).matches();
    }
}
