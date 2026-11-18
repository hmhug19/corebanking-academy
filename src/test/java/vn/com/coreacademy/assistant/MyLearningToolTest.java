package vn.com.coreacademy.assistant;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.tool.MyLearningTool;
import vn.com.coreacademy.assistant.tool.ToolOutcome;
import vn.com.coreacademy.assistant.tool.TurnState;
import vn.com.coreacademy.learning.dto.LearningDetails.CourseCard;
import vn.com.coreacademy.learning.service.LearningSearchService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MyLearningToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AgentContext CONTEXT = new AgentContext("000004", null, null);
    private static final Instant PAST = Instant.parse("2026-11-13T05:00:00Z");
    private static final Instant SOON = Instant.parse("2026-12-20T05:00:00Z");

    private final LearningSearchService learning = mock(LearningSearchService.class);
    private final MyLearningTool tool = new MyLearningTool(learning);

    private static CourseCard card(long id, String title, String status, double progress, Instant dueAt,
                                   boolean overdue) {
        return new CourseCard(id, id, "C" + id, title, null, null, null, null, null, null, 0, null, 0, id, status,
            BigDecimal.valueOf(progress), dueAt, dueAt != null, overdue, null, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null, null, null, false, null, null);
    }

    private String run(String filter) {
        JsonNode arguments = filter == null ? JSON.createObjectNode() : JSON.createObjectNode().put("filter", filter);
        return ((ToolOutcome.Result) tool.run(arguments, CONTEXT, new TurnState())).text();
    }

    @Test
    void overdueAsksTheServiceForTheCallersOwnOverdueCoursesAndListsTitleDueAndProgress() {
        when(learning.mineFor(eq("000004"), eq(null), eq(true), anyInt())).thenReturn(List.of(
            card(1, "Test lần 1 ngày 13/11", "in_progress", 40, PAST, true)));

        String text = run("overdue");

        assertThat(text).contains("«Test lần 1 ngày 13/11»").contains("hạn: 13/11/2026").contains("tiến độ: 40%")
            .contains("quá hạn").doesNotContain("http");
    }

    @Test
    void anEmptyAnswerSaysTheListIsReallyEmptyAndOnlyThenMayTheModelSayNone() {
        when(learning.mineFor(eq("000004"), eq(null), eq(true), anyInt())).thenReturn(List.of());

        assertThat(run("overdue")).contains("Danh sách rỗng").contains("được phép nói là không có");
    }

    @Test
    void upcomingKeepsOnlyUnfinishedNotOverdueCoursesWithADueDateSoonestFirst() {
        when(learning.mineFor(eq("000004"), eq(null), eq(null), anyInt())).thenReturn(List.of(
            card(1, "Đã hết hạn", "in_progress", 10, PAST, true),
            card(2, "Xong rồi", "completed", 100, SOON, false),
            card(3, "Không hạn", "in_progress", 5, null, false),
            card(4, "Sắp tới B", "not_started", 0, SOON.plusSeconds(86_400), false),
            card(5, "Sắp tới A", "in_progress", 50, SOON, false)));

        String text = run("upcoming");

        assertThat(text).contains("Sắp tới A").contains("Sắp tới B").doesNotContain("Đã hết hạn")
            .doesNotContain("Xong rồi").doesNotContain("Không hạn");
        assertThat(text.indexOf("Sắp tới A")).isLessThan(text.indexOf("Sắp tới B"));
    }

    @Test
    void aStatusFilterIsPassedToTheServiceAndWithdrawnCoursesAreDropped() {
        when(learning.mineFor(eq("000004"), eq("completed"), eq(null), anyInt())).thenReturn(List.of(
            card(1, "Đã học", "completed", 100, null, false), card(2, "Đã rút", "withdrawn", 0, null, false)));

        String text = run("completed");

        assertThat(text).contains("Đã học").contains("đã hoàn thành").contains("không có hạn")
            .doesNotContain("Đã rút");
    }

    @Test
    void noFilterMeansAllAndAnUnknownFilterIsRefusedWithoutReadingAnything() {
        when(learning.mineFor(eq("000004"), eq(null), eq(null), anyInt())).thenReturn(List.of(
            card(1, "Khóa A", "not_started", 0, null, false)));

        assertThat(run(null)).contains("Khóa A").contains("chưa bắt đầu");
        assertThat(run("tất cả của người khác")).contains("Bộ lọc không hợp lệ");
        verify(learning, never()).mineFor(any(), eq("tất cả của người khác"), any(), anyInt());
    }

    @Test
    void theListIsCutAtTheLimitAndSaysHowManyAreLeft() {
        List<CourseCard> many = new ArrayList<>();
        for (int i = 1; i <= MyLearningTool.MAX_COURSES + 3; i++) {
            many.add(card(i, "Khóa " + i, "in_progress", 1, SOON, false));
        }
        when(learning.mineFor(eq("000004"), eq(null), eq(null), anyInt())).thenReturn(many);

        assertThat(run("all")).contains("Còn 3 khóa nữa");
    }

    @Test
    void theToolOnlyReadsAndTakesTheUserFromTheContext() {
        assertThat(tool.actsForUser()).isFalse();
        assertThat(tool.readsSharedContent()).isFalse();
        assertThat(tool.availableTo(CONTEXT)).isTrue();
        assertThat(tool.definition(CONTEXT).parameters().path("properties").has("userId")).isFalse();
    }
}
