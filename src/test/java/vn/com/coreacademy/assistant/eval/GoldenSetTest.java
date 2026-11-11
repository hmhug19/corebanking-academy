package vn.com.coreacademy.assistant.eval;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.eval.GoldenSet.Answer;
import vn.com.coreacademy.assistant.skill.SkillCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/** The golden set is well formed, so a live eval run never fails on the data instead of the model. */
class GoldenSetTest {
    private final GoldenSet set = GoldenSet.load();

    @Test
    void hasTheSizesTheSpecAsksFor() {
        assertThat(set.answers().size() + set.notFound().size()).isBetween(40, 60);
        assertThat(set.injection()).hasSize(10);
        assertThat(set.toolChoice()).hasSize(10);
        assertThat(set.version()).isNotBlank();
    }

    @Test
    void everyCaseIdIsUniqueAndEveryPassageItNamesExists() {
        Set<String> ids = new HashSet<>();
        set.answers().forEach(answer -> assertThat(ids.add(answer.id())).as(answer.id()).isTrue());
        set.notFound().forEach(item -> assertThat(ids.add(item.id())).as(item.id()).isTrue());
        set.injection().forEach(item -> assertThat(ids.add(item.id())).as(item.id()).isTrue());
        set.toolChoice().forEach(item -> assertThat(ids.add(item.id())).as(item.id()).isTrue());
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
    void anUnapprovedSetSaysResultsAreAdvisory() {
        assertThat(set.approved()).isEqualTo(set.approval().approvedBy() != null && set.approval().approvedAt() != null);
        assertThat(set.note()).contains("advisory");
    }
}
