package vn.com.coreacademy.assistant;

import java.util.List;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.skill.Skill;

import static org.assertj.core.api.Assertions.assertThat;

class SkillRequiredToolsTest {
    private static Skill skill(List<String> required) {
        return new Skill("s", "d", List.of("a", "b", "c"), List.of(), "system", "", "", required);
    }

    @Test
    void aTurnMustCallWhenAnyOfTheRequiredToolsIsOffered() {
        Skill skill = skill(List.of("a", "b"));

        assertThat(skill.mustCall(List.of("b", "c"))).isTrue();
        assertThat(skill.requiredAmong(List.of("b", "c"))).containsExactly("b");
        assertThat(skill.mustCall(List.of("c"))).isFalse();
        assertThat(skill.mustCall(List.of())).isFalse();
    }

    @Test
    void aSkillWithNoRequiredToolNeverMustCall() {
        assertThat(skill(List.of()).mustCall(List.of("a"))).isFalse();
        assertThat(skill(null).requiredTools()).isEmpty();
        assertThat(new Skill("s", "d", List.of("a"), List.of(), "x", "", "").mustCall(List.of("a"))).isFalse();
    }

    @Test
    void theOldSingleToolConstructorStillWorks() {
        Skill skill = new Skill("s", "d", List.of("a"), List.of(), "x", "", "", "a");

        assertThat(skill.requiredTools()).containsExactly("a");
        assertThat(skill.mustCall(List.of("a"))).isTrue();
        assertThat(new Skill("s", "d", List.of("a"), List.of(), "x", "", "", (String) null).requiredTools()).isEmpty();
    }
}
