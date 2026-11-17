package vn.com.coreacademy.assistant.course;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** What the model may write into a lesson or a question before it is stored. */
class GenerationSchemasTest {
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode lesson(String body) throws Exception {
        return json.readTree("""
            {"blocks": [{"type": "text", "title": "Khối", "body": %s}], "sourceNotes": []}
            """.formatted(json.writeValueAsString(body)));
    }

    @Test
    void aPlainLessonIsValid() throws Exception {
        assertThat(GenerationSchemas.lesson(lesson("Nội dung bài học bằng tiếng Việt."))).isEmpty();
    }

    @Test
    void linksImagesAndHtmlInALessonAreRejected() throws Exception {
        for (String body : new String[] {"Xem https://evil.example/login", "[nhấn vào đây](http://x.y)",
            "![ảnh](a.png)", "Vào www.evil.example", "<script>alert(1)</script>", "<a href=\"x\">x</a>"}) {
            assertThat(GenerationSchemas.lesson(lesson(body))).as(body).isNotEmpty();
        }
    }

    @Test
    void aLessonHasOneToSixBlocksOfKnownTypesWithinTheLength() throws Exception {
        assertThat(GenerationSchemas.lesson(json.readTree("{\"blocks\": [], \"sourceNotes\": []}"))).isNotEmpty();
        assertThat(GenerationSchemas.lesson(lesson("x".repeat(GenerationSchemas.MAX_BODY + 1)))).isNotEmpty();
        assertThat(GenerationSchemas.lesson(json.readTree(
            "{\"blocks\": [{\"type\": \"video\", \"title\": \"t\"}], \"sourceNotes\": []}"))).isNotEmpty();
        assertThat(GenerationSchemas.lesson(json.readTree(
            "{\"blocks\": [{\"type\": \"text\", \"title\": \" \", \"body\": \"b\"}], \"sourceNotes\": []}")))
            .isNotEmpty();
        String seven = "{\"type\": \"text\", \"title\": \"t\", \"body\": \"b\"},".repeat(7);
        assertThat(GenerationSchemas.lesson(json.readTree("{\"blocks\": [" + seven.substring(0, seven.length() - 1)
            + "], \"sourceNotes\": []}"))).isNotEmpty();
    }

    @Test
    void aProcessBlockNeedsStepsWithTitles() throws Exception {
        assertThat(GenerationSchemas.lesson(json.readTree("""
            {"blocks": [{"type": "process", "title": "Quy trình",
              "steps": [{"title": "Bước 1", "description": "Làm A"}]}], "sourceNotes": []}
            """))).isEmpty();
        assertThat(GenerationSchemas.lesson(json.readTree("""
            {"blocks": [{"type": "process", "title": "Quy trình", "steps": []}], "sourceNotes": []}
            """))).isNotEmpty();
    }

    @Test
    void questionsNeedTheRightNumberOfCorrectOptionsAndNoLinks() throws Exception {
        String single = """
            {"questions": [{"type": "SINGLE", "content": "Câu hỏi?", "difficulty": "EASY", "options": [
              {"content": "A", "correct": true}, {"content": "B", "correct": false}]}]}
            """;
        assertThat(GenerationSchemas.questions(json.readTree(single))).isEmpty();
        assertThat(GenerationSchemas.questions(json.readTree(single.replace("\"correct\": false", "\"correct\": true"))))
            .isNotEmpty();
        assertThat(GenerationSchemas.questions(json.readTree(single.replace("Câu hỏi?", "Xem http://x.y"))))
            .isNotEmpty();
    }

    // ---- Soạn cùng trợ lý, step 1 --------------------------------------------------------------------------------

    private static final String BASICS = """
        {"title": "Mở tài khoản", "summary": "Quy trình mở tài khoản cho khách hàng cá nhân.",
         "objectives": ["Thực hiện được việc mở tài khoản"], "level": "basic", "defaultRequirement": "mandatory",
         "programId": 3, "unsure": ["moduleId"]}
        """;

