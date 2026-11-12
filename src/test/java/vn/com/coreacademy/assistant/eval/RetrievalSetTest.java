package vn.com.coreacademy.assistant.eval;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vn.com.coreacademy.knowledge.extraction.DocxTextExtractor;
import vn.com.coreacademy.knowledge.extraction.ExtractedText;
import vn.com.coreacademy.knowledge.extraction.MarkdownTextExtractor;
import vn.com.coreacademy.knowledge.extraction.PdfTextExtractor;
import vn.com.coreacademy.knowledge.extraction.TextLine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Keeps the retrieval set and its fixtures consistent; the numbers themselves come from {@link RetrievalEval}. */
class RetrievalSetTest {
    private final RetrievalSet.Fixtures fixtures = RetrievalSet.fixtures();
    private final RetrievalSet.Cases set = RetrievalSet.cases();

    @Test
    void theSetHasFortyToSixtyCasesInTheFiveGroups() {
        assertThat(set.cases()).hasSizeBetween(40, 60);
        Map<String, Long> perGroup = set.cases().stream()
            .collect(Collectors.groupingBy(RetrievalSet.Case::group, Collectors.counting()));
        assertThat(perGroup.keySet()).containsExactlyInAnyOrderElementsOf(RetrievalSet.GROUPS);
        assertThat(perGroup.values()).allMatch(count -> count >= 4);
        assertThat(set.cases()).extracting(RetrievalSet.Case::id).doesNotHaveDuplicates();
    }

    @Test
    void everyExpectationNamesARealSectionOfARealDocumentTheUserMayRead() {
        Map<String, RetrievalSet.User> users = fixtures.users().stream()
            .collect(Collectors.toMap(RetrievalSet.User::id, Function.identity()));
        for (RetrievalSet.Case testCase : set.cases()) {
            assertThat(users).as(testCase.id() + " user").containsKey(testCase.user());
            assertThat(testCase.question()).as(testCase.id()).isNotBlank();
            if (testCase.group().equals("follow_up")) {
                assertThat(testCase.previous()).as(testCase.id() + " previous").isNotBlank();
            }
            if (testCase.expect() != null) {
                RetrievalSet.Doc doc = fixtures.doc(testCase.expect().doc());
                assertThat(doc).as(testCase.id() + " document").isNotNull();
                assertThat(doc.sections().stream().map(RetrievalSet.Section::marker))
                    .as(testCase.id() + " marker").contains(testCase.expect().marker());
                assertThat(visibleTo(doc, users.get(testCase.user()))).as(testCase.id() + " visible").isTrue();
            }
            if (testCase.forbidden() != null) {
                RetrievalSet.Doc forbidden = fixtures.doc(testCase.forbidden());
                assertThat(forbidden).as(testCase.id() + " forbidden").isNotNull();
                assertThat(visibleTo(forbidden, users.get(testCase.user()))).as(testCase.id() + " hidden").isFalse();
            }
            if (testCase.group().equals("empty")) {
                assertThat(testCase.expect()).as(testCase.id()).isNull();
            }
        }
    }

    @Test
    void aMarkerOccursInItsOwnSectionAndNoOtherSection() {
        Set<String> seen = new HashSet<>();
        for (RetrievalSet.Doc doc : fixtures.documents()) {
            for (RetrievalSet.Section section : doc.sections()) {
                String marker = RetrievalScore.normalise(section.marker());
                assertThat(RetrievalScore.normalise(section.text())).as(section.title()).contains(marker);
                assertThat(seen.add(marker)).as("marker used twice: " + marker).isTrue();
            }
        }
    }

    @Test
    void thePermissionGroupHoldsALeakCaseAndAnAllowedCase() {
        List<RetrievalSet.Case> permission = set.cases().stream().filter(c -> c.group().equals("permission")).toList();
        assertThat(permission).anyMatch(c -> c.forbidden() != null);
        assertThat(permission).anyMatch(c -> c.expect() != null);
    }

    @Test
    void theRealExtractorsReadBackWhatTheFixturesWrite(@TempDir Path directory) throws IOException {
        boolean fonts = RetrievalFixtures.font().isPresent();
        for (RetrievalSet.Doc doc : fixtures.documents()) {
            if (doc.format().startsWith("pdf") && !fonts) {
                continue;
            }
            Path file = RetrievalFixtures.write(doc, directory);
            ExtractedText text = switch (doc.format()) {
                case "md" -> new MarkdownTextExtractor().extract(file);
                case "docx" -> new DocxTextExtractor().extract(file);
                default -> new PdfTextExtractor().extract(file);
            };
            String all = RetrievalScore.normalise(text.lines().stream().map(TextLine::text)
                .collect(Collectors.joining(" ")));
            assertThat(all).as(doc.file()).isNotBlank();
            if (!doc.format().equals("pdf2")) {
                // A second column interleaves its lines with the first: that is what the baseline measures.
                for (RetrievalSet.Section section : doc.sections()) {
                    assertThat(all).as(doc.file() + " › " + section.title()).contains(
                        RetrievalScore.normalise(section.marker()));
                }
            }
        }
        assumeTrue(fonts, "PDF fixtures skipped: no TrueType font found (-Dretrieval.eval.font=<path>)");
    }

    @Test
    void scoresRankFindsTheFirstChunkOfTheExpectedDocumentWithTheMarker() {
        var expect = new RetrievalSet.Expect("a", "đủ 18 tuổi");
        var ranked = List.of(new RetrievalScore.Found("b", "đủ 18 tuổi"), new RetrievalScore.Found("a", "khác"),
            new RetrievalScore.Found("a", "Phải  ĐỦ 18\ntuổi mới được"));

        assertThat(RetrievalScore.rank(ranked, expect)).isEqualTo(3);
        assertThat(RetrievalScore.rank(ranked.subList(0, 2), expect)).isZero();
        assertThat(RetrievalScore.leaked(ranked, "b")).isTrue();
        assertThat(RetrievalScore.leaked(ranked, null)).isFalse();
    }

    @Test
    void groupScoresCountRecallMrrEmptyAndLeaks() {
        var asked = new RetrievalSet.Case("P", "plain", "u", null, "q", new RetrievalSet.Expect("a", "m"), null);
        var empty = new RetrievalSet.Case("E", "empty", "u", null, "q", null, null);
        var leak = new RetrievalSet.Case("X", "permission", "u", null, "q", null, "d");
        var outcomes = List.of(new RetrievalScore.Outcome(asked, 2, false, false),
            new RetrievalScore.Outcome(asked, 0, false, false), new RetrievalScore.Outcome(empty, 0, true, false),
            new RetrievalScore.Outcome(leak, 0, false, true));

        var plain = RetrievalScore.group("plain", outcomes);
        assertThat(plain.recallAt5()).isEqualTo(0.5);
        assertThat(plain.recallAt20()).isEqualTo(0.5);
        assertThat(plain.mrr()).isEqualTo(0.25);
        assertThat(RetrievalScore.group("empty", outcomes).correctEmpty()).isEqualTo(1.0);
        assertThat(RetrievalScore.group("permission", outcomes).leaks()).isEqualTo(1);
        assertThat(RetrievalScore.group("plain", outcomes).leaks()).isZero();
    }

    private static boolean visibleTo(RetrievalSet.Doc doc, RetrievalSet.User user) {
        return switch (doc.scope()) {
            case "GLOBAL" -> true;
            case "PERSONAL" -> doc.owner().equals(user.id());
            case "UNIT" -> doc.unit().equals(user.unit());
            default -> false;
        };
    }
}
