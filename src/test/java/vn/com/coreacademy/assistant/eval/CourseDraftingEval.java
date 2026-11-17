package vn.com.coreacademy.assistant.eval;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;
import vn.com.coreacademy.assistant.config.AssistantBudget;
import vn.com.coreacademy.assistant.config.AssistantModels;
import vn.com.coreacademy.assistant.config.GenerationProperties;
import vn.com.coreacademy.assistant.course.GenerationSchemas;
import vn.com.coreacademy.assistant.course.SourceSummariser;
import vn.com.coreacademy.assistant.course.SourceTextRepository;
import vn.com.coreacademy.assistant.course.SourceTextRepository.Piece;
import vn.com.coreacademy.assistant.drafting.CourseDraftingProposer;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.Candidate;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.Choice;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.CourseBrief;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.DraftingStepRow;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository.SessionRow;
import vn.com.coreacademy.assistant.drafting.DraftingStepProposer.Proposal;
import vn.com.coreacademy.assistant.skill.SkillCatalog;
import vn.com.coreacademy.knowledge.ai.OllamaClient;
import vn.com.coreacademy.knowledge.config.KnowledgeProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Model eval of the step 1 and step 2 prompts of Soạn cùng trợ lý ({@code skills/tao_khoa_hoc/steps/draft_basics.md}
 * and {@code draft_outline.md}), with the real prompts and the real local model. Step 1: a short banking source and the
 * unit's lists go in, and the proposal must fit the schema, take its ids from the lists only (the matching programme
 * and module, never an invented one) and be in Vietnamese. Step 2: the outline must hold titles and one line per
 * lesson, name a section of the summary for every lesson and carry no lesson content. Step 3 ({@code draft_rules.md}):
 * bonus points in range, a competency from the list or none, and multiple choice questions that each name a section
 * of the summary and have the right number of correct options. It needs Ollama with {@code qwen3:8b} on localhost:11434, so its name keeps it out of the default run
 * (surefire only includes {@code *Test} and {@code *IT}), and it is skipped when the model is not reachable. Run it
 * alone with {@code mvn test -Dtest=CourseDraftingEval}, or every eval with {@code mvn test -Dtest="*Eval"}.
 */
class CourseDraftingEval {
    private static final String SOURCE = """
        QUY TRÌNH MỞ TÀI KHOẢN THANH TOÁN CHO KHÁCH HÀNG CÁ NHÂN
        1. Điều kiện: khách hàng là công dân Việt Nam từ đủ 15 tuổi, có giấy tờ tùy thân còn hiệu lực.
        2. Hồ sơ: giấy đề nghị mở tài khoản kiêm hợp đồng, căn cước công dân bản gốc để đối chiếu.
        3. Các bước tại quầy: giao dịch viên tiếp nhận hồ sơ, kiểm tra giấy tờ, xác thực sinh trắc học, nhập thông tin
        khách hàng vào hệ thống core banking, in hợp đồng để khách hàng ký, kiểm soát viên phê duyệt.
        4. Sau khi mở: kích hoạt dịch vụ ngân hàng điện tử, hướng dẫn khách hàng đổi mật khẩu lần đầu.
        5. Lưu ý phòng chống rửa tiền: nhận biết khách hàng, đối chiếu danh sách cấm vận, báo cáo giao dịch đáng ngờ.
        """;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void theStepOneProposalFitsTheSchemaAndPicksOnlyListedIds() {
        OllamaClient ollama = ollama();
        assumeTrue(ollama.isAvailable(), "Ollama with qwen3:8b on localhost:11434 is not reachable");
        SourceTextRepository sources = mock(SourceTextRepository.class);
        when(sources.status(1L)).thenReturn(Optional.of(new SourceTextRepository.Status("ready", null)));
        when(sources.pieces(1L)).thenReturn(List.of(new Piece(0, SOURCE, 1, 1, 9)));
        CourseDraftingRepository drafting = mock(CourseDraftingRepository.class);
        when(drafting.programmes(anyLong(), anyInt())).thenReturn(List.of(
            new Candidate(12, "Nghiệp vụ ngân hàng bán lẻ", 3), new Candidate(13, "Quản trị rủi ro tín dụng", 4)));
        when(drafting.modules(anyLong(), anyInt())).thenReturn(List.of(
            new Candidate(34, "Tài khoản thanh toán", 3), new Candidate(35, "Thẻ tín dụng", 3),
            new Candidate(36, "Thẩm định khoản vay", 4)));
        when(drafting.codeTaken(anyString())).thenReturn(false);
        CourseDraftingProposer proposer = new CourseDraftingProposer(new SourceSummariser(sources, ollama,
            new SkillCatalog(), AssistantModels.defaults(), json, AssistantBudget.defaults(),
            new GenerationProperties(3, Duration.ofSeconds(3), Duration.ofDays(30), Duration.ofSeconds(180))),
            drafting, json);

        Proposal result = proposer.propose(new SessionRow(1, "000005", 1, 1L, null, null, null, 1, "active", null,
            "{\"targetLearners\":\"Giao dịch viên mới\"}", null, null, null),
            new DraftingStepRow(1, 1, 1, "proposing", null, "[]", null, 1, null, false, null));

        JsonNode proposal = result.proposal();
        System.out.println("CourseDraftingEval step 1 proposal: " + proposal.toPrettyString());
        System.out.println("CourseDraftingEval notes: " + result.notes());
        assertThat(GenerationSchemas.LEVELS).contains(proposal.path("level").asText());
        assertThat(GenerationSchemas.REQUIREMENTS).contains(proposal.path("defaultRequirement").asText());
        assertThat(proposal.path("title").asText()).isNotBlank();
        assertThat(proposal.path("objectives").size()).isBetween(1, 10);
        assertThat(proposal.path("programId").isNull() || proposal.path("programId").asLong() == 12)
            .as("programme is the retail one or left empty").isTrue();
        assertThat(proposal.path("moduleId").isNull() || proposal.path("moduleId").asLong() == 34)
            .as("module is the payment account one or left empty").isTrue();
        assertThat(proposal.path("title").asText() + proposal.path("summary").asText()).containsPattern(
            "[ăâđêôơưàáạảãèéẹẻẽìíịỉĩòóọỏõùúụủũỳýỵỷỹ]");
    }

