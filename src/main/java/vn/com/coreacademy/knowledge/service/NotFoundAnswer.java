package vn.com.coreacademy.knowledge.service;

import java.text.Normalizer;
import java.util.Locale;

/** The one sentence that means "the documents do not say", and the check for the model having said it. */
public final class NotFoundAnswer {
    public static final String TEXT = "Không tìm thấy thông tin này trong tài liệu.";

    private static final String PREFIX = normalize("Không tìm thấy thông tin này trong tài liệu");

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

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }
}
