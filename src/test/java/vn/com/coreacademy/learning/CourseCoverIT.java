package vn.com.coreacademy.learning;

import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import vn.com.coreacademy.assessment.support.ApiClient;
import vn.com.coreacademy.assessment.support.AssessmentFixtures;
import vn.com.coreacademy.assessment.support.AssessmentFixtures.Course;
import vn.com.coreacademy.assessment.support.AssessmentIntegrationTest;

import static org.assertj.core.api.Assertions.assertThat;
import static vn.com.coreacademy.assessment.support.ApiClient.get;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.INSTRUCTOR;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER;
import static vn.com.coreacademy.assessment.support.AssessmentFixtures.LEARNER_2;

/**
 * Thẻ khóa của học viên mang coverAssetId của revision trên thẻ, và ai thấy thẻ thì đọc được ảnh bìa đó qua
 * /learning-assets/{id}/content mà không cần blockId; ảnh bìa của bản nháp chưa ai thấy thì vẫn bị từ chối.
 */
class CourseCoverIT extends AssessmentIntegrationTest {
    private static final String LEARNING = "/api/v1/me/learning";
    private static final String CONTENT = "/api/v1/learning-assets/{id}/content";
    /** Kho không hỗ trợ: qua được bước phân quyền thì dừng ở lỗi nghiệp vụ này, không cần stream tệp thật. */
    private static final String PAST_PERMISSION = "LEARNING-ASSET_STORAGE_UNSUPPORTED";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private JdbcTemplate jdbc;

    private ApiClient api;
    private AssessmentFixtures fixtures;

    @BeforeAll
    void setUp() {
        api = new ApiClient(mvc, json);
        fixtures = new AssessmentFixtures(api, jdbc);
    }

    @Test
    void theCardNamesTheCoverOfItsRevision() {
        Course course = fixtures.course(LEARNER);
        long asset = asset(course);
        coverPublished(course, asset);

        for (String list : new String[] {"/courses", "/catalog"}) {
            assertThat(card(LEARNER, list, course).path("coverAssetId").asLong()).as(list).isEqualTo(asset);
        }
        JsonNode single = api.ok(LEARNER, get(LEARNING + "/courses/{c}", course.id())).path("card");
        assertThat(single.path("coverAssetId").asLong()).isEqualTo(asset);
    }

    @Test
    void aCourseWithoutCoverHasANullField() {
        Course course = fixtures.course(LEARNER);

        JsonNode card = card(LEARNER, "/courses", course);
        assertThat(card.has("coverAssetId")).isTrue();
        assertThat(card.path("coverAssetId").isNull()).isTrue();
    }

    @Test
    void learnersWhoSeeTheCardReadItsCover() {
        Course course = fixtures.course(LEARNER);
        long asset = asset(course);
        coverPublished(course, asset);

        api.error(LEARNER, get(CONTENT, asset), PAST_PERMISSION);
        // Khóa đã xuất bản hiện trong danh mục của học viên khác dù chưa được giao.
        assertThat(card(LEARNER_2, "/catalog", course).path("coverAssetId").asLong()).isEqualTo(asset);
        api.error(LEARNER_2, get(CONTENT, asset), PAST_PERMISSION);
    }

    @Test
    void theCoverOfADraftNobodySeesIsRefused() {
        Course course = fixtures.course(LEARNER);
        long asset = asset(course);
        api.ok(INSTRUCTOR, api.post(Map.of(), "/api/v1/courses/{c}/draft", course.id()));
        jdbc.update("""
            update learning_hub.course_revisions set cover_asset_id = ?
            where course_id = ? and revision_id <> (select published_revision_id from learning_hub.courses
                where course_id = ?)
            """, asset, course.id(), course.id());

        api.http(LEARNER, get(CONTENT, asset), 403, "CORE-ACCESS_DENIED");
        api.http(LEARNER_2, get(CONTENT, asset), 403, "CORE-ACCESS_DENIED");
        api.error(INSTRUCTOR, get(CONTENT, asset), PAST_PERMISSION);
    }

    private long asset(Course course) {
        return jdbc.queryForObject("""
            insert into learning_hub.assets (owner_org_id, uploaded_by, storage_type, original_name, mime_type,
                size_bytes, status, created_by, updated_by)
            values (?, ?, 'external', 'bia.png', 'image/png', 1000, 'ready', ?, ?) returning asset_id
            """, Long.class, course.ownerOrgId(), INSTRUCTOR, INSTRUCTOR, INSTRUCTOR);
    }

    private void coverPublished(Course course, long asset) {
        jdbc.update("""
            update learning_hub.course_revisions set cover_asset_id = ?
            where revision_id = (select published_revision_id from learning_hub.courses where course_id = ?)
            """, asset, course.id());
    }

    private JsonNode card(String user, String list, Course course) {
        JsonNode page = api.ok(user, get(LEARNING + list + "?size=100&sort=id,desc"));
        for (JsonNode row : page.path("items")) {
            if (row.path("courseId").asLong() == course.id()) {
                return row;
            }
        }
        throw new AssertionError("course " + course.id() + " not on " + list);
    }
}