    private JsonNode basicsWith(String field, Object value) throws Exception {
        var node = (ObjectNode) json.readTree(BASICS);
        node.set(field, json.valueToTree(value));
        return node;
    }

    @Test
    void aStepOneProposalWithKnownValuesIsValidAndIdsMayBeLeftOut() throws Exception {
        assertThat(GenerationSchemas.draftBasics(json.readTree(BASICS))).isEmpty();
        assertThat(GenerationSchemas.draftBasics(basicsWith("programId", null))).isEmpty();
    }

    @Test
    void aStepOneProposalOutsideTheCourseValuesIsRejected() throws Exception {
        assertThat(GenerationSchemas.draftBasics(basicsWith("level", "BEGINNER"))).isNotEmpty();
        assertThat(GenerationSchemas.draftBasics(basicsWith("defaultRequirement", "elective"))).isNotEmpty();
        assertThat(GenerationSchemas.draftBasics(basicsWith("objectives", java.util.List.of()))).isNotEmpty();
        assertThat(GenerationSchemas.draftBasics(basicsWith("programId", "ba"))).isNotEmpty();
        assertThat(GenerationSchemas.draftBasics(basicsWith("unsure", java.util.List.of("code")))).isNotEmpty();
        assertThat(GenerationSchemas.draftBasics(basicsWith("title", " "))).isNotEmpty();
        assertThat(GenerationSchemas.draftBasics(basicsWith("summary", "Xem https://evil.example"))).isNotEmpty();
    }

    @Test
    void theStepOneSchemaLeavesTheCodeToTheServer() {
        JsonNode format = GenerationSchemas.format(GenerationSchemas.DRAFT_BASICS);

        assertThat(format.path("properties").has("code")).isFalse();
        assertThat(format.path("required")).extracting(JsonNode::asText)
            .contains("title", "summary", "objectives", "level", "defaultRequirement", "unsure")
            .doesNotContain("programId", "moduleId");
    }

    // ---- Soạn cùng trợ lý, step 2 --------------------------------------------------------------------------------

    private static final String OUTLINE = """
        {"chapters": [{"title": "Chuẩn bị", "lessons": [
            {"title": "Điều kiện mở tài khoản", "description": "Ai được mở tài khoản và cần giấy tờ gì.", "section": "s1"},
            {"title": "Hồ sơ", "description": "Các giấy tờ khách hàng nộp.", "section": "s2"}]}]}
        """;

    private JsonNode outlineLessonWith(String field, Object value) throws Exception {
        var node = (ObjectNode) json.readTree(OUTLINE);
        ((ObjectNode) node.path("chapters").get(0).path("lessons").get(0))
            .set(field, json.valueToTree(value));
        return node;
    }

    @Test
    void aStepTwoOutlineOfTitlesDescriptionsAndSectionsIsValid() throws Exception {
        assertThat(GenerationSchemas.draftOutline(json.readTree(OUTLINE))).isEmpty();
    }

    @Test
    void aStepTwoOutlineWithoutADescriptionOrSectionOrWithLinksIsRejected() throws Exception {
        assertThat(GenerationSchemas.draftOutline(outlineLessonWith("description", " "))).isNotEmpty();
        assertThat(GenerationSchemas.draftOutline(outlineLessonWith("description",
            "x".repeat(GenerationSchemas.MAX_LESSON_DESCRIPTION + 1)))).isNotEmpty();
        assertThat(GenerationSchemas.draftOutline(outlineLessonWith("section", ""))).isNotEmpty();
        assertThat(GenerationSchemas.draftOutline(outlineLessonWith("title", "Xem https://evil.example")))
            .isNotEmpty();
        assertThat(GenerationSchemas.draftOutline(json.readTree("{\"chapters\": []}"))).isNotEmpty();
        assertThat(GenerationSchemas.draftOutline(json.readTree(
            "{\"chapters\": [{\"title\": \"C\", \"lessons\": []}]}"))).isNotEmpty();
    }

