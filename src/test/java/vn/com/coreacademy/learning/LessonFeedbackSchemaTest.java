package vn.com.coreacademy.learning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Upgrade và rollback của lesson_feedback chạy bằng đúng file SQL vận hành trên PostgreSQL thật, bắt đầu từ baseline
 * mà bảng đã bị bỏ để mô phỏng DB cũ. Không dùng Spring: chỉ kiểm schema.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(LessonFeedbackSchemaTest.DockerRequired.class)
class LessonFeedbackSchemaTest {
    private static final String UPGRADE = "docs/sql/upgrade/2026-11-04-lesson-feedback.sql";
    private static final String ROLLBACK = "docs/sql/upgrade/2026-11-04-lesson-feedback.rollback.sql";
    private static final List<String> BASELINES = List.of("learning_hub_identity.sql", "learning_hub_learning.sql");
    private static final String TABLE = "learning_hub.lesson_feedback";

    static final class DockerRequired implements ExecutionCondition {
        @Override
        public ConditionEvaluationResult evaluateExecutionCondition(ExtensionContext context) {
            return DockerClientFactory.instance().isDockerAvailable()
                ? ConditionEvaluationResult.enabled("Docker available")
                : ConditionEvaluationResult.disabled("No Docker");
        }
    }

    private PostgreSQLContainer<?> postgres;
    private JdbcTemplate jdbc;

