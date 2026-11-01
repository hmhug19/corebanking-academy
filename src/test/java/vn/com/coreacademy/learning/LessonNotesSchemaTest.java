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
 * Upgrade và rollback của lesson_notes, lesson_bookmarks chạy bằng đúng file SQL vận hành trên PostgreSQL thật, bắt
 * đầu từ baseline mà hai bảng đã bị bỏ để mô phỏng DB cũ. Không dùng Spring: chỉ kiểm schema.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(LessonNotesSchemaTest.DockerRequired.class)
class LessonNotesSchemaTest {
    private static final String UPGRADE = "docs/sql/upgrade/2026-11-03-lesson-notes.sql";
    private static final String ROLLBACK = "docs/sql/upgrade/2026-11-03-lesson-notes.rollback.sql";
    private static final List<String> BASELINES = List.of("learning_hub_identity.sql", "learning_hub_learning.sql");
    private static final String NOTES = "learning_hub.lesson_notes";
    private static final String BOOKMARKS = "learning_hub.lesson_bookmarks";

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
        // Một DB đã chạy baseline trước module này không có hai bảng.
        jdbc.execute("DROP TABLE " + NOTES);
        jdbc.execute("DROP TABLE " + BOOKMARKS);
    }

    @AfterAll
    void stop() {
        postgres.stop();
    }

    @Test
    void upgradeAndRollbackRunCleanlyAndTwice() throws Exception {
        assertThat(tableExists(NOTES)).isFalse();
        assertThat(tableExists(BOOKMARKS)).isFalse();

        run(UPGRADE);
        run(UPGRADE);

        for (String table : List.of(NOTES, BOOKMARKS)) {
            assertThat(tableExists(table)).isTrue();
            assertThat(commentedColumns(table)).as(table + ": every column has a comment").isEqualTo(columns(table));
            assertThat(jdbc.queryForObject("select obj_description('" + table + "'::regclass)", String.class))
                .isNotBlank();
            assertThat(jdbc.queryForObject("""
                select count(*) from pg_constraint
                where conrelid = '%s'::regclass and contype in ('f', 'u')
                """.formatted(table), Integer.class)).as(table + ": no FK and no UNIQUE").isZero();
        }
        assertThat(columns(NOTES)).isEqualTo(14);
        assertThat(columns(BOOKMARKS)).isEqualTo(14);
        assertThat(indexDefinitions()).hasSize(2).allSatisfy(definition -> assertThat(definition)
            .contains("(user_id, lesson_id, create_time DESC)").contains("WHERE (NOT is_deleted)"));

        // Dòng cuối của mỗi nhóm kiểm: neo hợp lệ vào được, neo thiếu block hoặc lẫn trường không vào được.
        insertNote("none", null, null, null);
        insertNote("pdf", 7L, 3, null);
        insertNote("video", 8L, null, 20);
        assertRefused(() -> insertNote("pdf", null, 3, null), "a pdf note without a block");
        assertRefused(() -> insertNote("pdf", 7L, null, null), "a pdf note without a page");
        assertRefused(() -> insertNote("pdf", 7L, 3, 20), "a pdf note with a second");
        assertRefused(() -> insertNote("video", null, null, 20), "a video note without a block");
        assertRefused(() -> insertNote("video", 8L, 3, 20), "a video note with a page");
        assertRefused(() -> insertNote("none", 7L, null, null), "a whole-lesson note with a block");
        assertRefused(() -> insertNote("none", null, 3, null), "a whole-lesson note with a page");
        assertRefused(() -> insertNote("image", 7L, null, null), "an unknown anchor type");

        insertBookmark("pdf", 7L, 3, null);
        insertBookmark("video", 8L, null, 20);
        assertRefused(() -> insertBookmark("none", 7L, null, null), "a bookmark with no anchor");
        assertRefused(() -> insertBookmark("pdf", 7L, null, 20), "a pdf bookmark with a second only");
        assertRefused(() -> insertBookmark("video", 8L, 3, 20), "a video bookmark with a page");
        assertRefused(() -> jdbc.update("""
            insert into learning_hub.lesson_bookmarks (user_id, lesson_id, block_id, anchor_type, pdf_page,
                created_by, updated_by)
            values ('U1', 1, null, 'pdf', 3, 'U1', 'U1')
            """), "a bookmark without a block");

        List<String> notices = run(ROLLBACK);
        run(ROLLBACK);

        assertThat(notices).as("the rollback prints both row counts first")
            .anyMatch(text -> text.contains("lesson_notes (3 rows)") && text.contains("lesson_bookmarks (2 rows)"));
        assertThat(tableExists(NOTES)).isFalse();
        assertThat(tableExists(BOOKMARKS)).isFalse();
        assertThat(indexDefinitions()).isEmpty();
    }

    private void insertNote(String anchorType, Long blockId, Integer page, Integer second) {
        jdbc.update("""
            insert into learning_hub.lesson_notes (user_id, lesson_id, block_id, anchor_type, pdf_page, video_second,
                body, created_by, updated_by)
            values ('U1', 1, ?, ?, ?, ?, 'ghi chú', 'U1', 'U1')
            """, blockId, anchorType, page, second);
    }

    private void insertBookmark(String anchorType, Long blockId, Integer page, Integer second) {
        jdbc.update("""
            insert into learning_hub.lesson_bookmarks (user_id, lesson_id, block_id, anchor_type, pdf_page,
                video_second, created_by, updated_by)
            values ('U1', 1, ?, ?, ?, ?, 'U1', 'U1')
            """, blockId, anchorType, page, second);
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

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('" + table + "') is not null",
            Boolean.class));
    }

    private int columns(String table) {
        return jdbc.queryForObject("""
            select count(*) from information_schema.columns
            where table_schema = 'learning_hub' and table_name = ?
            """, Integer.class, table.substring(table.indexOf('.') + 1));
    }

    private int commentedColumns(String table) {
        return jdbc.queryForObject("""
            select count(*) from pg_attribute a
            where a.attrelid = '%s'::regclass and a.attnum > 0 and not a.attisdropped
              and col_description(a.attrelid, a.attnum) is not null
            """.formatted(table), Integer.class);
    }

    private List<String> indexDefinitions() {
        return jdbc.queryForList("""
            select indexdef from pg_indexes
            where schemaname = 'learning_hub' and indexname in ('idx_lesson_notes_user_lesson',
                'idx_lesson_bookmarks_user_lesson')
            """, String.class);
    }
}
