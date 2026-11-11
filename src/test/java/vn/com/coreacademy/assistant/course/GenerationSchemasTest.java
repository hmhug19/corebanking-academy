package vn.com.coreacademy.assistant.course;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
}