    @Test
    void theStepTwoSchemaHasNoLessonContent() {
        JsonNode lesson = GenerationSchemas.format(GenerationSchemas.DRAFT_OUTLINE).path("properties")
            .path("chapters").path("items").path("properties").path("lessons").path("items");

        assertThat(lesson.path("required")).extracting(JsonNode::asText)
            .containsExactlyInAnyOrder("title", "description", "section");
        assertThat(lesson.path("properties").has("blocks")).isFalse();
    }

    // ---- step 3, the rules and draft questions (ticket 06) --------------------------------------------------------

    private static final String RULES = """
        {"rewardPoints": 50, "competencyId": 4, "questions": [
          {"type": "SINGLE", "content": "Ai được mở tài khoản?", "explanation": "Theo điều kiện.",
           "difficulty": "EASY", "section": "s1",
           "options": [{"content": "Cá nhân đủ 15 tuổi", "correct": true}, {"content": "Ai cũng được", "correct": false}]},
          {"type": "MULTIPLE", "content": "Hồ sơ gồm?", "difficulty": "MEDIUM", "section": "s2",
           "options": [{"content": "Giấy đề nghị", "correct": true}, {"content": "Căn cước", "correct": true},
                       {"content": "Sổ hộ khẩu", "correct": false}]}]}
        """;

    private JsonNode rulesWith(String field, Object value) throws Exception {
        ObjectNode node = (ObjectNode) json.readTree(RULES);
        ObjectNode question = (ObjectNode) node.path("questions").get(0);
        question.set(field, json.valueToTree(value));
        return node;
    }

    @Test
    void aStepThreeProposalWithPointsQuestionsAndSectionsIsValid() throws Exception {
        assertThat(GenerationSchemas.draftRules(json.readTree(RULES))).isEmpty();
        // the competency is optional: the model leaves it out when it is unsure
        assertThat(GenerationSchemas.draftRules(json.readTree(RULES.replace("\"competencyId\": 4,", ""))))
            .isEmpty();
    }

    @Test
    void aStepThreeProposalWithBadPointsQuestionsOrSectionsIsRejected() throws Exception {
        assertThat(GenerationSchemas.draftRules(json.readTree(RULES.replace("50", "-1")))).isNotEmpty();
        assertThat(GenerationSchemas.draftRules(json.readTree(RULES.replace("\"competencyId\": 4",
            "\"competencyId\": \"bốn\"")))).isNotEmpty();
        assertThat(GenerationSchemas.draftRules(rulesWith("section", ""))).isNotEmpty();
        assertThat(GenerationSchemas.draftRules(rulesWith("content", "Xem https://evil.example"))).isNotEmpty();
        assertThat(GenerationSchemas.draftRules(rulesWith("difficulty", "VERY_HARD"))).isNotEmpty();
        // a SINGLE question with two correct options
        assertThat(GenerationSchemas.draftRules(json.readTree(RULES.replace("\"Ai cũng được\", \"correct\": false",
            "\"Ai cũng được\", \"correct\": true")))).isNotEmpty();
        assertThat(GenerationSchemas.draftRules(json.readTree("{\"rewardPoints\": 0, \"questions\": []}")))
            .isNotEmpty();
    }

    @Test
    void theStepThreeSchemaAsksForTheSourceSectionOfEveryQuestion() {
        JsonNode schema = GenerationSchemas.format(GenerationSchemas.DRAFT_RULES);
        JsonNode question = schema.path("properties").path("questions").path("items");

        assertThat(schema.path("required")).extracting(JsonNode::asText)
            .containsExactlyInAnyOrder("rewardPoints", "questions");
        assertThat(question.path("required")).extracting(JsonNode::asText).contains("section", "options");
        assertThat(schema.path("properties").path("questions").path("maxItems").asInt())
            .isEqualTo(GenerationSchemas.MAX_DRAFT_QUESTIONS);
    }
}
