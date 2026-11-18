package vn.com.coreacademy.assistant;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import vn.com.coreacademy.assistant.course.CourseJobService;
import vn.com.coreacademy.assistant.course.CourseJobViews.Inputs;

/**
 * Starts an old "Tạo bằng AI" job the way the retired endpoint did, so tests still have jobs that were running when the
 * start was retired: the worker, the reads and the review screen keep serving them.
 */
final class OldCourseJobs {
    private final CourseJobService jobs;
    private final ObjectMapper json;

    OldCourseJobs(CourseJobService jobs, ObjectMapper json) {
        this.jobs = jobs;
        this.json = json;
    }

    /** The job's view, as GET /course-jobs/{id} returns it. */
    JsonNode start(String user, Map<String, Object> inputs) {
        return start(user, "quy-trinh.md", "# Quy trình\n\nNội dung.\n", inputs);
    }

    JsonNode start(String user, String fileName, String content, Map<String, Object> inputs) {
        MockMultipartFile file = new MockMultipartFile("file", fileName, "text/markdown",
            content.getBytes(StandardCharsets.UTF_8));
        Inputs parsed = json.convertValue(inputs, Inputs.class);
        return as(user, () -> json.valueToTree(jobs.create(file, parsed)));
    }

    /** What the start throws for this user and form. */
    RuntimeException refusal(String user, Map<String, Object> inputs) {
        try {
            start(user, inputs);
        } catch (RuntimeException exception) {
            return exception;
        }
        throw new AssertionError("The job started");
    }

    private static <T> T as(String userId, Supplier<T> action) {
        var original = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new JwtAuthenticationToken(Jwt.withTokenValue("it")
            .header("alg", "none").subject(userId).build(), List.of()));
        try {
            SecurityContextHolder.setContext(context);
            return action.get();
        } finally {
            SecurityContextHolder.setContext(original);
        }
    }
}
