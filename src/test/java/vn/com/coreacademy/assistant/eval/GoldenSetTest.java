package vn.com.coreacademy.assistant.eval;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.eval.GoldenSet.Answer;
import vn.com.coreacademy.assistant.eval.GoldenSet.Passage;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.knowledge.repository.DocumentChunkRepository.ChunkHit;
import vn.com.coreacademy.knowledge.service.CitationRule;

import static org.assertj.core.api.Assertions.assertThat;

/** The golden set is well formed, so a live eval run never fails on the data instead of the model. */
class GoldenSetTest {
    private final GoldenSet set = GoldenSet.load();

    @Test
    void hasTheSizesTheSpecAsksFor() {
        assertThat(set.answers().size() + set.notFound().size()).isBetween(40, 60);
        assertThat(set.injection()).hasSize(10);
        assertThat(set.toolChoice()).hasSize(12);
        assertThat(set.followUps()).hasSize(5);
        assertThat(set.version()).isNotBlank();
    }

    @Test
    void everyCaseIdIsUniqueAndEveryPassageItNamesExists() {
        Set<String> ids = new HashSet<>();
        set.answers().forEach(answer -> assertThat(ids.add(answer.id())).as(answer.id()).isTrue());
        set.notFound().forEach(item -> assertThat(ids.add(item.id())).as(item.id()).isTrue());
        set.injection().forEach(item -> assertThat(ids.add(item.id())).as(item.id()).isTrue());
        set.toolChoice().forEach(item -> assertThat(ids.add(item.id())).as(item.id()).isTrue());
        set.followUps().forEach(item -> assertThat(ids.add(item.id())).as(item.id()).isTrue());
        Set<String> known = set.passageById().keySet();
        for (Answer answer : set.answers()) {
            assertThat(known).containsAll(answer.passages());
            assertThat(answer.passages()).containsAll(answer.sources());
            assertThat(answer.points()).isNotEmpty();
        }
        set.notFound().forEach(item -> assertThat(known).containsAll(item.passages()));
    }

    @Test
    void toolCasesNameSkillsAndToolsThatExist() {
        SkillCatalog catalog = new SkillCatalog();
        set.toolChoice().forEach(item -> {
            assertThat(catalog.find(item.expectedSkill())).as(item.id()).isPresent();
            if (item.requestedSkill() != null) {
                assertThat(catalog.find(item.requestedSkill())).as(item.id()).isPresent();
            }
            List<String> offered = catalog.find(item.expectedSkill()).orElseThrow().tools();
            assertThat(item.expectedTool().equals("(none)") || offered.contains(item.expectedTool())).as(item.id())
                .isTrue();
        });
    }

    @Test
    void followUpCasesNameExistingSkillsAndCarryThePreviousExchange() {
        SkillCatalog catalog = new SkillCatalog();
        set.followUps().forEach(item -> {
            assertThat(catalog.find(item.expectedSkill())).as(item.id()).isPresent();
            assertThat(item.previousQuestion()).as(item.id()).isNotBlank();
            assertThat(item.previousAnswer()).as(item.id()).isNotBlank();
            assertThat(item.prompt()).as(item.id()).isNotBlank();
        });
    }

    /**
     * The threshold of the citation support check is tuned so that the correct citations of the set stay: for every
     * answer, a sentence that states its points, citing each source passage, loses no marker. (Real model answers are
     * measured by GoldenSetEval, which needs Ollama.)
     */
    @Test
    void aSentenceStatingTheAnswerPointsKeepsItsMarkerOnEverySourcePassage() {
        for (Answer answer : set.answers()) {
            List<ChunkHit> hits = new ArrayList<>();
            List<Passage> all = set.passages();
            for (String id : answer.passages()) {
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).id().equals(id)) {
                        hits.add(GoldenSet.hit(i, all.get(i)));
                    }
                }
            }
            for (String source : answer.sources()) {
                int n = answer.passages().indexOf(source) + 1;
                String text = all.stream().filter(passage -> passage.id().equals(source)).findFirst().orElseThrow()
                    .text().toLowerCase(java.util.Locale.ROOT);
                // The sentence states the points this passage really contains.
                String stated = answer.points().stream().map(alternatives -> alternatives.stream()
                        .filter(alternative -> text.contains(alternative.toLowerCase(java.util.Locale.ROOT)))
                        .findFirst().orElse(null))
                    .filter(java.util.Objects::nonNull).collect(Collectors.joining(", "));
                if (!stated.isEmpty()) {
                    String sentence = stated + " [" + n + "].";
                    assertThat(CitationRule.apply(sentence, hits).removedMarkers()).as(answer.id() + " " + sentence)
                        .isZero();
                }
            }
        }
    }

    @Test
    void anUnapprovedSetSaysResultsAreAdvisory() {
        assertThat(set.approved()).isEqualTo(set.approval().approvedBy() != null && set.approval().approvedAt() != null);
        assertThat(set.note()).contains("advisory");
    }
}
