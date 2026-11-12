package vn.com.coreacademy.assistant.course;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

import vn.com.coreacademy.assistant.course.CourseJobViews.JobSummary;

import static org.assertj.core.api.Assertions.assertThat;

/** The editor finds the job of a course through the summary's courseId. */
class JobSummaryTest {
    @Test
    void aSummaryNamesTheCourseTheJobWritesInto() {
        JobSummary summary = new JobSummary(7, "running", "lessons", 40, null, "tai-lieu.pdf", null,
            OffsetDateTime.now(), 55L);

        assertThat(summary.courseId()).isEqualTo(55L);
    }
}
