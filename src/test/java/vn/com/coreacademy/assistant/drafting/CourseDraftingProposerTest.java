package vn.com.coreacademy.assistant.drafting;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import vn.com.coreacademy.assistant.config.AssistantBudget;
import vn.com.coreacademy.assistant.config.AssistantModels;
import vn.com.coreacademy.assistant.config.GenerationProperties;
import vn.com.coreacademy.assistant.course.GenerationSchemas;
import vn.com.coreacademy.assistant.course.GenerationStepRunner.GenerationException;
import vn.com.coreacademy.assistant.course.SourceSummariser;
import vn.com.coreacademy.assistant.course.SourceTextRepository;
import vn.com.coreacademy.assistant.course.SourceTextRepository.Piece;
import vn.com.coreacademy.assistant.course.StepNotReadyException;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.Candidate;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.CourseBrief;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.DraftingStepRow;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.SessionRow;
import vn.com.coreacademy.assistant.drafting.DraftingStepProposer.Proposal;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.knowledge.ai.OllamaClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Step 1 of Soạn cùng trợ lý with the model faked: what is kept, what is dropped, and when the session fails. */
class CourseDraftingProposerTest {
    private static final long DOCUMENT = 9L;
    private static final long ORG = 1L;
    private static final String SUMMARY = "{\"title\":\"Mở tài khoản\",\"summary\":\"Điều kiện và hồ sơ.\","
        + "\"keyPoints\":[\"Hồ sơ\"]}";

    private final ObjectMapper json = new ObjectMapper();
    private final SourceTextRepository sources = mock(SourceTextRepository.class);
    private final OllamaClient ollama = mock(OllamaClient.class);
    private final CourseDraftingRepository drafting = mock(CourseDraftingRepository.class);
    private final CourseDraftingProposer proposer = new CourseDraftingProposer(new SourceSummariser(sources, ollama,
        new SkillCatalog(), AssistantModels.defaults(), json, AssistantBudget.defaults(),
        new GenerationProperties(3, Duration.ofSeconds(3))), drafting, json);

    @BeforeEach
    void aReadableDocumentAndTheUnitsLists() {
        when(sources.status(DOCUMENT)).thenReturn(Optional.of(new SourceTextRepository.Status("ready", null)));
        when(sources.pieces(DOCUMENT)).thenReturn(List.of(
            new Piece(0, "Điều kiện mở tài khoản thanh toán cho khách hàng cá nhân. ".repeat(10), 1, 1, 10)));
        when(drafting.programmes(anyLong(), anyInt())).thenReturn(List.of(new Candidate(12, "Bán lẻ", 3),
            new Candidate(13, "Kho quỹ", 4)));
        when(drafting.modules(anyLong(), anyInt())).thenReturn(List.of(new Candidate(34, "Tiền gửi", 3),
            new Candidate(35, "Ngân quỹ", 4)));
        when(drafting.codeTaken(anyString())).thenReturn(false);
    }

    private static SessionRow session(String hints, String summary) {
        return new SessionRow(5, "000005", ORG, DOCUMENT, null, null, null, 1, "active", null, hints, summary, null,
            null);
    }

    private static DraftingStepRow step(int number) {
        return new DraftingStepRow(7, 5, number, "proposing", null, "[]", null, 1, null, false, null);
    }

    private static String basics(String ids) {
        return """
            {"title":"Mở tài khoản thanh toán","summary":"Quy trình mở tài khoản.","objectives":["Thực hiện được"],
             "level":"basic","defaultRequirement":"mandatory",%s"unsure":["summary"]}
            """.formatted(ids);
    }

    private void modelAnswers(String basics) {
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(SUMMARY, basics);
    }

    @Test
    void aProposalWithIdsFromTheListsKeepsThemAndGetsAServerCode() {
        modelAnswers(basics("\"programId\":12,\"moduleId\":34,"));

        Proposal result = proposer.propose(session("{}", null), step(1));

        JsonNode proposal = result.proposal();
        assertThat(proposal.path("title").asText()).isEqualTo("Mở tài khoản thanh toán");
        assertThat(proposal.path("programId").asLong()).isEqualTo(12);
        assertThat(proposal.path("moduleId").asLong()).isEqualTo(34);
        assertThat(proposal.path("level").asText()).isEqualTo("basic");
        assertThat(proposal.path("defaultRequirement").asText()).isEqualTo("mandatory");
        assertThat(proposal.path("code").asText()).matches("AI-[A-Z2-9]{8}");
        assertThat(proposal.path("unsure")).extracting(JsonNode::asText).containsExactly("summary");
        assertThat(result.notes()).containsExactly("Hãy kiểm tra lại mô tả ngắn.");
        assertThat(result.sourceSummary().path("sections")).hasSize(1);
    }

    @Test
    void idsOutsideTheListsAreDroppedAndNamedInTheNotes() {
        modelAnswers(basics("\"programId\":999,\"moduleId\":34,"));

        Proposal result = proposer.propose(session("{}", null), step(1));

        assertThat(result.proposal().path("programId").isNull()).isTrue();
        // a module without a kept programme is dropped too: the course refuses a module alone
        assertThat(result.proposal().path("moduleId").isNull()).isTrue();
        assertThat(result.proposal().path("unsure")).extracting(JsonNode::asText)
            .containsExactly("summary", "programId", "moduleId");
        assertThat(result.notes()).contains(CourseDraftingProposer.NO_PROGRAMME, CourseDraftingProposer.NO_MODULE);
    }