    @Test
    void theStepTwoOutlineHasNoContentAndNamesKnownSourceSections() {
        OllamaClient ollama = ollama();
        assumeTrue(ollama.isAvailable(), "Ollama with qwen3:8b on localhost:11434 is not reachable");
        CourseDraftingRepository drafting = mock(CourseDraftingRepository.class);
        when(drafting.courseBrief(2L)).thenReturn(Optional.of(new CourseBrief("Mở tài khoản thanh toán cá nhân",
            "Quy trình mở tài khoản tại quầy cho giao dịch viên.", "basic",
            List.of("Thực hiện được quy trình mở tài khoản tại quầy"))));
        CourseDraftingProposer proposer = new CourseDraftingProposer(new SourceSummariser(
            mock(SourceTextRepository.class), ollama, new SkillCatalog(), AssistantModels.defaults(), json,
            AssistantBudget.defaults(),
            new GenerationProperties(3, Duration.ofSeconds(3), Duration.ofDays(30), Duration.ofSeconds(180))),
            drafting, json);
        String summary = """
            {"sections":[
              {"key":"s1","title":"Điều kiện và hồ sơ","summary":"Công dân Việt Nam từ đủ 15 tuổi, giấy tờ tùy thân còn hiệu lực; giấy đề nghị kiêm hợp đồng và căn cước bản gốc."},
              {"key":"s2","title":"Các bước tại quầy","summary":"Tiếp nhận, kiểm tra giấy tờ, xác thực sinh trắc học, nhập thông tin vào core banking, in hợp đồng, kiểm soát viên phê duyệt."},
              {"key":"s3","title":"Sau khi mở và phòng chống rửa tiền","summary":"Kích hoạt ngân hàng điện tử, đổi mật khẩu lần đầu; nhận biết khách hàng, đối chiếu danh sách cấm vận, báo cáo giao dịch đáng ngờ."}]}
            """;

        Proposal result = proposer.propose(new SessionRow(1, "000005", 1, 1L, null, 1L, 2L, 2, "active", null,
            "{\"targetLearners\":\"Giao dịch viên mới\"}", summary, null, null),
            new DraftingStepRow(2, 1, 2, "proposing", null, "[]", null, 1, null, false, null));

        JsonNode proposal = result.proposal();
        System.out.println("CourseDraftingEval step 2 proposal: " + proposal.toPrettyString());
        assertThat(proposal.path("chapters").size()).isBetween(1, 20);
        List<String> titles = List.of("Điều kiện và hồ sơ", "Các bước tại quầy", "Sau khi mở và phòng chống rửa tiền");
        proposal.path("chapters").forEach(chapter -> chapter.path("lessons").forEach(lesson -> {
            assertThat(lesson.path("title").asText()).isNotBlank();
            assertThat(lesson.path("description").asText()).isNotBlank();
            assertThat(titles).contains(lesson.path("sourceSection").asText());
            assertThat(lesson.has("blocks")).isFalse();
        }));
        assertThat(proposal.toString()).containsPattern("[ăâđêôơưàáạảãèéẹẻẽìíịỉĩòóọỏõùúụủũỳýỵỷỹ]");
    }

