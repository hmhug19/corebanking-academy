package vn.com.coreacademy.assessment;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import vn.com.coreacademy.assessment.importing.QuestionWorkbook;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.ADMIN;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.questionBody;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.unique;

/** Nhập/xuất ngân hàng câu hỏi bằng Excel như FE: tải mẫu, kiểm tra trước, nhập thật, ghi đè, xuất rồi nhập lại. */
class QuestionTransferApiIT extends AssessmentIntegrationTest {
    private static final String URL = "/api/v1/questions";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private AssessmentFixtures fixtures;
    private String competencyCode;
    private long competencyId;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
        competencyCode = unique("IMP");
        Map<String, Object> body = new HashMap<>();
        body.put("code", competencyCode);
        body.put("name", "Năng lực nhập Excel");
        body.put("description", null);
        body.put("parentId", null);
        body.put("sortNo", 0);
        body.put("status", "ACTIVE");
        competencyId = api.ok(ADMIN, api.post(body, "/api/v1/competencies")).path("id").asLong();
    }

    @Test
    void templateIsAnEmptyWorkbookWithTheContractHeaders() throws IOException {
        MockHttpServletResponse response = api.raw(INSTRUCTOR, get(URL + "/import-template"));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo(XLSX);
        assertThat(response.getHeader("Content-Disposition")).contains("question-import-template.xlsx");
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(response.getContentAsByteArray()))) {
            assertThat(workbook.getSheetName(0)).isEqualTo("Instructions");
            Sheet sheet = workbook.getSheet("Questions");
            List<String> headers = new ArrayList<>();
            sheet.getRow(0).forEach(cell -> headers.add(cell.getStringCellValue()));
            assertThat(headers).containsExactly("ma", "noi_dung", "loai", "phuong_an", "dap_an_dung", "giai_thich",
                "do_kho", "nhom_kien_thuc", "diem", "trang_thai");
            assertThat(sheet.getLastRowNum()).isLessThanOrEqualTo(1000);
            for (Row row : sheet) {
                if (row.getRowNum() > 0) {
                    row.forEach(cell -> assertThat(cell.toString()).isBlank());
                }
            }
        }
        api.http(LEARNER, get(URL + "/import-template"), 403, "CORE-ACCESS_DENIED");
    }

    @Test
    void dryRunValidatesWithoutWritingThenCommitCreates() throws IOException {
        Course course = fixtures.course();
        byte[] file = xlsx(
            row("", "Câu một đáp án", "mot", "Sai|Đúng|Sai nữa", "B", "Vì B đúng", "Dễ", competencyCode, "2", ""),
            row(unique("NV"), "Câu nhiều đáp án", "MULTIPLE", "A1|A2|A3|A4", "a|C", "", "", competencyCode, "", ""));

        JsonNode dry = api.ok(INSTRUCTOR, upload(course.id(), file, true, false));
        assertThat(dry.fieldNames()).toIterable().containsExactlyInAnyOrder("dryRun", "rowCount", "toCreate",
            "toUpdate", "created", "updated");
        assertThat(dry.path("dryRun").asBoolean()).isTrue();
        assertThat(dry.path("rowCount").asInt()).isEqualTo(2);
        assertThat(dry.path("toCreate").asInt()).isEqualTo(2);
        assertThat(dry.path("created").asInt()).isZero();
        assertThat(count(course.id())).as("dry-run không ghi").isZero();

        JsonNode done = api.ok(INSTRUCTOR, upload(course.id(), file, false, false));
        assertThat(done.path("dryRun").asBoolean()).isFalse();
        assertThat(done.path("created").asInt()).isEqualTo(2);
        assertThat(count(course.id())).isEqualTo(2);

        JsonNode list = api.ok(INSTRUCTOR, get(URL + "?courseId={c}&sort=id,asc", course.id())).path("items");
        JsonNode single = api.ok(INSTRUCTOR, get(URL + "/{id}", list.get(0).path("id").asLong()));
        assertThat(single.path("code").asText()).startsWith("Q");
        assertThat(single.path("questionType").asText()).isEqualTo("SINGLE");
        assertThat(single.path("difficulty").asText()).isEqualTo("EASY");
        assertThat(single.path("points").decimalValue()).isEqualByComparingTo("2");
        assertThat(single.path("competencyId").asLong()).isEqualTo(competencyId);
        assertThat(single.path("options").get(1).path("correct").asBoolean()).isTrue();
        JsonNode multiple = api.ok(INSTRUCTOR, get(URL + "/{id}", list.get(1).path("id").asLong()));
        assertThat(multiple.path("difficulty").asText()).isEqualTo("MEDIUM");
        assertThat(multiple.path("points").decimalValue()).isEqualByComparingTo("1");
        assertThat(multiple.path("explanation").isNull()).isTrue();
        assertThat(multiple.path("options").findValues("correct").stream().filter(JsonNode::asBoolean)).hasSize(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM learning_hub.audit_log
            WHERE action = 'ASSESSMENT_QUESTIONS_IMPORTED' AND entity_id = ?
            """, Integer.class, String.valueOf(course.id()))).isEqualTo(1);
    }

    @Test
    void rowErrorsPointToExcelCellsAndNothingIsWritten() throws IOException {
        Course course = fixtures.course();
        String dup = unique("DUP");
        byte[] file = xlsx(
            row("", "Câu", "TRUE_FALSE", "A|B", "A", "", "", competencyCode, "", ""),
            row("", "", "SINGLE", "A|B", "A", "", "", competencyCode, "", ""),
            row("", "Câu", "SINGLE", "A|B", "A", "", "", "KHONG-CO", "", ""),
            row("", "Câu", "SINGLE", "A|B", "C", "", "", competencyCode, "", ""),
            row("", "Câu", "SINGLE", "A|B|C", "A|B", "", "", competencyCode, "", ""),
            row(dup, "Câu", "SINGLE", "A|B", "A", "", "", competencyCode, "", ""),
            row(dup, "Câu", "SINGLE", "A|B", "A", "", "", competencyCode, "", ""),
            row("", "Câu", "SINGLE", "A|B", "A", "", "", competencyCode, "-1", ""),
            row("", "Câu", "SINGLE", "Chỉ một", "A", "", "", competencyCode, "", ""),
            row("", "Câu hợp lệ", "SINGLE", "A|B", "A", "", "", competencyCode, "", ""));

        JsonNode error = api.error(INSTRUCTOR, upload(course.id(), file, false, false), "ASSESSMENT-IMPORT_INVALID");
        Map<String, String> fields = new HashMap<>();
        error.path("errors").forEach(e -> fields.put(e.path("field").asText(), e.path("code").asText()));
        assertThat(fields).containsEntry("Questions[2].loai", "INVALID_VALUE")
            .containsEntry("Questions[3].noi_dung", "REQUIRED")
            .containsEntry("Questions[4].nhom_kien_thuc", "INVALID_REFERENCE")
            .containsEntry("Questions[5].dap_an_dung", "INVALID_VALUE")
            .containsEntry("Questions[6].dap_an_dung", "INVALID_VALUE")
            .containsEntry("Questions[7].ma", "DUPLICATE_IN_FILE")
            .containsEntry("Questions[8].ma", "DUPLICATE_IN_FILE")
            .containsEntry("Questions[9].diem", "OUT_OF_RANGE")
            .containsEntry("Questions[10].phuong_an", "INVALID_VALUE");
        assertThat(fields.keySet()).noneMatch(field -> field.startsWith("Questions[11]"));
        assertThat(error.path("message").asText()).isNotBlank();
        assertThat(count(course.id())).as("có lỗi thì không ghi dòng hợp lệ nào").isZero();
    }

    @Test
    void existingCodeNeedsOverwriteAndCannotCrossCourses() throws IOException {
        Course course = fixtures.course();
        String code = unique("OVR");
        api.ok(INSTRUCTOR, upload(course.id(), xlsx(row(code, "Bản đầu", "SINGLE", "A|B", "A", "", "",
            competencyCode, "", "")), false, false));

        byte[] changed = xlsx(row(code, "Bản sửa", "SINGLE", "A|B|C", "C", "Giải thích mới", "HARD",
            competencyCode, "3", "INACTIVE"));
        JsonNode exists = api.error(INSTRUCTOR, upload(course.id(), changed, true, false),
            "ASSESSMENT-IMPORT_INVALID");
        assertThat(exists.path("errors").get(0).path("field").asText()).isEqualTo("Questions[2].ma");
        assertThat(exists.path("errors").get(0).path("code").asText()).isEqualTo("ALREADY_EXISTS");

        JsonNode plan = api.ok(INSTRUCTOR, upload(course.id(), changed, true, true));
        assertThat(plan.path("toUpdate").asInt()).isEqualTo(1);
        assertThat(plan.path("toCreate").asInt()).isZero();
        JsonNode done = api.ok(INSTRUCTOR, upload(course.id(), changed, false, true));
        assertThat(done.path("updated").asInt()).isEqualTo(1);
        long id = api.ok(INSTRUCTOR, get(URL + "?courseId={c}", course.id())).path("items").get(0).path("id")
            .asLong();
        JsonNode updated = api.ok(INSTRUCTOR, get(URL + "/{id}", id));
        assertThat(updated.path("content").asText()).isEqualTo("Bản sửa");
        assertThat(updated.path("status").asText()).isEqualTo("INACTIVE");
        assertThat(updated.path("options")).hasSize(3);
        assertThat(updated.path("options").get(2).path("correct").asBoolean()).isTrue();

        Course other = fixtures.course();
        JsonNode cross = api.error(INSTRUCTOR, upload(other.id(), changed, true, true), "ASSESSMENT-IMPORT_INVALID");
        assertThat(cross.path("errors").get(0).path("code").asText()).isEqualTo("ALREADY_EXISTS");
    }

    @Test
    void exportUsesImportFormatAndRoundTrips() throws IOException {
        Course course = fixtures.course();
        Map<String, Object> multiple = questionBody(course.id(), competencyId, "MULTIPLE", 0, 2);
        multiple.put("points", 1.5);
        api.ok(INSTRUCTOR, api.post(multiple, URL));
        api.ok(INSTRUCTOR, api.post(questionBody(course.id(), competencyId, "SINGLE", 3), URL));

        MockHttpServletResponse response = api.raw(INSTRUCTOR, get(URL + "/export?courseId={c}", course.id()));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isEqualTo(XLSX);
        assertThat(response.getHeader("Content-Disposition")).contains("questions.xlsx");
        byte[] exported = response.getContentAsByteArray();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(exported))) {
            Sheet sheet = workbook.getSheet("Questions");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            Row first = sheet.getRow(1);
            assertThat(first.getCell(2).getStringCellValue()).isEqualTo("MULTIPLE");
            assertThat(first.getCell(3).getStringCellValue()).isEqualTo("Phương án A|Phương án B|Phương án C|"
                + "Phương án D");
            assertThat(first.getCell(4).getStringCellValue()).isEqualTo("A|C");
            assertThat(first.getCell(7).getStringCellValue()).isEqualTo(competencyCode);
            assertThat(first.getCell(8).getStringCellValue()).isEqualTo("1.5");
            assertThat(sheet.getRow(2).getCell(4).getStringCellValue()).isEqualTo("D");
        }

        JsonNode reimport = api.ok(INSTRUCTOR, upload(course.id(), exported, true, true));
        assertThat(reimport.path("toUpdate").asInt()).as("tệp xuất nhập lại được ngay").isEqualTo(2);

        MockHttpServletResponse filtered = api.raw(INSTRUCTOR, get(URL + "/export?courseId={c}&questionType=SINGLE",
            course.id()));
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(filtered.getContentAsByteArray()))) {
            assertThat(workbook.getSheet("Questions").getLastRowNum()).isEqualTo(1);
        }

        JsonNode invalid = api.error(INSTRUCTOR, get(URL + "/export?courseId={c}&status=X", course.id())
            .accept(XLSX, "application/json"), "CORE-VALIDATION_FAILED");
        assertThat(invalid.path("errors").get(0).path("field").asText()).isEqualTo("status");
        api.http(LEARNER, get(URL + "/export?courseId={c}", course.id()), 403, "CORE-ACCESS_DENIED");
    }

    @Test
    void fileLevelProblemsAreReportedOnFileField() throws IOException {
        Course course = fixtures.course();
        JsonNode text = api.error(INSTRUCTOR, multipart(course.id(), "cau-hoi.csv", "a,b".getBytes(), true),
            "ASSESSMENT-IMPORT_INVALID");
        assertThat(text.path("errors").get(0).path("field").asText()).isEqualTo("file");

        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("Questions").createRow(0).createCell(0).setCellValue("sai_cot");
            workbook.write(output);
            JsonNode header = api.error(INSTRUCTOR, multipart(course.id(), "q.xlsx", output.toByteArray(), true),
                "ASSESSMENT-IMPORT_INVALID");
            assertThat(header.path("errors").get(0).path("field").asText()).isEqualTo("file");
        }

        api.error(INSTRUCTOR, upload(course.id(), xlsx(), true, false), "ASSESSMENT-IMPORT_INVALID");

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx(row("", "Câu", "SINGLE", "A|B", "A", "",
            "", competencyCode, "", "")))); var output = new ByteArrayOutputStream()) {
            workbook.getSheet("Questions").getRow(1).createCell(0).setCellValue(123);
            workbook.createSheet("Khac");
            workbook.write(output);
            JsonNode extra = api.error(INSTRUCTOR, multipart(course.id(), "q.xlsx", output.toByteArray(), true),
                "ASSESSMENT-IMPORT_INVALID");
            assertThat(extra.path("errors").get(0).path("field").asText()).isEqualTo("file");
        }

        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(xlsx(row("", "Câu", "SINGLE", "A|B", "A", "",
            "", competencyCode, "", "")))); var output = new ByteArrayOutputStream()) {
            workbook.getSheet("Questions").getRow(1).createCell(0).setCellValue(123);
            workbook.write(output);
            JsonNode numeric = api.error(INSTRUCTOR, multipart(course.id(), "q.xlsx", output.toByteArray(), true),
                "ASSESSMENT-IMPORT_INVALID");
            assertThat(numeric.path("errors").get(0).path("field").asText()).isEqualTo("Questions[2].ma");
            assertThat(numeric.path("errors").get(0).path("code").asText()).isEqualTo("INVALID_FORMAT");
        }

        api.http(LEARNER, upload(course.id(), xlsx(row("", "Câu", "SINGLE", "A|B", "A", "", "", competencyCode, "",
            "")), true, false), 403, "CORE-ACCESS_DENIED");
    }

    @Test
    void csvExportErrorsStayJsonForFrontend() {
        JsonNode error = api.error(LEARNER, get("/api/v1/me/quiz-attempts/export?status=X")
            .accept("text/csv", "application/json"), "CORE-VALIDATION_FAILED");
        assertThat(error.path("errors").get(0).path("field").asText()).isEqualTo("status");
    }

    private MockHttpServletRequestBuilder upload(long courseId, byte[] content, boolean dryRun, boolean overwrite) {
        return MockMvcRequestBuilders.multipart(URL + "/import?courseId={c}&dryRun={d}&overwrite={o}", courseId,
            dryRun, overwrite).file(new MockMultipartFile("file", "cau-hoi.xlsx", XLSX, content));
    }

    @Test
    void importsTheActualTemplateIntoAProgramWithoutAnyCourse() throws IOException {
        Course course = fixtures.course();
        long programId = jdbc.queryForObject(
            "SELECT program_id FROM learning_hub.course_revisions WHERE revision_id = ?", Long.class,
            course.revisionId());
        String code = unique("PROGRAM_EXCEL");
        byte[] bytes = api.raw(ADMIN, get(URL + "/import-template")).getContentAsByteArray();
        try (var workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes));
             var output = new ByteArrayOutputStream()) {
            Row row = workbook.getSheet("Questions").createRow(1);
            String[] values = row(code, "Câu hỏi dùng chung chương trình", "SINGLE", "Đúng|Sai", "A", "Giải thích",
                "EASY", competencyCode, "1", "ACTIVE");
            for (int i = 0; i < values.length; i++) {
                row.createCell(i).setCellValue(values[i]);
            }
            workbook.write(output);
            bytes = output.toByteArray();
        }
        JsonNode checked = api.ok(ADMIN, programUpload(programId, bytes, true, false));
        assertThat(checked.path("toCreate").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.questions WHERE code = ?",
            Integer.class, code)).isZero();
        assertThat(api.ok(INSTRUCTOR, programUpload(programId, bytes, true, false))
            .path("toCreate").asInt()).isEqualTo(1);
        api.http(LEARNER, programUpload(programId, bytes, true, false), 403, "CORE-ACCESS_DENIED");
        JsonNode imported = api.ok(ADMIN, programUpload(programId, bytes, false, false));
        assertThat(imported.path("created").asInt()).isEqualTo(1);
        JsonNode question = api.ok(ADMIN, get(URL + "?programId={p}&q={q}", programId, code))
            .path("items").get(0);
        assertThat(question.path("programId").asLong()).isEqualTo(programId);
        assertThat(question.path("courseId").isNull()).isTrue();
        api.error(ADMIN, programUpload(programId, bytes, true, false), "ASSESSMENT-IMPORT_INVALID");
        JsonNode overwritten = api.ok(ADMIN, programUpload(programId, bytes, false, true));
        assertThat(overwritten.path("updated").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT question_id FROM learning_hub.questions WHERE code = ?",
            Long.class, code))
            .isEqualTo(question.path("id").asLong());
        Course other = fixtures.course();
        long otherProgramId = jdbc.queryForObject(
            "SELECT program_id FROM learning_hub.course_revisions WHERE revision_id = ?", Long.class,
            other.revisionId());
        api.error(ADMIN, programUpload(otherProgramId, bytes, false, true), "ASSESSMENT-IMPORT_INVALID");
    }

    @Test
    void programImportRejectsAllRowsWhenAnyRowIsInvalid() throws IOException {
        Course course = fixtures.course();
        long programId = jdbc.queryForObject(
            "SELECT program_id FROM learning_hub.course_revisions WHERE revision_id = ?", Long.class,
            course.revisionId());
        String code = unique("ATOMIC_PROGRAM");
        byte[] file = xlsx(row(code, "Câu hợp lệ", "SINGLE", "A|B", "A", "", "", competencyCode, "", ""),
            row("", "Câu sai đáp án", "MULTIPLE", "A|B", "A", "", "", competencyCode, "", ""));
        JsonNode error = api.error(ADMIN, programUpload(programId, file, false, false), "ASSESSMENT-IMPORT_INVALID");
        assertThat(error.path("errors").toString()).contains("Questions[3].dap_an_dung");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM learning_hub.questions WHERE code = ?",
            Integer.class, code)).isZero();
    }

    private MockHttpServletRequestBuilder programUpload(long programId, byte[] content, boolean dryRun,
                                                       boolean overwrite) {
        return MockMvcRequestBuilders.multipart(URL + "/import?programId={p}&dryRun={d}&overwrite={o}", programId,
            dryRun, overwrite).file(new MockMultipartFile("file", "cau-hoi.xlsx", XLSX, content));
    }

    private MockHttpServletRequestBuilder multipart(long courseId, String name, byte[] content, boolean dryRun) {
        return MockMvcRequestBuilders.multipart(URL + "/import?courseId={c}&dryRun={d}", courseId, dryRun)
            .file(new MockMultipartFile("file", name, XLSX, content));
    }

    private int count(long courseId) {
        return jdbc.queryForObject("SELECT count(*) FROM learning_hub.questions WHERE course_id = ?", Integer.class,
            courseId);
    }

    private static String[] row(String... values) {
        return values;
    }

    /** Dựng tệp theo đúng mẫu của API (Instructions + Questions), dòng dữ liệu bắt đầu từ dòng Excel 2. */
    private static byte[] xlsx(String[]... rows) throws IOException {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            workbook.createSheet("Instructions").createRow(0).createCell(0).setCellValue("Hướng dẫn");
            Sheet sheet = workbook.createSheet("Questions");
            Row header = sheet.createRow(0);
            for (int i = 0; i < QuestionWorkbook.HEADERS.size(); i++) {
                header.createCell(i).setCellValue(QuestionWorkbook.HEADERS.get(i));
            }
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    if (!rows[r][c].isEmpty()) {
                        row.createCell(c).setCellValue(rows[r][c]);
                    }
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