    @Test
    void aModuleOfAnotherFieldThanTheProgrammeIsDropped() {
        modelAnswers(basics("\"programId\":12,\"moduleId\":35,"));

        Proposal result = proposer.propose(session("{}", null), step(1));

        assertThat(result.proposal().path("programId").asLong()).isEqualTo(12);
        assertThat(result.proposal().path("moduleId").isNull()).isTrue();
        assertThat(result.notes()).containsExactly(CourseDraftingProposer.NO_MODULE, "Hãy kiểm tra lại mô tả ngắn.");
    }

    @Test
    void theModelIsOnlyOfferedTheUnitsListsAndTheSummaryNotTheDocument() {
        modelAnswers(basics(""));

        proposer.propose(session("{\"targetLearners\":\"Giao dịch viên mới\"}", null), step(1));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OllamaClient.Message>> calls = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<JsonNode> formats = ArgumentCaptor.forClass(JsonNode.class);
        verify(ollama, times(2)).chat(calls.capture(), any(), formats.capture(), any());
        String asked = calls.getAllValues().get(1).get(1).content();
        assertThat(asked).contains("12: Bán lẻ [3]", "34: Tiền gửi [3]", "Giao dịch viên mới", "Điều kiện và hồ sơ.")
            .doesNotContain("Điều kiện mở tài khoản thanh toán cho khách hàng cá nhân.");
        assertThat(formats.getAllValues().get(1)).isEqualTo(GenerationSchemas.format(GenerationSchemas.DRAFT_BASICS));
    }

    @Test
    void whatTheAuthorSaidWinsOverTheModel() {
        modelAnswers(basics("\"programId\":12,"));

        Proposal result = proposer.propose(session("{\"title\":\"Khóa của tôi\",\"level\":\"advanced\"}", null),
            step(1));

        assertThat(result.proposal().path("title").asText()).isEqualTo("Khóa của tôi");
        assertThat(result.proposal().path("level").asText()).isEqualTo("advanced");
    }

    @Test
    void aTakenCodeIsReplacedByOneNobodyUses() {
        modelAnswers(basics(""));
        when(drafting.codeTaken(anyString())).thenReturn(true, true, false);

        Proposal result = proposer.propose(session("{}", null), step(1));

        verify(drafting, times(3)).codeTaken(anyString());
        ArgumentCaptor<String> tried = ArgumentCaptor.forClass(String.class);
        verify(drafting, times(3)).codeTaken(tried.capture());
        assertThat(result.proposal().path("code").asText()).isEqualTo(tried.getAllValues().get(2));
    }

    @Test
    void aSummaryTheSessionAlreadyHasIsReusedWithoutReadingTheDocument() {
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(basics(""));

        Proposal result = proposer.propose(session("{}", "{\"sections\":[{\"title\":\"Mở\",\"summary\":\"Hồ sơ.\"}]}"),
            step(1));

        verify(sources, never()).pieces(anyLong());
        verify(ollama, times(1)).chat(anyList(), any(), any(), any());
        assertThat(result.sourceSummary()).isNull();
    }

    @Test
    void anAnswerThatNeverFitsTheSchemaFailsWithAReadableMessage() {
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(SUMMARY, "{\"title\":\"\"}");

        assertThatThrownBy(() -> proposer.propose(session("{}", null), step(1)))
            .isInstanceOf(GenerationException.class).hasMessageContaining("không hợp lệ");
        verify(ollama, times(1 + SourceSummariser.ATTEMPTS)).chat(anyList(), any(), any(), any());
    }

    @Test
    void anUnreadableDocumentFailsWithoutAskingTheModel() {
        when(sources.status(DOCUMENT)).thenReturn(Optional.of(new SourceTextRepository.Status("failed", "x")));

        assertThatThrownBy(() -> proposer.propose(session("{}", null), step(1)))
            .isInstanceOf(GenerationException.class).hasMessage(SourceSummariser.UNREADABLE);
        verify(ollama, never()).chat(anyList(), any(), any(), any());
    }

    @Test
    void aTooThinDocumentFailsWithoutAskingTheModel() {
        when(sources.pieces(DOCUMENT)).thenReturn(List.of(new Piece(0, "Nội dung ngắn.", 1, 1, 1)));

        assertThatThrownBy(() -> proposer.propose(session("{}", null), step(1)))
            .isInstanceOf(GenerationException.class).hasMessage(SourceSummariser.TOO_THIN);
        verify(ollama, never()).chat(anyList(), any(), any(), any());
    }

    @Test
    void aDocumentStillBeingReadWaits() {
        when(sources.status(DOCUMENT)).thenReturn(Optional.of(new SourceTextRepository.Status("processing", null)));

        assertThatThrownBy(() -> proposer.propose(session("{}", null), step(1)))
            .isInstanceOf(StepNotReadyException.class);
    }

