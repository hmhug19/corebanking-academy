package vn.com.coreacademy.assistant;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.util.unit.DataSize;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.drafting.CourseDraftingService;
import vn.com.coreacademy.assistant.drafting.DraftingViews.SessionView;
import vn.com.coreacademy.assistant.drafting.DraftingViews.StartFields;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.assistant.tool.StartCourseDraftingTool;
import vn.com.coreacademy.assistant.tool.ToolOutcome;
import vn.com.coreacademy.assistant.tool.TurnState;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.identity.dto.RoleGrant;
import vn.com.coreacademy.identity.dto.UserRights;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** start_course_drafting: the document, caller and conversation come from the turn; every answer is the tool's. */
class StartCourseDraftingToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final CourseDraftingService drafting = mock(CourseDraftingService.class);
    private final KnowledgeProperties knowledge = mock(KnowledgeProperties.class);
    private final StartCourseDraftingTool tool = new StartCourseDraftingTool(drafting, knowledge);

    StartCourseDraftingToolTest() {
        when(knowledge.maxFileSize()).thenReturn(DataSize.ofMegabytes(15));
    }

    private static AgentContext as(String role) {
        return new AgentContext("000004", null, new UserRights(List.of(new RoleGrant(role, role, null)), List.of(),
            Map.of())).withConversation(31L);
    }

    private static SessionView session(long id) {
        return new SessionView(id, "active", 1, null, null, 31L, null, null, List.of(), null, null);
    }

    @Test
    void withADocumentItStartsASessionForThisConversationAndOpensTheNewCoursePageOnIt() {
        when(drafting.startFromSource(anyString(), anyLong(), any(), any())).thenReturn(session(12));

        ToolOutcome outcome = tool.run(JSON.createObjectNode().put("title", " Mở tài khoản ")
                .put("targetLearners", "Giao dịch viên mới").put("level", "basic"),
            as("INSTRUCTOR").withAttachment(55L), new TurnState());

        ToolOutcome.Navigate navigate = (ToolOutcome.Navigate) outcome;
        assertThat(navigate.path()).isEqualTo("/admin/courses/new?session=12");
        assertThat(navigate.title()).isEqualTo("Khóa học mới");
        assertThat(navigate.prefill()).isNull();
        assertThat(navigate.text()).contains("Đã chốt");
        ArgumentCaptor<StartFields> hints = ArgumentCaptor.forClass(StartFields.class);
        verify(drafting).startFromSource(eq("000004"), eq(55L), hints.capture(), eq(31L));
        assertThat(hints.getValue()).isEqualTo(new StartFields("Mở tài khoản", "Giao dịch viên mới", "basic", null));
    }

    @Test
    void valuesOutsideTheCoursesBoundsAreDroppedNotPassedOn() {
        when(drafting.startFromSource(anyString(), anyLong(), any(), any())).thenReturn(session(12));

        tool.run(JSON.createObjectNode().put("title", "").put("level", "BEGINNER").put("ownerOrgId", 9),
            as("ADMIN").withAttachment(55L), new TurnState());

        verify(drafting).startFromSource("000004", 55L, StartFields.NONE, 31L);
    }

    @Test
    void withoutADocumentItAsksForOneAndStartsNothing() {
        ToolOutcome outcome = tool.run(JSON.createObjectNode(), as("INSTRUCTOR"), new TurnState());

        assertThat(outcome).isInstanceOf(ToolOutcome.Reply.class);
        assertThat(((ToolOutcome.Reply) outcome).text()).contains("đính kèm tài liệu").contains(".pdf")
            .contains("tối đa 15 MB");
        verifyNoInteractions(drafting);
    }

    @Test
    void onlyInstructorsAndAdminsHaveItAndALearnerOrReportViewerIsToldItIsForInstructors() {
        assertThat(tool.availableTo(as("LEARNER"))).isFalse();
        assertThat(tool.availableTo(as("REPORT_VIEWER"))).isFalse();
        assertThat(tool.availableTo(as("INSTRUCTOR"))).isTrue();
        assertThat(tool.availableTo(as("SUPER_ADMIN"))).isTrue();

        ToolOutcome outcome = tool.run(JSON.createObjectNode(), as("LEARNER").withAttachment(55L), new TurnState());

        assertThat(outcome).isEqualTo(new ToolOutcome.Reply("Tính năng tạo khóa học dành cho giảng viên."));
        verifyNoInteractions(drafting);
    }

    @Test
    void aRefusedStartIsAnsweredWithItsOwnMessageAndNothingNavigates() {
        when(drafting.startFromSource(anyString(), anyLong(), any(), any())).thenThrow(BusinessException.field(
            AssistantResponseCode.JOB_RATE_LIMITED, "limit", "DAILY_LIMIT", "Bạn đã tạo 3 khóa học bằng AI trong ngày."));

        ToolOutcome limited = tool.run(JSON.createObjectNode(), as("INSTRUCTOR").withAttachment(55L), new TurnState());

        assertThat(limited).isEqualTo(new ToolOutcome.Reply("Bạn đã tạo 3 khóa học bằng AI trong ngày."));

        doThrow(new AccessDeniedException("scope")).when(drafting).startFromSource(anyString(), anyLong(), any(),
            any());
        assertThat(tool.run(JSON.createObjectNode(), as("INSTRUCTOR").withAttachment(55L), new TurnState()))
            .isEqualTo(new ToolOutcome.Reply("Tính năng tạo khóa học dành cho giảng viên."));
    }

    @Test
    void theDefinitionNeedsNoArgumentAndOffersOnlyWhatTheUserMaySay() {
        var definition = tool.definition(as("INSTRUCTOR"));

        assertThat(definition.name()).isEqualTo("start_course_drafting");
        assertThat(tool.actsForUser()).isTrue();
        assertThat(definition.parameters().path("required").isMissingNode()).isTrue();
        assertThat(definition.parameters().path("properties").fieldNames()).toIterable()
            .containsExactlyInAnyOrder("title", "targetLearners", "level");
        assertThat(definition.parameters().path("properties").path("level").path("enum")).extracting(Object::toString)
            .containsExactly("\"basic\"", "\"intermediate\"", "\"advanced\"");
    }
}
