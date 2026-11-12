package vn.com.coreacademy.knowledge;

import org.junit.jupiter.api.Test;
import vn.com.coreacademy.knowledge.entity.DocumentScope;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DocumentScopeTest {
    @Test
    void aClientMayAskForPersonalUnitOrGlobalAndBlankMeansNone() {
        assertThat(DocumentScope.fromRequest("personal")).isEqualTo(DocumentScope.PERSONAL);
        assertThat(DocumentScope.fromRequest("unit")).isEqualTo(DocumentScope.UNIT);
        assertThat(DocumentScope.fromRequest("global")).isEqualTo(DocumentScope.GLOBAL);
        assertThat(DocumentScope.fromRequest(" ")).isNull();
        assertThat(DocumentScope.fromRequest(null)).isNull();
    }

    @Test
    void aCourseSourceIsNeverARequestableScope() {
        assertThat(DocumentScope.fromValue("course_source")).isEqualTo(DocumentScope.COURSE_SOURCE);
        assertThatThrownBy(() -> DocumentScope.fromRequest("course_source")).isInstanceOf(IllegalArgumentException.class);
    }
}