    @Test
    void aStepNotBuiltYetFailsReadably() {
        assertThatThrownBy(() -> proposer.propose(session("{}", null), step(3)))
            .isInstanceOf(GenerationException.class).hasMessage(CourseDraftingProposer.NOT_SUPPORTED);
    }

    // ---- step 2 ----------------------------------------------------------------------------------------------------

    private static final String SECTIONS = """
        {"sections":[{"key":"s1","title":"Điều kiện mở tài khoản","summary":"Ai được mở."},
                     {"key":"s2","title":"Hồ sơ và giấy tờ","summary":"Giấy đề nghị, căn cước."}]}
        """;

    private static SessionRow outlineSession(String summary) {
        return new SessionRow(5, "000005", ORG, DOCUMENT, null, 70L, 71L, 2, "active", null,
            "{\"targetLearners\":\"Giao dịch viên mới\"}", summary, null, null);
    }

    private static String outline(String firstSection) {
        return outline(firstSection, "s2");
    }

    private static String outline(String firstSection, String secondSection) {
        return """
            {"chapters":[{"title":"Chuẩn bị","lessons":[
              {"title":"Điều kiện","description":"Ai được mở tài khoản.","section":"%s"},
              {"title":"Hồ sơ","description":"Giấy tờ khách hàng nộp.","section":"%s"}]}]}
            """.formatted(firstSection, secondSection);
    }

    @Test
    void theOutlineNamesEachLessonsSourceSectionAndHasNoLessonContent() {
        when(drafting.courseBrief(71L)).thenReturn(Optional.of(new CourseBrief("Mở tài khoản thanh toán",
            "Quy trình tại quầy.", "basic", List.of("Thực hiện được việc mở tài khoản"))));
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(outline("s1"));

        Proposal result = proposer.propose(outlineSession(SECTIONS), step(2));

        JsonNode chapter = result.proposal().path("chapters").get(0);
        assertThat(chapter.path("title").asText()).isEqualTo("Chuẩn bị");
        JsonNode first = chapter.path("lessons").get(0);
        assertThat(first.path("title").asText()).isEqualTo("Điều kiện");
        assertThat(first.path("description").asText()).isEqualTo("Ai được mở tài khoản.");
        assertThat(first.path("sourceSection").asText()).isEqualTo("Điều kiện mở tài khoản");
        assertThat(chapter.path("lessons").get(1).path("sourceSection").asText()).isEqualTo("Hồ sơ và giấy tờ");
        assertThat(result.proposal().toString()).doesNotContain("blocks").doesNotContain("\"section\"");
        assertThat(result.notes()).containsExactly("Đã đề xuất 1 chương, 2 bài; nội dung từng bài để trống.");
        assertThat(result.sourceSummary()).isNull();
        verify(sources, never()).pieces(anyLong());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<OllamaClient.Message>> calls = ArgumentCaptor.forClass(List.class);
        ArgumentCaptor<JsonNode> formats = ArgumentCaptor.forClass(JsonNode.class);
        verify(ollama).chat(calls.capture(), any(), formats.capture(), any());
        assertThat(calls.getValue().get(1).content()).contains("s1", "Điều kiện mở tài khoản", "Ai được mở.",
            "Mở tài khoản thanh toán", "Giao dịch viên mới");
        assertThat(formats.getValue()).isEqualTo(GenerationSchemas.format(GenerationSchemas.DRAFT_OUTLINE));
    }

    @Test
    void aLessonFromASectionTheSummaryDoesNotHaveIsAskedAgain() {
        when(drafting.courseBrief(71L)).thenReturn(Optional.empty());
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(outline("s9"), outline("s1"));

        Proposal result = proposer.propose(outlineSession(SECTIONS), step(2));

        verify(ollama, times(2)).chat(anyList(), any(), any(), any());
        assertThat(result.proposal().path("chapters").get(0).path("lessons").get(0).path("sourceSection").asText())
            .isEqualTo("Điều kiện mở tài khoản");
    }

    @Test
    void anOutlineThatNeverNamesKnownSectionsFailsReadably() {
        when(drafting.courseBrief(71L)).thenReturn(Optional.empty());
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(outline("s9"));

        assertThatThrownBy(() -> proposer.propose(outlineSession(SECTIONS), step(2)))
            .isInstanceOf(GenerationException.class).hasMessageContaining("không hợp lệ");
        verify(ollama, times(SourceSummariser.ATTEMPTS)).chat(anyList(), any(), any(), any());
    }

    @Test
    void anOutlineWithoutASummaryYetReadsTheDocumentOnce() {
        when(drafting.courseBrief(71L)).thenReturn(Optional.empty());
        when(ollama.chat(anyList(), any(), any(), any())).thenReturn(SUMMARY, outline("s1", "s1"));

        Proposal result = proposer.propose(outlineSession(null), step(2));

        assertThat(result.sourceSummary().path("sections")).hasSize(1);
        assertThat(result.proposal().path("chapters").get(0).path("lessons").get(0).path("sourceSection").asText())
            .isEqualTo("Mở tài khoản");
    }
}
