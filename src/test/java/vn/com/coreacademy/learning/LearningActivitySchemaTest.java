package vn.com.coreacademy.learning;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ConditionEvaluationResult;
import org.junit.jupiter.api.extension.ExecutionCondition;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Upgrade, backfill và rollback của learning_activity_days chạy bằng đúng file SQL vận hành trên PostgreSQL thật,
 * bắt đầu từ baseline mà bảng đã bị bỏ để mô phỏng DB cũ. Không dùng Spring: chỉ kiểm schema.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(LearningActivitySchemaTest.DockerRequired.class)
class LearningActivitySchemaTest {
    private static final String UPGRADE = "docs/sql/upgrade/2026-11-02-learning-leaderboard.sql";
    private static final String BACKFILL = "docs/sql/upgrade/2026-11-02-learning-leaderboard-backfill.sql";
    private static final String ROLLBACK = "docs/sql/upgrade/2026-11-02-learning-leaderboard.rollback.sql";
    private static final List<String> BASELINES = List.of("learning_hub_identity.sql", "learning_hub_learning.sql",
        "learning_hub_assessment.sql");
    private static final String DAYS = "learning_hub.learning_activity_days";

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
    private long quizSeed;

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
        jdbc.execute("DROP TABLE " + DAYS);
    }

    @AfterAll
    void stop() {
        postgres.stop();
    }

    @Test
    void upgradeBackfillAndRollbackRunCleanlyAndTwice() throws Exception {
        assertThat(tableExists()).isFalse();

        run(UPGRADE);
        run(UPGRADE);
        assertThat(tableExists()).isTrue();
        assertThat(commentedColumns()).as("every column has a comment").isEqualTo(columns()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select obj_description('" + DAYS + "'::regclass)", String.class))
            .isNotBlank();
        assertThat(jdbc.queryForList("""
            select indexdef from pg_indexes
            where schemaname = 'learning_hub' and indexname = 'idx_learning_activity_days_user_date'
            """, String.class)).singleElement().asString().contains("(user_id, activity_date DESC)");
        assertThat(jdbc.queryForObject("""
            select count(*) from pg_constraint
            where conrelid = '%s'::regclass and contype in ('f', 'u')
            """.formatted(DAYS), Integer.class)).as("no FK and no UNIQUE").isZero();

        // 23:30 và 00:30 giờ Việt Nam là hai ngày khác nhau; hai bản ghi cùng ngày chỉ cho một dòng.
        seedActivity("U1", "2026-10-30 16:30:00+00");
        seedActivity("U1", "2026-10-30 17:30:00+00");
        seedActivity("U1", "2026-10-31 02:00:00+00");
        seedActivity("U2", "2026-10-31 02:00:00+00");
        jdbc.update("insert into " + DAYS
            + " (user_id, activity_date, first_source) values ('U2', '2026-10-31', 'session')");

        run(BACKFILL);
        int afterFirst = count();
        run(BACKFILL);

        assertThat(count()).as("a second backfill adds nothing").isEqualTo(afterFirst);
        assertThat(days("U1")).containsExactly("2026-10-31", "2026-10-30");
        assertThat(days("U2")).as("the existing day is kept").containsExactly("2026-10-31");
        assertThat(jdbc.queryForObject("select first_source from " + DAYS
            + " where user_id = 'U2'", String.class)).isEqualTo("session");
        assertThat(jdbc.queryForObject("select first_source from " + DAYS
            + " where user_id = 'U1' limit 1", String.class)).isEqualTo("backfill");

        run(ROLLBACK);
        run(ROLLBACK);
        assertThat(tableExists()).isFalse();
        assertThat(jdbc.queryForObject("""
            select count(*) from pg_indexes where indexname = 'idx_learning_activity_days_user_date'
            """, Integer.class)).isZero();
    }

    /** Một lượt xem bài học (dòng lesson_progress) và một lượt thi đã chấm vào cùng thời điểm. */
    private void seedActivity(String user, String at) {
        Long enrollment = jdbc.queryForObject("""
            insert into learning_hub.course_enrollments (course_id, revision_id, user_id, cycle_no, enrollment_origin,
                enrolled_at, status, progress_percent, created_by, updated_by)
            values (1, 1, ?, 1, 'assigned', now(), 'in_progress', 0, ?, ?) returning enrollment_id
            """, Long.class, user, user, user);
        jdbc.update("""
            insert into learning_hub.lesson_progress (enrollment_id, lesson_id, status, progress_percent, created_by,
                updated_by, update_time)
            values (?, 1, 'in_progress', 10, ?, ?, ?::timestamptz)
            """, enrollment, user, user, at);
        jdbc.update("""
            insert into learning_hub.quiz_attempts (quiz_id, user_id, enrollment_id, attempt_no, status, started_at,
                deadline_at, submitted_at, time_limit_minutes, pass_percent, scoring_mode, grace_seconds,
                total_questions, max_score)
            values (?, ?, ?, 1, 'SUBMITTED', ?::timestamptz - interval '10 minutes',
                ?::timestamptz + interval '1 hour', ?::timestamptz, 30, 70, 'ALL_OR_NOTHING', 0, 1, 1)
            """, ++quizSeed, user, enrollment, at, at, at);
    }

    private void run(String file) throws SQLException, IOException {
        String sql = Files.readString(Path.of(file), StandardCharsets.UTF_8);
        try (Connection connection = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(),
            postgres.getPassword()); Statement statement = connection.createStatement()) {
            // Cả file trong một lệnh như khi chạy psql -f; BEGIN/COMMIT nằm trong file.
            statement.execute(sql);
        }
    }

    private boolean tableExists() {
        return Boolean.TRUE.equals(jdbc.queryForObject("select to_regclass('" + DAYS + "') is not null",
            Boolean.class));
    }

    private int columns() {
        return jdbc.queryForObject("""
            select count(*) from information_schema.columns
            where table_schema = 'learning_hub' and table_name = 'learning_activity_days'
            """, Integer.class);
    }

    private int commentedColumns() {
        return jdbc.queryForObject("""
            select count(*) from pg_attribute a
            where a.attrelid = '%s'::regclass and a.attnum > 0 and not a.attisdropped
              and col_description(a.attrelid, a.attnum) is not null
            """.formatted(DAYS), Integer.class);
    }

    private int count() {
        return jdbc.queryForObject("select count(*) from " + DAYS, Integer.class);
    }

    private List<String> days(String user) {
        return jdbc.queryForList("select activity_date::text from " + DAYS
            + " where user_id = ? order by activity_date desc", String.class, user);
    }
}