    @Test
    void theStepThreeQuestionsNameKnownSectionsAndTheCompetencyComesFromTheList() {
        OllamaClient ollama = ollama();
        assumeTrue(ollama.isAvailable(), "Ollama with qwen3:8b on localhost:11434 is not reachable");
        CourseDraftingRepository drafting = mock(CourseDraftingRepository.class);
        when(drafting.courseBrief(2L)).thenReturn(Optional.of(new CourseBrief("Mở tài khoản thanh toán cá nhân",
            "Quy trình mở tài khoản tại quầy cho giao dịch viên.", "basic",
            List.of("Thực hiện được quy trình mở tài khoản tại quầy"))));
        when(drafting.competencies(anyInt())).thenReturn(List.of(new Choice(4, "Giao dịch tại quầy"),
            new Choice(5, "Thẩm định tín dụng"), new Choice(6, "Phòng chống rửa tiền")));
        CourseDraftingProposer proposer = new CourseDraftingProposer(new SourceSummariser(
            mock(SourceTextRepository.class), ollama, new SkillCatalog(), AssistantModels.defaults(), json,
            AssistantBudget.defaults(),
            new GenerationProperties(3, Duration.ofSeconds(3), Duration.ofDays(30), Duration.ofSeconds(180))),
            drafting, json);
        String summary = """
            {"sections":[
              {"key":"s1","title":"Điều kiện và hồ sơ","summary":"Công dân Việt Nam từ đủ 15 tuổi, giấy tờ tùy thân còn hiệu lực; giấy đề nghị kiêm hợp đồng và căn cước bản gốc."},
              {"key":"s2","title":"Các bước tại quầy","summary":"Tiếp nhận, kiểm tra giấy tờ, xác thực sinh trắc học, nhập thông tin vào core banking, in hợp đồng, kiểm soát viên phê duyệt."},
              {"key":"s3","title":"Sau khi mở và phòng chống rửa tiền","summary":"Kích hoạt ngân hàng điện tử, đổi mật khẩu lần đầu; nhận biết khách hàng, đối chiếu danh sách cấm vận, báo cáo giao dịch đáng ngờ."}]}
            """;

        Proposal result = proposer.propose(new SessionRow(1, "000005", 1, 1L, null, 1L, 2L, 3, "active", null,
            "{}", summary, null, null),
            new DraftingStepRow(3, 1, 3, "proposing", null, "[]", null, 1, null, false, null));

        JsonNode proposal = result.proposal();
        System.out.println("CourseDraftingEval step 3 proposal: " + proposal.toPrettyString());
        System.out.println("CourseDraftingEval notes: " + result.notes());
        assertThat(proposal.path("rewardPoints").asInt()).isBetween(0, GenerationSchemas.MAX_DRAFT_REWARD);
        assertThat(proposal.path("competencyId").isNull() || List.of(4L, 5L, 6L)
            .contains(proposal.path("competencyId").asLong())).as("competency from the list or empty").isTrue();
        assertThat(proposal.path("questions").size()).isBetween(5, GenerationSchemas.MAX_DRAFT_QUESTIONS);
        List<String> titles = List.of("Điều kiện và hồ sơ", "Các bước tại quầy", "Sau khi mở và phòng chống rửa tiền");
        proposal.path("questions").forEach(question -> {
            assertThat(titles).contains(question.path("sourceSection").asText());
            long correct = 0;
            for (JsonNode option : question.path("options")) {
                correct += option.path("correct").asBoolean() ? 1 : 0;
            }
            assertThat(correct).isGreaterThanOrEqualTo(1);
        });
        assertThat(proposal.toString()).containsPattern("[ăâđêôơưàáạảãèéẹẻẽìíịỉĩòóọỏõùúụủũỳýỵỷỹ]");
    }

    private static OllamaClient ollama() {
        KnowledgeProperties.Ollama ollama = new KnowledgeProperties.Ollama("http://localhost:11434", "qwen3:8b",
            "bge-m3", Duration.ofSeconds(5), Duration.ofSeconds(180), Duration.ofMinutes(10));
        return new OllamaClient(new KnowledgeProperties(Path.of("unused"), DataSize.ofMegabytes(20),
            new KnowledgeProperties.Worker(Duration.ofSeconds(2), 1, 3),
            new KnowledgeProperties.Chunk(600, 800, 80), new KnowledgeProperties.Retrieval(5, 0.35), ollama));
    }
}