    @BeforeAll
    void startOldDatabase() {
        postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
        for (int i = 0; i < BASELINES.size(); i++) {
            postgres.withCopyFileToContainer(MountableFile.forHostPath("docs/sql/" + BASELINES.get(i)),
                "/docker-entrypoint-initdb.d/0" + (i + 1) + "-" + BASELINES.get(i));
        }
        postgres.start();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
            postgres.getPassword()));
        // Một DB đã chạy baseline trước module này không có bảng.
        jdbc.execute("DROP TABLE " + TABLE);
    }

    @AfterAll
    void stop() {
        postgres.stop();
    }

    @Test
    void upgradeAndRollbackRunCleanlyAndTwice() throws Exception {
        assertThat(tableExists()).isFalse();

        run(UPGRADE);
        run(UPGRADE);

        assertThat(tableExists()).isTrue();
        assertThat(commentedColumns()).as("every column has a comment").isEqualTo(columns());
        assertThat(jdbc.queryForObject("select obj_description('" + TABLE + "'::regclass)", String.class))
            .isNotBlank();
        assertThat(jdbc.queryForObject("""
            select count(*) from pg_constraint where conrelid = '%s'::regclass and contype in ('f', 'u')
            """.formatted(TABLE), Integer.class)).as("no FK and no UNIQUE").isZero();
        assertThat(columns()).isEqualTo(21);
        assertThat(indexDefinitions()).hasSize(2)
            .anyMatch(definition -> definition.contains("(lesson_id, kind, create_time DESC)")
                && definition.contains("WHERE (NOT is_deleted)"))
            .anyMatch(definition -> definition.contains("(course_id)")
                && definition.contains("WHERE (((kind)::text = 'rating'::text) AND (NOT is_deleted))"));

        // Mỗi loại vào được với dạng hợp lệ của nó.
        insertRating(4, null);
        insertText("comment", "Bài rất rõ ràng", "none", null, null, null, null);
        insertText("comment", "Trang này hay", "pdf", 7L, 3, null, null);
        insertText("question", "Trang này nghĩa là gì?", "video", 8L, null, 20, "new");
        insertText("report", "Sai thông tin", "none", null, null, null, "resolved");

        // Đánh giá: sao 1..5, không nội dung, không neo, không trạng thái.
        assertRefused(() -> insertRating(0, null), "a rating of 0 stars");
        assertRefused(() -> insertRating(6, null), "a rating of 6 stars");
        assertRefused(() -> jdbc.update("""
            insert into learning_hub.lesson_feedback (user_id, lesson_id, course_id, kind, created_by, updated_by)
            values ('U1', 1, 1, 'rating', 'U1', 'U1')
            """), "a rating without stars");
        assertRefused(() -> insertRating(4, "ghi chú"), "a rating with a body");
        assertRefused(() -> jdbc.update("""
            insert into learning_hub.lesson_feedback (user_id, lesson_id, course_id, kind, stars, anchor_type,
                created_by, updated_by)
            values ('U1', 1, 1, 'rating', 4, 'none', 'U1', 'U1')
            """), "a rating with an anchor");
        assertRefused(() -> jdbc.update("""
            insert into learning_hub.lesson_feedback (user_id, lesson_id, course_id, kind, stars, status,
                created_by, updated_by)
            values ('U1', 1, 1, 'rating', 4, 'new', 'U1', 'U1')
            """), "a rating with a status");

        // Nhận xét, câu hỏi, báo cáo: có nội dung, không sao, neo hợp lệ.
        assertRefused(() -> jdbc.update("""
            insert into learning_hub.lesson_feedback (user_id, lesson_id, course_id, kind, stars, body, anchor_type,
                created_by, updated_by)
            values ('U1', 1, 1, 'comment', 5, 'Có sao', 'none', 'U1', 'U1')
            """), "a comment with stars");
        assertRefused(() -> jdbc.update("""
            insert into learning_hub.lesson_feedback (user_id, lesson_id, course_id, kind, anchor_type, created_by,
                updated_by)
            values ('U1', 1, 1, 'comment', 'none', 'U1', 'U1')
            """), "a comment without a body");
        assertRefused(() -> insertText("comment", "Có trạng thái", "none", null, null, null, "new"),
            "a comment with a status");
        assertRefused(() -> insertText("question", "Thiếu trạng thái", "none", null, null, null, null),
            "a question without a status");
        assertRefused(() -> insertText("report", "Thiếu trạng thái", "none", null, null, null, null),
            "a report without a status");
        assertRefused(() -> insertText("question", "Trạng thái lạ", "none", null, null, null, "closed"),
            "a question with an unknown status");
        assertRefused(() -> insertText("note", "Loại lạ", "none", null, null, null, null), "an unknown kind");

        // Neo giống ghi chú.
        assertRefused(() -> insertText("comment", "x", "pdf", null, 3, null, null), "a pdf comment without a block");
        assertRefused(() -> insertText("comment", "x", "pdf", 7L, null, null, null), "a pdf comment without a page");
        assertRefused(() -> insertText("comment", "x", "pdf", 7L, 3, 20, null), "a pdf comment with a second");
        assertRefused(() -> insertText("comment", "x", "video", null, null, 20, null),
            "a video comment without a block");
        assertRefused(() -> insertText("comment", "x", "video", 8L, 3, 20, null), "a video comment with a page");
        assertRefused(() -> insertText("comment", "x", "none", 7L, null, null, null),
            "a whole-lesson comment with a block");
        assertRefused(() -> insertText("comment", "x", "image", 7L, null, null, null), "an unknown anchor type");
        assertRefused(() -> jdbc.update("""
            insert into learning_hub.lesson_feedback (user_id, lesson_id, course_id, kind, body, created_by,
                updated_by)
            values ('U1', 1, 1, 'comment', 'x', 'U1', 'U1')
            """), "a comment without an anchor type");

        List<String> notices = run(ROLLBACK);
        run(ROLLBACK);

        assertThat(notices).as("the rollback prints the row count first")
            .anyMatch(text -> text.contains("lesson_feedback (5 rows)"));
        assertThat(tableExists()).isFalse();
        assertThat(indexDefinitions()).isEmpty();
    }

    private void insertRating(int stars, String body) {
        jdbc.update("""
            insert into learning_hub.lesson_feedback (user_id, lesson_id, course_id, kind, stars, body, created_by,
                updated_by)
            values ('U1', 1, 1, 'rating', ?, ?, 'U1', 'U1')
            """, stars, body);
    }

    private void insertText(String kind, String body, String anchorType, Long blockId, Integer page, Integer second,
                            String status) {
        jdbc.update("""
            insert into learning_hub.lesson_feedback (user_id, lesson_id, course_id, kind, body, block_id,
                anchor_type, pdf_page, video_second, status, created_by, updated_by)
            values ('U1', 1, 1, ?, ?, ?, ?, ?, ?, ?, 'U1', 'U1')
            """, kind, body, blockId, anchorType, page, second, status);
    }

    private static void assertRefused(Runnable insert, String what) {
        assertThatThrownBy(insert::run).as(what).isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Chạy cả file trong một lệnh như psql -f; trả các NOTICE để kiểm số dòng mà rollback in ra. */
    private List<String> run(String file) throws SQLException, IOException {
        String sql = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        List<String> notices = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
            postgres.getPassword()); Statement statement = connection.createStatement()) {
            statement.execute(sql);
            for (SQLWarning warning = statement.getWarnings(); warning != null; warning = warning.getNextWarning()) {
                notices.add(warning.getMessage());
            }
        }
        return notices;
    }

    private boolean tableExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('" + TABLE + "') is not null",
            Boolean.class));
    }

    private int columns() {
        return jdbc.queryForObject("""
            select count(*) from information_schema.columns
            where table_schema = 'learning_hub' and table_name = 'lesson_feedback'
            """, Integer.class);
    }

    private int commentedColumns() {
        return jdbc.queryForObject("""
            select count(*) from pg_attribute a
            where a.attrelid = '%s'::regclass and a.attnum > 0 and not a.attisdropped
              and col_description(a.attrelid, a.attnum) is not null
            """.formatted(TABLE), Integer.class);
    }

    private List<String> indexDefinitions() {
        return jdbc.queryForList("""
            select indexdef from pg_indexes
            where schemaname = 'learning_hub' and indexname in ('idx_lesson_feedback_lesson_kind',
                'idx_lesson_feedback_course_rating')
            """, String.class);
    }
}
