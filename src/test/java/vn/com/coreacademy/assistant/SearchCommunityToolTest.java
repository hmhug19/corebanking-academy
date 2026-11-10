package vn.com.coreacademy.assistant;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vn.com.coreacademy.assistant.agent.AgentContext;
import vn.com.coreacademy.assistant.tool.FinishedAnswer;
import vn.com.coreacademy.assistant.tool.SearchCommunityTool;
import vn.com.coreacademy.assistant.tool.ToolOutcome;
import vn.com.coreacademy.assistant.tool.TurnState;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.community.service.CommunityAssistantAdapter;
import vn.com.coreacademy.community.service.CommunityAssistantAdapter.Source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SearchCommunityToolTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AgentContext CONTEXT = new AgentContext("000004", null, null);

    private final CommunityAssistantAdapter community = mock(CommunityAssistantAdapter.class);
    private final SearchCommunityTool tool = new SearchCommunityTool(community);
    private final TurnState turn = new TurnState();

    private static Source source(long id, String title) {
        return new Source(id, id + 100, title, "/community/contents/" + id + "?revisionId=" + (id + 100));
    }

    private void finds(Source... sources) {
        when(community.search(anyString(), anyString())).thenReturn(List.of(sources));
        when(community.stillReadable(anyString(), any())).thenReturn(true);
    }

    private FinishedAnswer searchThenFinish(String query, String modelText) {
        JsonNode arguments = JSON.createObjectNode().put("query", query);
        tool.run(arguments, CONTEXT, turn);
        return turn.finisher().orElseThrow().finish(modelText);
    }

    @Test
    void titlesKeepAmpersandAndAngleBracketsReadable() {
        finds(source(1, "Lãi & phí <KH> doanh nghiệp"));

        var answer = searchThenFinish("lãi phí", "Có một bài về lãi và phí.").answer();

        assertThat(answer).contains("[Lãi & phí <KH> doanh nghiệp](/community/contents/1?revisionId=101)");
        assertThat(answer).doesNotContain("&amp;").doesNotContain("&lt;");
    }

    @Test
    void aTitleCannotBreakTheLinkSyntax() {
        finds(source(2, "Mẫu [cũ](https://evil.test) \\ xuống\ndòng]"));

        var answer = searchThenFinish("mẫu", "Có một bài.").answer();

        var link = answer.lines().filter(line -> line.startsWith("- [")).findFirst().orElseThrow();
        assertThat(link).isEqualTo("- [Mẫu \\[cũ\\](https://evil.test) \\\\ xuống dòng\\]](/community/contents/2?revisionId=102)");
        assertThat(answer).doesNotContain("\n]");
    }

    @Test
    void theAnswerIsTheModelSummaryFollowedByTheServerLinks() {
        finds(source(1, "Đối soát cuối ngày"), source(3, "Lỗi EOD thường gặp"));

        var answer = searchThenFinish("eod", "Cộng đồng có hai bài liên quan đến EOD.").answer();

        assertThat(answer).startsWith("Cộng đồng có hai bài liên quan đến EOD.\n\n");
        assertThat(answer).contains("- [Đối soát cuối ngày](/community/contents/1?revisionId=101)")
            .contains("- [Lỗi EOD thường gặp](/community/contents/3?revisionId=103)");
    }

    @Test
    void linksOfTheModelAreDroppedSoLinksComeOnlyFromTheServer() {
        finds(source(1, "Đối soát cuối ngày"));

        var answer = searchThenFinish("eod",
            "Xem [bài này](https://evil.test/x) hoặc https://evil.test/y và /community/contents/999.").answer();

        assertThat(answer).doesNotContain("evil.test").doesNotContain("](https");
        assertThat(answer).contains("Xem bài này");
        assertThat(answer).contains("(/community/contents/1?revisionId=101)");
    }

    @Test
    void withoutAModelSummaryTheAnswerStillHasALeadSentence() {
        finds(source(1, "Đối soát cuối ngày"));

        var answer = searchThenFinish("eod", "   ").answer();

        assertThat(answer).startsWith("Các nguồn Community phù hợp:\n\n- [Đối soát cuối ngày]");
    }

    @Test
    void noSourcesSaysSoWithoutLinks() {
        when(community.search(anyString(), anyString())).thenReturn(List.of());

        var answer = searchThenFinish("không có", "Bạn xem các bài sau").answer();

        assertThat(answer).isEqualTo("Chưa tìm thấy nguồn Community phù hợp.");
    }

    @Test
    void sourcesAreCheckedAgainBeforeTheyAreShown() {
        finds(source(1, "Đối soát cuối ngày"));
        when(community.stillReadable(anyString(), any())).thenReturn(false);

        var answer = searchThenFinish("eod", "Có một bài.").answer();

        assertThat(answer).isEqualTo("Nguồn hoặc quyền truy cập đã thay đổi. Vui lòng tìm lại.")
            .doesNotContain("/community/");
    }

    @Test
    void anAccessErrorWhileCheckingShowsTheSameRefusal() {
        finds(source(1, "Đối soát cuối ngày"));
        when(community.stillReadable(anyString(), any()))
            .thenThrow(BusinessException.validation("query", "INVALID_VALUE", "x"));

        assertThat(searchThenFinish("eod", "Có một bài.").answer())
            .isEqualTo("Nguồn hoặc quyền truy cập đã thay đổi. Vui lòng tìm lại.");
    }

    @Test
    void theToolResultAsksTheModelForOneSummarySentence() {
        finds(source(1, "Đối soát cuối ngày"));

        var result = ((ToolOutcome.Result) tool.run(JSON.createObjectNode().put("query", "eod"), CONTEXT, turn)).text();

        assertThat(result).contains("một câu").contains("không viết liên kết");
    }
}
