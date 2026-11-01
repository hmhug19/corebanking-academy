package vn.com.coreacademy.learning;

import org.springframework.jdbc.core.JdbcTemplate;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;

import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;

/**
 * Bài học có đủ loại khối để thử neo: PDF 4 trang, video 30,5 giây, video có tài nguyên hỏng, PDF thiếu số trang và
 * khối text. Dựng bằng khóa thật của AssessmentFixtures rồi thêm khối và tài nguyên bằng SQL, vì API tải lên cần S3.
 */
final class LessonNotesFixture {
    record Lesson(Course course, long pdf, long video, long failedVideo, long unboundedPdf, long text) {
        long id() {
            return course.lessonId();
        }
    }

    private LessonNotesFixture() {
    }

    static Lesson lesson(AssessmentFixtures fixtures, JdbcTemplate jdbc, String... learners) {
        Course course = fixtures.course(learners);
        long text = jdbc.queryForObject("select min(block_id) from learning_hub.lesson_blocks where lesson_id = ?",
            Long.class, course.lessonId());
        long pdf = block(jdbc, course, "pdf", asset(jdbc, course, "application/pdf", 4, null, "ready"), 10);
        long video = block(jdbc, course, "video", asset(jdbc, course, "video/mp4", null, "30.500", "ready"), 11);
        long failedVideo = block(jdbc, course, "video", asset(jdbc, course, "video/mp4", null, "30.500", "failed"),
            12);
        long unboundedPdf = block(jdbc, course, "pdf", asset(jdbc, course, "application/pdf", null, null, "ready"),
            13);
        return new Lesson(course, pdf, video, failedVideo, unboundedPdf, text);
    }

    private static long asset(JdbcTemplate jdbc, Course course, String mime, Integer pages, String seconds,
                              String status) {
        return jdbc.queryForObject("""
            insert into learning_hub.assets (owner_org_id, uploaded_by, storage_type, original_name, mime_type,
                size_bytes, page_count, duration_seconds, status, created_by, updated_by)
            values (?, ?, 's3', 'tai-nguyen', ?, 1000, ?, ?::numeric, ?, ?, ?) returning asset_id
            """, Long.class, course.ownerOrgId(), INSTRUCTOR, mime, pages, seconds, status, INSTRUCTOR, INSTRUCTOR);
    }

    private static long block(JdbcTemplate jdbc, Course course, String type, long assetId, int sortNo) {
        return jdbc.queryForObject("""
            insert into learning_hub.lesson_blocks (lesson_id, block_type, title, completion_mode, is_required,
                sort_no, media_asset_id, created_by, updated_by)
            values (?, ?, ?, 'open', false, ?, ?, ?, ?) returning block_id
            """, Long.class, course.lessonId(), type, "Khối " + type + " " + sortNo, sortNo, assetId, INSTRUCTOR,
            INSTRUCTOR);
    }
}
