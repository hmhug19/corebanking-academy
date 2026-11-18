package vn.com.coreacademy.assistant.course;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vn.com.coreacademy.assistant.config.GenerationProperties;
import vn.com.coreacademy.assistant.drafting.CourseDraftingRepository;
import vn.com.coreacademy.assistant.exception.AssistantResponseCode;
import vn.com.coreacademy.common.exception.BusinessException;

/**
 * The daily allowance of course generation: a user starts at most {@code jobs-per-day} old jobs and drafting sessions
 * together in any 24 hours. Deleted, cancelled and failed ones count too, so deleting does not give the allowance back.
 * Uploads of chat attachments have a daily cap of their own.
 */
@Component
@RequiredArgsConstructor
public class GenerationAllowance {
    private static final Duration DAY = Duration.ofHours(24);

    private final CourseJobRepository jobs;
    private final CourseDraftingRepository drafting;
    private final GenerationProperties properties;

    /** Serialises the check and the insert of one user's starts. Must run inside the inserting transaction. */
    public void lock(String userId) {
        jobs.lockOwner(userId);
    }

    /** Refuses a start after the day's allowance, telling the user when the oldest one leaves the 24 hours. */
    public void require(String userId) {
        List<OffsetDateTime> started = drafting.startedSince(userId, OffsetDateTime.now().minus(DAY));
        if (started.size() < properties.jobsPerDay()) {
            return;
        }
        throw BusinessException.field(AssistantResponseCode.JOB_RATE_LIMITED, "limit", "DAILY_LIMIT",
            "Bạn đã tạo " + properties.jobsPerDay() + " khóa học bằng AI trong ngày, thử lại sau "
                + waitText(started, properties.jobsPerDay()) + ".");
    }

    /**
     * Refuses a chat attachment for Soạn cùng trợ lý once {@code source-uploads-per-day} of the user's attachments of
     * the last 24 hours wait unused: an upload starts nothing, so the start allowance does not cover it, yet each one
     * stores a file. One a session was started from no longer counts (that start did).
     */
    public void requireUpload(String userId) {
        List<OffsetDateTime> uploaded = drafting.unusedSourcesSince(userId, OffsetDateTime.now().minus(DAY));
        if (uploaded.size() < properties.sourceUploadsPerDay()) {
            return;
        }
        throw BusinessException.field(AssistantResponseCode.JOB_RATE_LIMITED, "limit", "DAILY_UPLOAD_LIMIT",
            "Bạn đã tải lên " + properties.sourceUploadsPerDay() + " tài liệu cho trợ lý trong ngày, thử lại sau "
                + waitText(uploaded, properties.sourceUploadsPerDay()) + ".");
    }

    /** How long until the oldest of the last {@code allowed} times ({@code times} oldest first) leaves the 24 hours. */
    private static String waitText(List<OffsetDateTime> times, int allowed) {
        Duration wait = Duration.between(OffsetDateTime.now(), times.get(times.size() - allowed).plus(DAY));
        long minutes = Math.max(1, (wait.toSeconds() + 59) / 60);

        return minutes >= 60 ? (minutes + 59) / 60 + " giờ" : minutes + " phút";
    }
}
