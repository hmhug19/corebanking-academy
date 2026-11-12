package vn.com.coreacademy.assistant.eval;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The retrieval golden set ({@code assistant/eval/retrieval/}): the fixture documents and the cases that say which
 * section of which document a search must bring back for a user. {@link RetrievalEval} runs it against the real
 * pipeline; {@link RetrievalSetTest} keeps the files consistent in the default run.
 */
public final class RetrievalSet {
    static final String FIXTURES = "/assistant/eval/retrieval/fixtures.json";
    static final String CASES = "/assistant/eval/retrieval/retrieval-set.json";

    public static final List<String> GROUPS = List.of("plain", "codes", "follow_up", "empty", "permission");

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Fixtures(int version, List<User> users, List<Doc> documents) {
        public Doc doc(String id) {
            return documents.stream().filter(doc -> doc.id().equals(id)).findFirst().orElse(null);
        }
    }

    public record User(String id, String unit) {
    }

    /** {@code format}: pdf1 (one column), pdf2 (two columns with a table), docx or md. {@code unit} only for UNIT. */
    public record Doc(String id, String file, String format, String scope, String owner, String unit,
                      List<Section> sections) {
    }

    /** {@code marker} is a phrase that occurs in this section's text and nowhere else in the fixtures. */
    public record Section(String title, String marker, List<String> paragraphs, List<String> steps,
                          List<List<String>> table) {
        public String text() {
            StringBuilder text = new StringBuilder(title).append(' ');
            paragraphs.forEach(paragraph -> text.append(paragraph).append(' '));
            if (steps != null) {
                steps.forEach(step -> text.append(step).append(' '));
            }
            if (table != null) {
                table.forEach(row -> text.append(String.join(" ", row)).append(' '));
            }
            return text.toString();
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cases(int version, List<Case> cases) {
    }

    public record Expect(String doc, String marker) {
    }

    /** {@code expect} is null for {@code empty} cases and for a {@code permission} case that must find nothing. */
    public record Case(String id, String group, String user, String previous, String question, Expect expect,
                       String forbidden) {
    }

    public static Fixtures fixtures() {
        return read(FIXTURES, Fixtures.class);
    }

    public static Cases cases() {
        return read(CASES, Cases.class);
    }

    private static <T> T read(String resource, Class<T> type) {
        try (InputStream in = RetrievalSet.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + resource);
            }
            return new ObjectMapper().readValue(in, type);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private RetrievalSet() {
    }
}
