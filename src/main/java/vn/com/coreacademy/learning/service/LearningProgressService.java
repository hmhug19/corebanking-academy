package vn.com.coreacademy.learning.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.dto.LearningDetails;
import vn.com.coreacademy.learning.dto.LearningRequests;
import vn.com.coreacademy.learning.dto.LearningViews;
import vn.com.coreacademy.learning.entity.AssignmentRecipient;
import vn.com.coreacademy.learning.entity.BlockAsset;
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
    private static final BigDecimal HUNDRED = new BigDecimal("100");
    private final LearningRepository repository;
    private final LearningAccessPolicy access;
    private final LearningAssignmentService assignments;
    private final LearningContentService content;
    private final LearningWriteLock lock;
    private final LearningMapper mapper;
    private final IdentityAuditService audit;

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
        var existing = repository.blockProgress(enrollmentId).stream().filter(p -> p.getBlockId().equals(blockId))
            .findFirst();
        Instant now = Instant.now();
        BlockProgress progress;
        if (existing.isPresent()) {
            progress = existing.get();
            LearningContentService.version(progress, request.expectedVersion());
            if (request.sequence() <= progress.getLastEventSequence()) {
                throw new BusinessException(LearningResponseCode.CONFLICT);
            }
        } else {
            if (request.expectedVersion() != 0) {
                throw new BusinessException(LearningResponseCode.CONFLICT);
            }
            progress = new BlockProgress();
            progress.setEnrollmentId(enrollmentId);
            progress.setBlockId(blockId);
            progress.setStatus("not_started");
            progress.setProgressPercent(BigDecimal.ZERO);
            progress.setStartedAt(now);
            progress.setLastEventSequence(0L);
            repository.save(progress, actor.person().id());
        }

        // Bước 2: lưu vị trí đúng loại; video dùng coverage đã kiểm tra theo đồng hồ server, không dùng % client.
        BigDecimal percentage = blockPercentage(enrollment, block, progress, request, now, actor.person().id());
        progress.setProgressPercent(progress.getProgressPercent().max(percentage));
        progress.setStatus(progress.getProgressPercent().compareTo(HUNDRED) >= 0 ? "completed" : "in_progress");
        if ("completed".equals(progress.getStatus()) && progress.getCompletedAt() == null) {
            progress.setCompletedAt(now);
        }
        progress.setLastAccessAt(now);
        progress.setLastEventSequence(request.sequence());
        repository.save(progress, actor.person().id());
        repository.flush();

        // Bước 3: tổng hợp chi tiết lên bài và khóa trong cùng transaction; completion không làm mất lịch sử.
        LessonProgress lessonProgress = updateLesson(enrollment, lesson, now, actor.person().id());
        updateEnrollment(enrollment, lesson.getId(), blockId, now, actor.person().id());
        repository.flush();
        return new LearningDetails.ProgressResult(mapper.view(progress), mapper.view(lessonProgress),
            mapper.view(enrollment));
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
        reminder.setStatus("sent");
        reminder.setSentAt(Instant.now());
        repository.save(reminder, actor.person().id());
        repository.flush();
        return mapper.view(reminder);
    }

    private BigDecimal blockPercentage(CourseEnrollment enrollment, LessonBlock block, BlockProgress progress,
                                       LearningRequests.ProgressRequest request, Instant now, String actor) {
        if (!"video".equals(block.getBlockType()) && (request.videoPositionSeconds() != null
            || request.watchedFromSeconds() != null || request.watchedToSeconds() != null)) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        if (!"pdf".equals(block.getBlockType()) && request.pdfPage() != null) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        if ("video".equals(block.getBlockType())) {
            return video(enrollment, block, progress, request, now, actor);
        }
        if ("pdf".equals(block.getBlockType()) && request.pdfPage() != null) {
            LearningAsset asset = primaryAsset(enrollment.getRevisionId(), block.getId());
            if (asset.getPageCount() == null || request.pdfPage() > asset.getPageCount()) {
                throw BusinessException.validation("pdfPage", "OUT_OF_RANGE", "Trang PDF không hợp lệ.");
            }
            progress.setLastPdfPage(request.pdfPage());
            if ("pdf_end".equals(block.getCompletionMode()) && request.pdfPage().equals(asset.getPageCount())) {
                return HUNDRED;
            }
        }
        if ("open".equals(block.getCompletionMode())
            || ("manual".equals(block.getCompletionMode()) && request.complete())) {
            return HUNDRED;
        }
        return progress.getProgressPercent();
    }

    private BigDecimal video(CourseEnrollment enrollment, LessonBlock block, BlockProgress progress,
                             LearningRequests.ProgressRequest request, Instant now, String actor) {
        LearningAsset asset = primaryAsset(enrollment.getRevisionId(), block.getId());
        BigDecimal duration = asset.getDurationSeconds();
        if (duration == null || duration.signum() <= 0 || request.videoPositionSeconds() == null
            || request.videoPositionSeconds().compareTo(duration) > 0) {
            throw BusinessException.validation("videoPositionSeconds", "OUT_OF_RANGE", "Vị trí video chưa hợp lệ.");
        }
        boolean from = request.watchedFromSeconds() != null;
        boolean to = request.watchedToSeconds() != null;
        if (from != to) {
            throw new BusinessException(LearningResponseCode.INVALID_REFERENCE);
        }
        if (from) {
            BigDecimal start = request.watchedFromSeconds();
            BigDecimal end = request.watchedToSeconds();
            BigDecimal allowance = progress.getLastAccessAt() == null ? BigDecimal.ZERO
                : BigDecimal.valueOf(Duration.between(progress.getLastAccessAt(), now).toMillis(), 3)
                    .multiply(new BigDecimal("2")).add(new BigDecimal("1"));
            if (progress.getLastVideoPositionSeconds() == null || end.compareTo(start) < 0
                || end.compareTo(duration) > 0 || end.subtract(start).compareTo(allowance) > 0
                || start.subtract(progress.getLastVideoPositionSeconds()).abs().compareTo(BigDecimal.ONE) > 0
                || end.subtract(request.videoPositionSeconds()).abs().compareTo(BigDecimal.ONE) > 0) {
                throw BusinessException.validation("watchedToSeconds", "OUT_OF_RANGE",
                    "Khoảng xem không khớp vị trí hoặc thời gian server; gửi heartbeat mở video trước.");
            }
            mergeRange(progress.getId(), start, end, actor);
        }
        progress.setLastVideoPositionSeconds(request.videoPositionSeconds());
        BigDecimal watched = repository.watchRanges(progress.getId()).stream()
            .map(r -> r.getEndSecond().subtract(r.getStartSecond())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal coverage = watched.multiply(HUNDRED).divide(duration, 3, RoundingMode.DOWN).min(HUNDRED);
        // Chạm ngưỡng cấu hình mới hoàn thành; tua đến cuối không tạo thêm range được xem.
        return coverage.compareTo(block.getCompletionThreshold()) >= 0 ? HUNDRED : coverage;
    }

    private LearningAsset primaryAsset(Long revisionId, Long blockId) {
        BlockAsset link = repository.assetLinks(revisionId).stream()
            .filter(a -> a.getBlockId().equals(blockId) && "primary".equals(a.getUsage())).findFirst()
            .orElseThrow(() -> new BusinessException(LearningResponseCode.ASSET_INVALID));
        LearningAsset asset = repository.require(LearningAsset.class, link.getAssetId());
        if (!"ready".equals(asset.getStatus())) {
            throw new BusinessException(LearningResponseCode.ASSET_INVALID);
        }
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
        progress.setStatus(percent.compareTo(HUNDRED) >= 0 ? "completed" : "in_progress");
        if (progress.getStartedAt() == null) {
            progress.setStartedAt(now);
        }
        progress.setLastAccessAt(now);
        if ("completed".equals(progress.getStatus()) && progress.getCompletedAt() == null) {
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
        BigDecimal percent = lessons.isEmpty() ? BigDecimal.ZERO
            : sum.divide(BigDecimal.valueOf(lessons.size()), 3, RoundingMode.DOWN);
        boolean complete = !lessons.isEmpty() && lessons.stream()
            .allMatch(l -> values.getOrDefault(l.getId(), BigDecimal.ZERO).compareTo(HUNDRED) >= 0)
            && repository.assessments(enrollment.getRevisionId()).isEmpty();
        enrollment.setProgressPercent(enrollment.getProgressPercent().max(percent));
        enrollment.setStatus(complete || enrollment.getCompletedAt() != null ? "completed" : "in_progress");
        if (enrollment.getStartedAt() == null) {
            enrollment.setStartedAt(now);
        }
        enrollment.setLastAccessAt(now);
        enrollment.setLastLessonId(lessonId);
        enrollment.setLastBlockId(blockId);
        if (complete && enrollment.getCompletedAt() == null) {
            enrollment.setCompletedAt(now);
            audit.record(actor, "LEARNING_COURSE_COMPLETED", "course_enrollment", enrollment.getId().toString(),
                enrollment.getOrgIdSnapshot(), Map.of("revisionId", enrollment.getRevisionId()));
        }
        repository.save(enrollment, actor);
    }
}
