package vn.com.coreacademy.learning.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.config.LearningMediaProperties;
import vn.com.coreacademy.learning.constants.LearningCodes.AuditAction;
import vn.com.coreacademy.learning.constants.LearningCodes.BlockType;
import vn.com.coreacademy.learning.constants.LearningCodes.CompletionMode;
import vn.com.coreacademy.learning.constants.LearningCodes.LeaseState;
import vn.com.coreacademy.learning.constants.LearningCodes.LessonType;
import vn.com.coreacademy.learning.constants.LearningCodes.PlaybackState;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressEvent;
import vn.com.coreacademy.learning.constants.LearningCodes.ProgressStatus;
import vn.com.coreacademy.learning.constants.LearningCodes.ReminderStatus;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningDetails;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.entity.AssignmentRecipient;
import vn.com.coreacademy.learning.entity.BlockProgress;
import vn.com.coreacademy.learning.entity.Course;
import vn.com.coreacademy.learning.entity.CourseChapter;
import vn.com.coreacademy.learning.entity.CourseEnrollment;
import vn.com.coreacademy.learning.entity.CourseLesson;
import vn.com.coreacademy.learning.entity.CourseRevision;
import vn.com.coreacademy.learning.entity.LearningAsset;
import vn.com.coreacademy.learning.entity.LearningReminder;
import vn.com.coreacademy.learning.entity.LessonBlock;
import vn.com.coreacademy.learning.entity.LessonProgress;
import vn.com.coreacademy.learning.entity.VideoWatchRange;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.mapper.LearningMapper;
import vn.com.coreacademy.learning.repository.LearningRepository;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningProgressService {
    private final LearningMediaProperties media;
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final LearningSessionService sessions;
    private final ObjectMapper json;
    private final LearningRepository repository;
    private final LearningAccessPolicy access;
    private final LearningAssignmentService assignments;
    private final LearningContentService content;
    private final LearningWriteLock lock;
    private final LearningMapper mapper;
    private final IdentityAuditService audit;
    private final LearningQuizDirectory quizzes;

    public LearningDetails.EnrollmentDetail get(Long enrollmentId) {
        var actor = access.actor();
        var enrollment = assignments.requireOwnEnrollment(actor, enrollmentId, true);
        return new LearningDetails.EnrollmentDetail(mapper.view(enrollment),
            content.detail(repository.require(Course.class, enrollment.getCourseId()),
                repository.require(CourseRevision.class, enrollment.getRevisionId())),
            repository.lessonProgress(enrollmentId).stream().map(mapper::view).toList(),
            repository.blockProgress(enrollmentId).stream().map(mapper::view).toList());
    }

    /** Client chỉ gửi tương tác; backend xác định %/trạng thái và kiểm tra quyền trên đúng lần học. */
    @Transactional
    public LearningDetails.ProgressResult progress(Long enrollmentId, Long blockId,
                                                   LearningRequests.ProgressRequest request) {
        // Bước 1: serialize cập nhật và kiểm tra caller, lịch mở học, quan hệ block→lesson→revision.
        var actor = access.actor();
        lock.acquire();
        CourseEnrollment enrollment = assignments.requireOwnEnrollment(actor, enrollmentId, true);
        LessonBlock block = repository.require(LessonBlock.class, blockId);
        CourseLesson lesson = repository.require(CourseLesson.class, block.getLessonId());
        CourseChapter chapter = repository.require(CourseChapter.class, lesson.getChapterId());
        if (!chapter.getRevisionId().equals(enrollment.getRevisionId())) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        var lease = sessions.require(request.sessionId(), request.sessionFence(), enrollmentId, blockId);
        String fingerprint = fingerprint(enrollmentId, blockId, request);
        if (request.sequence() == lease.getLastEventSequence()
            && fingerprint.equals(lease.getLastEventHash())) {
            var current = repository.blockProgress(enrollmentId).stream()
                .filter(p -> p.getBlockId().equals(blockId)).findFirst().orElseThrow();
            var currentLesson = repository.lessonProgress(enrollmentId).stream()
                .filter(p -> p.getLessonId().equals(lesson.getId())).findFirst().orElseThrow();
            return new LearningDetails.ProgressResult(mapper.view(current), mapper.view(currentLesson),
                mapper.view(enrollment), mapper.receipt(lease));
        }
        sessions.requireActive(lease);
        if (request.sequence() != lease.getLastEventSequence() + 1) {
            throw new BusinessException(LearningResponseCode.STALE_EVENT);
        }
        Long mediaId = sessions.primary(block, enrollment.getRevisionId());
        if (!Objects.equals(request.assetId(), mediaId) || !Objects.equals(lease.getMediaAssetId(), mediaId)) {
            throw new BusinessException(LearningResponseCode.MEDIA_CHANGED);
        }
        if (request.watchedFromSeconds() != null && (!PlaybackState.PLAYING.equals(lease.getLastEventKind())
            || ProgressEvent.SEEK.equals(request.eventType()) || ProgressEvent.OPEN.equals(request.eventType()))) {
            throw new BusinessException(LearningResponseCode.STALE_EVENT);
        }
        var existing = repository.blockProgress(enrollmentId).stream().filter(p -> p.getBlockId().equals(blockId))
            .findFirst();
        Instant now = Instant.now();
        BlockProgress progress;
        if (existing.isPresent()) {
            progress = existing.get();
            LearningContentService.version(progress, request.expectedVersion());
        } else {
            if (request.expectedVersion() != 0) {
                throw new BusinessException(LearningResponseCode.CONFLICT);
            }
            progress = new BlockProgress();
            progress.setEnrollmentId(enrollmentId);
            progress.setBlockId(blockId);
            progress.setStatus(ProgressStatus.NOT_STARTED);
            progress.setProgressPercent(BigDecimal.ZERO);
            progress.setStartedAt(now);
            progress.setLastEventSequence(0L);
            repository.save(progress, actor.person().id());
        }

        if (progress.getMediaAssetId() != null && !Objects.equals(progress.getMediaAssetId(), mediaId)) {
            throw new BusinessException(LearningResponseCode.MEDIA_CHANGED);
        }
        progress.setMediaAssetId(mediaId);

        // Bước 2: lưu vị trí đúng loại; video dùng coverage đã kiểm tra theo đồng hồ server, không dùng % client.
        BigDecimal percentage = blockPercentage(enrollment, block, progress, request, now, actor.person().id());
        progress.setProgressPercent(progress.getProgressPercent().max(percentage));
        progress.setStatus(progress.getProgressPercent().compareTo(HUNDRED) >= 0 ? ProgressStatus.COMPLETED :
            ProgressStatus.IN_PROGRESS);
        if (ProgressStatus.COMPLETED.equals(progress.getStatus()) && progress.getCompletedAt() == null) {
            progress.setCompletedAt(now);
        }
        progress.setLastAccessAt(now);
        progress.setLastEventSequence(request.sequence());
        repository.save(progress, actor.person().id());
        repository.flush();

        // Bước 3: tổng hợp chi tiết lên bài và khóa trong cùng transaction; completion không làm mất lịch sử.
        LessonProgress lessonProgress = updateLesson(enrollment, lesson, now, actor.person().id());
        updateEnrollment(enrollment, lesson.getId(), blockId, now, actor.person().id());
        lease.setLastEventSequence(request.sequence());
        lease.setLastEventHash(fingerprint);
        lease.setLastEventKind(request.playing() ? PlaybackState.PLAYING : request.eventType());
        lease.setLastEventAcceptedAt(now);
        lease.setLastHeartbeatAt(now);
        lease.setExpiresAt(now.plus(media.getSessionLease()));
        if (ProgressEvent.CLOSE.equals(request.eventType())) {
            lease.setLeaseState(LeaseState.RELEASED);
            lease.setExpiresAt(now);
        }
        repository.save(lease, actor.person().id());
        repository.flush();
        return new LearningDetails.ProgressResult(mapper.view(progress), mapper.view(lessonProgress),
            mapper.view(enrollment), mapper.receipt(lease));
    }

    public List<LearningViews.LearningReminderView> reminders() {
        var actor = access.actor();
        access.requireLearner(actor);
        return repository.dueReminders(actor.person().id()).stream().map(mapper::view).toList();
    }

    @Transactional
    public LearningViews.LearningReminderView dismissReminder(Long id) {
        var actor = access.actor();
        access.requireLearner(actor);
        lock.acquire();
        LearningReminder reminder = repository.require(LearningReminder.class, id);
        var recipient = repository.require(AssignmentRecipient.class,
            reminder.getRecipientId());
        if (!actor.person().id().equals(recipient.getUserId())) {
            throw new AccessDeniedException("Reminder owner denied");
        }
        reminder.setStatus(ReminderStatus.SENT);
        reminder.setSentAt(Instant.now());
        repository.save(reminder, actor.person().id());
        repository.flush();
        return mapper.view(reminder);
    }

    private BigDecimal blockPercentage(CourseEnrollment enrollment, LessonBlock block, BlockProgress progress,
                                       LearningRequests.ProgressRequest request, Instant now, String actor) {
        if (!BlockType.VIDEO.equals(block.getBlockType()) && (request.videoPositionSeconds() != null
            || request.watchedFromSeconds() != null || request.watchedToSeconds() != null)) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        if (!BlockType.PDF.equals(block.getBlockType()) && request.pdfPage() != null) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        if (BlockType.VIDEO.equals(block.getBlockType())) {
            return video(enrollment, block, progress, request, now, actor);
        }
        if (BlockType.PDF.equals(block.getBlockType()) && request.pdfPage() != null) {
            LearningAsset asset = primaryAsset(enrollment.getRevisionId(), block.getId());
            if (asset.getPageCount() == null || request.pdfPage() > asset.getPageCount()) {
                throw BusinessException.validation("pdfPage", "OUT_OF_RANGE", "Trang PDF không hợp lệ.");
            }
            progress.setLastPdfPage(request.pdfPage());
            if (CompletionMode.PDF_END.equals(block.getCompletionMode()) &&
                request.pdfPage().equals(asset.getPageCount())) {
                return HUNDRED;
            }
        }
        if (CompletionMode.OPEN.equals(block.getCompletionMode())
            || (CompletionMode.MANUAL.equals(block.getCompletionMode()) && request.complete())) {
            return HUNDRED;
        }
        return progress.getProgressPercent();
    }

    private BigDecimal video(CourseEnrollment enrollment, LessonBlock block, BlockProgress progress,
                             LearningRequests.ProgressRequest request, Instant now, String actor) {
        LearningAsset asset = primaryAsset(enrollment.getRevisionId(), block.getId());
        BigDecimal duration = asset.getDurationSeconds();
        // Player báo currentTime với độ chính xác cao hơn duration đã lưu (ví dụ 11.136125 so với 11.136), nên vượt
        // duration trong ngưỡng grace là hợp lệ và được kẹp về duration; vượt xa hơn mới là vị trí sai.
        BigDecimal position = request.videoPositionSeconds();
        if (duration == null || duration.signum() <= 0 || position == null
            || position.compareTo(duration.add(LearningLimits.COVERAGE_GRACE_SECONDS)) > 0) {
            throw BusinessException.validation("videoPositionSeconds", "OUT_OF_RANGE", "Vị trí video chưa hợp lệ.");
        }
        position = position.min(duration);
        boolean from = request.watchedFromSeconds() != null;
        boolean to = request.watchedToSeconds() != null;
        if (from != to) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        if (from) {
            BigDecimal start = request.watchedFromSeconds();
            BigDecimal end = request.watchedToSeconds();
            if (end.compareTo(duration.add(LearningLimits.COVERAGE_GRACE_SECONDS)) <= 0) {
                end = end.min(duration);
                start = start.min(end);
            }
            long elapsed = progress.getLastAccessAt() == null ? 0
                : Duration.between(progress.getLastAccessAt(), now).toMillis();
            BigDecimal allowance = elapsed <= 0 || elapsed > media.getMaxEventGap().toMillis() ? BigDecimal.ZERO
                : BigDecimal.valueOf(elapsed,
                    3).multiply(LearningLimits.MAX_PLAYBACK_RATE).add(LearningLimits.COVERAGE_GRACE_SECONDS);
            if (progress.getLastVideoPositionSeconds() == null || end.compareTo(start) < 0
                || end.compareTo(duration) > 0 || end.subtract(start).compareTo(allowance) > 0
                || start.subtract(progress.getLastVideoPositionSeconds()).abs().compareTo(BigDecimal.ONE) > 0
                || end.subtract(position).abs().compareTo(BigDecimal.ONE) > 0) {
                throw BusinessException.validation("watchedToSeconds", "OUT_OF_RANGE",
                    "Khoảng xem không khớp vị trí hoặc thời gian server; gửi heartbeat mở video trước.");
            }
            mergeRange(progress.getId(), start, end, actor);
        }
        progress.setLastVideoPositionSeconds(position);
        BigDecimal watched = repository.watchRanges(progress.getId()).stream()
            .map(r -> r.getEndSecond().subtract(r.getStartSecond())).reduce(BigDecimal.ZERO, BigDecimal::add);
        progress.setWatchedSeconds(watched);
        BigDecimal coverage = watched.multiply(HUNDRED).divide(duration, 3, RoundingMode.DOWN).min(HUNDRED);
        // Chạm ngưỡng cấu hình mới hoàn thành; tua đến cuối không tạo thêm range được xem.
        BigDecimal threshold = block.getCompletionThreshold() == null ? LearningLimits.DEFAULT_VIDEO_THRESHOLD
            : block.getCompletionThreshold();
        return coverage.compareTo(threshold) >= 0 ? HUNDRED : coverage;
    }

    private LearningAsset primaryAsset(Long revisionId, Long blockId) {
        Long id = sessions.primary(repository.require(LessonBlock.class, blockId), revisionId);
        LearningAsset asset = repository.require(LearningAsset.class, id);
        return asset;
    }

    private void mergeRange(Long progressId, BigDecimal start, BigDecimal end, String actor) {
        if (start.compareTo(end) == 0) {
            return;
        }
        for (VideoWatchRange existing : repository.watchRanges(progressId)) {
            if (existing.getEndSecond().compareTo(start) >= 0 && existing.getStartSecond().compareTo(end) <= 0) {
                start = start.min(existing.getStartSecond());
                end = end.max(existing.getEndSecond());
                repository.remove(existing);
            }
        }
        VideoWatchRange range = new VideoWatchRange();
        range.setBlockProgressId(progressId);
        range.setStartSecond(start);
        range.setEndSecond(end);
        repository.save(range, actor);
        repository.flush();
    }

    private LessonProgress updateLesson(CourseEnrollment enrollment, CourseLesson lesson, Instant now, String actor) {
        var required = repository.blocks(enrollment.getRevisionId()).stream()
            .filter(b -> b.getLessonId().equals(lesson.getId()) && b.getIsRequired()).toList();
        var values = repository.blockProgress(enrollment.getId()).stream()
            .collect(Collectors.toMap(BlockProgress::getBlockId, BlockProgress::getProgressPercent));
        BigDecimal sum = required.stream().map(b -> values.getOrDefault(b.getId(), BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal percent = required.isEmpty() ? BigDecimal.ZERO
            : sum.divide(BigDecimal.valueOf(required.size()), 3, RoundingMode.DOWN);
        LessonProgress progress = repository.lessonProgress(enrollment.getId()).stream()
            .filter(p -> p.getLessonId().equals(lesson.getId())).findFirst().orElseGet(LessonProgress::new);
        progress.setEnrollmentId(enrollment.getId());
        progress.setLessonId(lesson.getId());
        progress.setProgressPercent(percent);
        progress.setStatus(percent.compareTo(HUNDRED) >= 0 ? ProgressStatus.COMPLETED : ProgressStatus.IN_PROGRESS);
        if (progress.getStartedAt() == null) {
            progress.setStartedAt(now);
        }
        progress.setLastAccessAt(now);
        if (ProgressStatus.COMPLETED.equals(progress.getStatus()) && progress.getCompletedAt() == null) {
            progress.setCompletedAt(now);
        }
        repository.save(progress, actor);
        repository.flush();
        return progress;
    }

    private void updateEnrollment(CourseEnrollment enrollment, Long lessonId, Long blockId, Instant now, String actor) {
        var lessons = repository.lessons(enrollment.getRevisionId()).stream().filter(CourseLesson::getIsRequired)
            .toList();
        Map<Long, BigDecimal> values = repository.lessonProgress(enrollment.getId()).stream()
            .collect(Collectors.toMap(LessonProgress::getLessonId, LessonProgress::getProgressPercent));
        BigDecimal sum = lessons.stream().map(l -> values.getOrDefault(l.getId(), BigDecimal.ZERO))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        Long finalQuizId = finalQuizId(enrollment.getRevisionId());
        boolean finalPassed = finalQuizId == null || quizzes.hasPassed(enrollment.getUserId(), enrollment.getId(),
            finalQuizId);
        boolean extraFinal = finalQuizId != null && lessons.stream()
            .noneMatch(lesson -> finalQuizId.equals(lesson.getQuizId()));
        int componentCount = lessons.size() + (extraFinal ? 1 : 0);
        BigDecimal completed = sum.add(extraFinal && finalPassed ? HUNDRED : BigDecimal.ZERO);
        BigDecimal percent = componentCount == 0 ? BigDecimal.ZERO
            : completed.divide(BigDecimal.valueOf(componentCount), 3, RoundingMode.DOWN);
        boolean complete = !lessons.isEmpty() && lessons.stream()
            .allMatch(l -> values.getOrDefault(l.getId(), BigDecimal.ZERO).compareTo(HUNDRED) >= 0)
            && finalPassed;
        enrollment.setProgressPercent(enrollment.getProgressPercent().max(percent));
        enrollment.setStatus(complete || enrollment.getCompletedAt() != null ? ProgressStatus.COMPLETED :
            ProgressStatus.IN_PROGRESS);
        if (enrollment.getStartedAt() == null) {
            enrollment.setStartedAt(now);
        }
        enrollment.setLastAccessAt(now);
        enrollment.setLastLessonId(lessonId);
        enrollment.setLastBlockId(blockId);
        if (complete && enrollment.getCompletedAt() == null) {
            enrollment.setCompletedAt(now);
            audit.record(actor, AuditAction.COURSE_COMPLETED, "course_enrollment", enrollment.getId().toString(),
                enrollment.getOrgIdSnapshot(), Map.of("revisionId", enrollment.getRevisionId()));
        }
        repository.save(enrollment, actor);
    }

    /**
     * Học viên đạt đề của khóa: bài quiz trỏ tới đề đó trong phiên bản đã nhận lên 100%, rồi tính lại tiến độ khóa.
     * Không ném lỗi khi lần học không còn hiệu lực (đã rút hoặc không thuộc người này) vì bên gọi đang nằm trong
     * transaction chấm bài và điểm phải được giữ; trả false trong trường hợp đó và khi đề không gắn với khóa (không có
     * bài quiz trỏ tới và cũng không phải bài thi cuối khóa của phiên bản). Đề là bài thi cuối khóa thì luôn tính lại
     * khóa dù không có bài quiz nào, vì đạt đề có thể là điều kiện còn thiếu để hoàn thành.
     * Tiến độ chỉ tăng nên chấm lại từ đạt xuống trượt không hạ bài học.
     */
    @Transactional
    public boolean completeQuizLessons(String userId, Long enrollmentId, Long quizId) {
        CourseEnrollment enrollment = repository.userEnrollments(userId).stream()
            .filter(e -> e.getId().equals(enrollmentId)).findFirst().orElse(null);
        if (enrollment == null) {
            return false;
        }
        List<CourseLesson> targets = repository.lessons(enrollment.getRevisionId()).stream()
            .filter(l -> LessonType.QUIZ.equals(l.getLessonType()) && quizId.equals(l.getQuizId())).toList();
        boolean finalExam = quizId.equals(finalQuizId(enrollment.getRevisionId()));
        Instant now = Instant.now();
        if (targets.isEmpty()) {
            if (!finalExam) {
                return false;
            }
            // Đề là bài thi cuối khóa nhưng không bài quiz nào trỏ tới: chỉ cần tính lại khóa.
            updateEnrollment(enrollment, enrollment.getLastLessonId(), enrollment.getLastBlockId(), now, userId);
            return true;
        }
        Map<Long, LessonProgress> existing = repository.lessonProgress(enrollmentId).stream()
            .collect(Collectors.toMap(LessonProgress::getLessonId, Function.identity(), (a, b) -> a));
        for (CourseLesson lesson : targets) {
            LessonProgress progress = existing.getOrDefault(lesson.getId(), new LessonProgress());
            progress.setEnrollmentId(enrollmentId);
            progress.setLessonId(lesson.getId());
            progress.setProgressPercent(HUNDRED);
            progress.setStatus(ProgressStatus.COMPLETED);
            if (progress.getStartedAt() == null) {
                progress.setStartedAt(now);
            }
            if (progress.getCompletedAt() == null) {
                progress.setCompletedAt(now);
            }
            progress.setLastAccessAt(now);
            repository.save(progress, userId);
        }
        repository.flush();
        updateEnrollment(enrollment, targets.get(0).getId(), null, now, userId);
        return true;
    }

    private Long finalQuizId(Long revisionId) {
        return repository.finalAssessment(revisionId).map(exam -> exam.getAssessmentRevisionId())
            .orElseGet(() -> repository.rules(revisionId).stream().findFirst()
                .map(rule -> rule.getFinalQuizId()).orElse(null));
    }

    private String fingerprint(Long enrollmentId, Long blockId, LearningRequests.ProgressRequest request) {
        try {
            byte[] bytes = (enrollmentId + ":" + blockId + ":" + json.writeValueAsString(request))
                .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException | JsonProcessingException exception) {
            throw new IllegalStateException("Progress event fingerprint unavailable", exception);
        }
    }

}
