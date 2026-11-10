package vn.com.coreacademy.assistant.skill;

import java.util.ArrayList;
import java.util.List;

/**
 * The follow-up questions a model may append to an answer: the {@link #MARKER} line, then lines starting with "- ".
 * The server cuts the block out of the answer and sends the lines as {@code suggestions}; a block that does not parse
 * is dropped without a trace.
 */
public final class Suggestions {
    public static final String MARKER = "@@GOI_Y@@";
    public static final int MAX_ITEMS = 3;
    public static final int MAX_CHARS = 120;

    private Suggestions() {
    }

    public record Split(String answer, List<String> suggestions) {
    }

    public static Split split(String text) {
        if (text == null) {
            return new Split("", List.of());
        }
        int at = text.indexOf(MARKER);
        if (at < 0) {
            return new Split(text, List.of());
        }
        List<String> items = new ArrayList<>();
        for (String line : text.substring(at + MARKER.length()).split("\\R")) {
            String item = line.strip();
            if (!item.startsWith("- ")) {
                continue;
            }
            item = item.substring(2).strip();
            if (!item.isEmpty() && item.length() <= MAX_CHARS && !item.contains(MARKER) && items.size() < MAX_ITEMS) {
                items.add(item);
            }
        }

        return new Split(text.substring(0, at).strip(), List.copyOf(items));
    }

    /**
     * Passes streamed text through until the marker shows, so the block never reaches the client. The end of a chunk
     * that could still become the marker is held back until the next chunk decides it.
     */
    public static final class Filter {
        private final StringBuilder held = new StringBuilder();
        private boolean cut;

        /** The part of {@code delta} that may be shown now; may be empty. */
        public String accept(String delta) {
            if (cut) {
                return "";
            }
            held.append(delta);
            String text = held.toString();
            int at = text.indexOf(MARKER);
            if (at >= 0) {
                cut = true;
                held.setLength(0);

                return text.substring(0, at).stripTrailing();
            }
            int keep = 0;
            for (int n = Math.min(MARKER.length() - 1, text.length()); n > 0; n--) {
                if (MARKER.startsWith(text.substring(text.length() - n))) {
                    keep = n;
                    break;
                }
            }
            held.setLength(0);
            held.append(text, text.length() - keep, text.length());

            return text.substring(0, text.length() - keep);
        }
    }
}
