package vn.com.coreacademy.assessment.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AuditAction;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ItemStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuizStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuizType;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.SubmitType;
import vn.com.coreacademy.assessment.constants.AssessmentLimits;
import vn.com.coreacademy.assessment.dto.AssessmentRequests;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.entity.Question;
import vn.com.coreacademy.assessment.entity.Quiz;
import vn.com.coreacademy.assessment.entity.QuizAttempt;
import vn.com.coreacademy.assessment.entity.QuizQuestion;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.assessment.mapper.AssessmentMapper;
import vn.com.coreacademy.assessment.repository.AssessmentRepository;
import vn.com.coreacademy.assessment.repository.QuizRepository;
import vn.com.coreacademy.assessment.repository.custom.QuizRepositoryCustom;
import vn.com.coreacademy.audit.service.IdentityAuditService;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess.ManagedCourse;
import vn.com.coreacademy.learning.service.LearningWriteLock;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class QuizService {
    private static final Set<String> SORTS = Set.of("id", "title", "updateTime");

    private final LearningAssessmentAccess learning;
    private final AssessmentRepository repository;
    private final QuizRepository quizzes;
    private final AssessmentMapper mapper;
    private final LearningWriteLock lock;
    private final IdentityAuditService audit;
    private final AttemptGrader grader;
    private final QuestionDrawer drawer;

    public PageResponse<AssessmentViews.QuizSummary> search(Long courseId, String q, String status, String quizType,
                                                            int page, int size, String sort) {
        learning.requireManagedCourse(courseId);
        if (status != null && !Set.of(QuizStatus.DRAFT, QuizStatus.PUBLISHED, QuizStatus.CLOSED).contains(status)) {
            throw BusinessException.validation("status", "INVALID_VALUE", "Giá trị không hợp lệ.");
        }
        if (quizType != null && !Set.of(QuizType.PRACTICE, QuizType.FINAL_EXAM).contains(quizType)) {
            throw BusinessException.validation("quizType", "INVALID_VALUE", "Giá trị không hợp lệ.");
        }
        var filter = new QuizRepositoryCustom.Filter(courseId, q, status, quizType);
        return PageResponse.from(quizzes.search(filter, PageQuery.of(page, size, sort, SORTS)));
    }

    public AssessmentViews.QuizDetail get(Long id) {
        Quiz quiz = requireQuiz(id);
        learning.requireManagedCourse(quiz.getCourseId());
        return detail(quiz);
    }

    /** Tạo đề ở trạng thái DRAFT; học viên chưa thấy cho tới khi xuất bản. */
    @Transactional
    public AssessmentViews.QuizDetail create(AssessmentRequests.QuizRequest request) {
        if (request.courseId() == null) {
            throw BusinessException.validation("courseId", "REQUIRED", "Thông tin này là bắt buộc.");
        }
        ManagedCourse course = learning.requireManagedCourse(request.courseId());
        String actor = learning.actor().person().id();
        lock.acquire();

        validateConfig(course.id(), null, request);
        Quiz quiz = new Quiz();
        quiz.setCourseId(course.id());
        quiz.setStatus(QuizStatus.DRAFT);
        apply(quiz, request);
        repository.save(quiz, actor);
        repository.flush();

        audit.record(actor, AuditAction.QUIZ_CREATED, "quiz", quiz.getId().toString(), course.ownerOrgId(),
            configAudit(quiz));
        return detail(quiz);
    }

    /**
     * Sửa cấu hình đề. Lượt đang làm/đã làm không đổi vì thông số chấm đã chụp vào lượt; đề đã xuất bản vẫn phải
     * đủ câu ACTIVE cho số câu mới.
     */
    @Transactional
    public AssessmentViews.QuizDetail update(Long id, Long expectedVersion, AssessmentRequests.QuizRequest request) {
        Quiz quiz = requireQuiz(id);
        ManagedCourse course = learning.requireManagedCourse(quiz.getCourseId());
        String actor = learning.actor().person().id();
        lock.acquire();

        requireVersion(quiz, expectedVersion);
        if (request.courseId() != null && !request.courseId().equals(quiz.getCourseId())) {
            throw BusinessException.validation("courseId", "INVALID_VALUE",
                "Không thể chuyển bài kiểm tra sang khóa khác.");
        }
        validateConfig(course.id(), id, request);
        if (QuizStatus.PUBLISHED.equals(quiz.getStatus())
            && repository.activePoolSize(id) < request.questionCount()) {
            throw new BusinessException(AssessmentResponseCode.QUIZ_NOT_READY);
        }
        apply(quiz, request);
        repository.save(quiz, actor);
        repository.flush();

        audit.record(actor, AuditAction.QUIZ_UPDATED, "quiz", id.toString(), course.ownerOrgId(), configAudit(quiz));
        return detail(quiz);
    }

    /** Thay toàn bộ pool câu hỏi; câu phải thuộc cùng khóa và chưa xóa. Lịch sử đề đã phát nằm ở snapshot lượt thi. */
    @Transactional
    public AssessmentViews.QuizDetail replacePool(Long id, Long expectedVersion,
                                                  AssessmentRequests.PoolRequest request) {
        Quiz quiz = requireQuiz(id);
        ManagedCourse course = learning.requireManagedCourse(quiz.getCourseId());
        String actor = learning.actor().person().id();
        lock.acquire();

        requireVersion(quiz, expectedVersion);
        List<AssessmentRequests.PoolItem> items = request.items();
        Map<Long, Question> found = repository.questions(items.stream().map(AssessmentRequests.PoolItem::questionId)
            .toList()).stream().collect(Collectors.toMap(Question::getId, Function.identity()));
        Set<Long> seen = new HashSet<>();
        Long programId = learning.courseProgramId(quiz.getCourseId());
        long active = 0;
        for (int i = 0; i < items.size(); i++) {
            Long questionId = items.get(i).questionId();
            Question question = found.get(questionId);
            if (!seen.add(questionId)) {
                throw BusinessException.validation("items[" + i + "].questionId", "DUPLICATE",
                    "Câu hỏi bị lặp trong pool.");
            }
            if (question == null || Boolean.TRUE.equals(question.getDeleted())
                || (question.getProgramId() != null
                    ? !question.getProgramId().equals(programId)
                    : !Objects.equals(question.getCourseId(), quiz.getCourseId()))) {
                throw BusinessException.field(AssessmentResponseCode.INVALID_REFERENCE, "items[" + i + "].questionId",
                    "NOT_FOUND", "Câu hỏi không tồn tại hoặc không thuộc chương trình của khóa học này.");
            }
            if (ItemStatus.ACTIVE.equals(question.getStatus())) {
                active++;
            }
        }
        if (QuizStatus.PUBLISHED.equals(quiz.getStatus()) && active < quiz.getQuestionCount()) {
            throw new BusinessException(AssessmentResponseCode.QUIZ_NOT_READY);
        }

        repository.clearPool(id);
        for (AssessmentRequests.PoolItem item : items) {
            QuizQuestion entry = new QuizQuestion();
            entry.setQuizId(id);
            entry.setQuestionId(item.questionId());
            entry.setSortNo(item.sortNo());
            entry.setPoints(item.points());
            entry.setCreatedBy(actor);
            repository.persist(entry);
        }
        repository.save(quiz, actor);
        repository.touchVersion(quiz);
        repository.flush();

        audit.record(actor, AuditAction.QUIZ_POOL_CHANGED, "quiz", id.toString(), course.ownerOrgId(),
            Map.of("poolSize", items.size(), "activePoolSize", active));
        return detail(quiz);
    }

    /** Xuất bản (hoặc mở lại đề đã đóng) khi pool đủ câu ACTIVE và thời gian đóng còn ở tương lai. */
    @Transactional
    public AssessmentViews.QuizDetail publish(Long id, AssessmentRequests.VersionRequest request) {
        Quiz quiz = requireQuiz(id);
        ManagedCourse course = learning.requireManagedCourse(quiz.getCourseId());
        String actor = learning.actor().person().id();
        lock.acquire();

        requireVersion(quiz, request.expectedVersion());
        if (QuizStatus.PUBLISHED.equals(quiz.getStatus())) {
            return detail(quiz);
        }
        Long programId = learning.courseProgramId(quiz.getCourseId());
        List<Long> questionIds = repository.pool(id).stream().map(QuizQuestion::getQuestionId).toList();
        if (repository.questions(questionIds).stream().anyMatch(question -> question.getProgramId() != null
            && !question.getProgramId().equals(programId))) {
            throw BusinessException.field(AssessmentResponseCode.INVALID_REFERENCE, "items", "INVALID_VALUE",
                "Bộ đề có câu hỏi ngoài chương trình hiện tại của khóa học.");
        }
        if (repository.activePoolSize(id) < quiz.getQuestionCount()) {
            throw new BusinessException(AssessmentResponseCode.QUIZ_NOT_READY);
        }
        if (quiz.getCloseAt() != null && !quiz.getCloseAt().isAfter(ServerTime.now())) {
            throw BusinessException.validation("closeAt", "OUT_OF_RANGE", "Thời gian đóng đề phải ở tương lai.");
        }
        quiz.setStatus(QuizStatus.PUBLISHED);
        quiz.setPublishedAt(ServerTime.now());
        repository.save(quiz, actor);
        repository.flush();

        audit.record(actor, AuditAction.QUIZ_PUBLISHED, "quiz", id.toString(), course.ownerOrgId(),
            configAudit(quiz));
        return detail(quiz);
    }

    /**
     * Đóng đề: học viên không bắt đầu được lượt mới và các lượt đang làm được tự nộp ngay với đáp án đã lưu
     * (AUTO_SUBMITTED/QUIZ_CLOSED). Kết quả vẫn xem được; mở lại bằng publish.
     */
    @Transactional
    public AssessmentViews.QuizDetail close(Long id, AssessmentRequests.VersionRequest request) {
        Quiz quiz = requireQuiz(id);
        ManagedCourse course = learning.requireManagedCourse(quiz.getCourseId());
        String actor = learning.actor().person().id();
        lock.acquire();

        requireVersion(quiz, request.expectedVersion());
        if (QuizStatus.CLOSED.equals(quiz.getStatus())) {
            return detail(quiz);
        }
        if (!QuizStatus.PUBLISHED.equals(quiz.getStatus())) {
            throw new BusinessException(AssessmentResponseCode.CONFLICT);
        }
        quiz.setStatus(QuizStatus.CLOSED);
        repository.save(quiz, actor);
        repository.flush();

        Instant now = ServerTime.now();
        int submitted = 0;
        for (Long attemptId : repository.inProgressAttemptIds(id)) {
            QuizAttempt attempt = repository.lockAttempt(attemptId);
            if (AttemptStatus.IN_PROGRESS.equals(attempt.getStatus())) {
                grader.submitOnBehalf(attempt, AttemptStatus.AUTO_SUBMITTED, SubmitType.QUIZ_CLOSED, now);
                submitted++;
            }
        }
        repository.flush();

        Map<String, Object> changes = configAudit(quiz);
        changes.put("autoSubmittedAttempts", submitted);
        audit.record(actor, AuditAction.QUIZ_CLOSED, "quiz", id.toString(), course.ownerOrgId(), changes);
        return detail(quiz);
    }

    /** Xóa mềm đề chưa từng có lượt thi; đề đã có lượt là lịch sử thi và chỉ được đóng. */
    @Transactional
    public void delete(Long id, Long expectedVersion) {
        Quiz quiz = requireQuiz(id);
        ManagedCourse course = learning.requireManagedCourse(quiz.getCourseId());
        String actor = learning.actor().person().id();
        lock.acquire();

        requireVersion(quiz, expectedVersion);
        if (repository.quizHasAttempts(id)) {
            throw new BusinessException(AssessmentResponseCode.QUIZ_IN_USE);
        }
        quiz.setDeleted(true);
        repository.save(quiz, actor);
        repository.flush();

        audit.record(actor, AuditAction.QUIZ_DELETED, "quiz", id.toString(), course.ownerOrgId(),
            Map.of("title", quiz.getTitle(), "status", quiz.getStatus()));
    }

    /** Bốc thử một đề theo cấu hình hiện tại (cả khi còn DRAFT) để giảng viên kiểm tra; không tạo lượt thi. */
    public AssessmentViews.QuizPreview preview(Long id) {
        Quiz quiz = requireQuiz(id);
        learning.requireManagedCourse(quiz.getCourseId());
        List<QuestionDrawer.Drawn> drawn = drawer.draw(quiz);
        List<AssessmentViews.PreviewQuestion> questions = new ArrayList<>();
        for (int i = 0; i < drawn.size(); i++) {
            QuestionDrawer.Drawn item = drawn.get(i);
            questions.add(new AssessmentViews.PreviewQuestion(i + 1, item.question().getId(),
                item.question().getCode(), item.question().getQuestionType(), item.question().getContent(),
                item.choices().stream().map(mapper::view).toList(), item.points(), item.question().getExplanation()));
        }
        BigDecimal maxScore = drawn.stream().map(QuestionDrawer.Drawn::points).reduce(BigDecimal.ZERO, BigDecimal::add);
        return new AssessmentViews.QuizPreview(id, drawn.size(), maxScore, questions);
    }

    private AssessmentViews.QuizDetail detail(Quiz quiz) {
        List<QuizQuestion> pool = repository.pool(quiz.getId());
        Map<Long, Question> questions = repository.questions(pool.stream().map(QuizQuestion::getQuestionId).toList())
            .stream().collect(Collectors.toMap(Question::getId, Function.identity()));
        List<AssessmentViews.PoolEntry> entries = pool.stream().map(entry -> {
            Question question = questions.get(entry.getQuestionId());
            String status = Boolean.TRUE.equals(question.getDeleted()) ? null : question.getStatus();
            BigDecimal points = entry.getPoints() == null ? question.getPoints() : entry.getPoints();
            return new AssessmentViews.PoolEntry(question.getId(), question.getCode(), preview(question.getContent()),
                question.getQuestionType(), question.getDifficulty(), status, entry.getSortNo(), entry.getPoints(),
                points);
        }).toList();
        int active = (int) entries.stream().filter(e -> ItemStatus.ACTIVE.equals(e.status())).count();
        return new AssessmentViews.QuizDetail(mapper.view(quiz), entries, active);
    }

    private Quiz requireQuiz(Long id) {
        Quiz quiz = repository.require(Quiz.class, id);
        if (Boolean.TRUE.equals(quiz.getDeleted())) {
            throw new BusinessException(AssessmentResponseCode.NOT_FOUND);
        }
        return quiz;
    }

    private static void requireVersion(Quiz quiz, Long expectedVersion) {
        if (!Objects.equals(quiz.getRowVersion(), expectedVersion)) {
            throw new BusinessException(AssessmentResponseCode.CONFLICT);
        }
    }

    /** Mỗi khóa tối đa một đề FINAL_EXAM; thời gian đóng phải sau thời gian mở. */
    private void validateConfig(Long courseId, Long quizId, AssessmentRequests.QuizRequest request) {
        if (request.openAt() != null && request.closeAt() != null && !request.closeAt().isAfter(request.openAt())) {
            throw BusinessException.validation("closeAt", "OUT_OF_RANGE", "Thời gian đóng phải sau thời gian mở.");
        }
        if (QuizType.FINAL_EXAM.equals(request.quizType()) && repository.finalQuizExists(courseId, quizId)) {
            throw BusinessException.field(AssessmentResponseCode.DUPLICATE, "quizType", "ALREADY_EXISTS",
                "Khóa học đã có bài thi cuối khóa.");
        }
    }

    private static void apply(Quiz quiz, AssessmentRequests.QuizRequest request) {
        quiz.setTitle(request.title().trim());
        quiz.setDescription(request.description());
        quiz.setQuizType(request.quizType());
        quiz.setSelectionMode(request.selectionMode());
        quiz.setQuestionCount(request.questionCount());
        quiz.setShuffleQuestions(request.shuffleQuestions());
        quiz.setShuffleOptions(request.shuffleOptions());
        quiz.setTimeLimitMinutes(request.timeLimitMinutes());
        quiz.setOpenAt(request.openAt());
        quiz.setCloseAt(request.closeAt());
        quiz.setGraceSeconds(request.graceSeconds());
        quiz.setPassPercent(request.passPercent());
        quiz.setScoringMode(request.scoringMode());
        quiz.setScoreMethod(request.scoreMethod());
        quiz.setReviewPolicy(request.reviewPolicy());
        quiz.setMaxAttempts(request.maxAttempts());
        quiz.setRetakeWaitHours(request.retakeWaitHours());
        quiz.setAllowRetakeAfterPass(request.allowRetakeAfterPass());
        quiz.setRequireLessonsCompleted(request.requireLessonsCompleted());
    }

    private static Map<String, Object> configAudit(Quiz quiz) {
        Map<String, Object> changes = new LinkedHashMap<>();
        changes.put("status", quiz.getStatus());
        changes.put("questionCount", quiz.getQuestionCount());
        changes.put("timeLimitMinutes", quiz.getTimeLimitMinutes());
        changes.put("passPercent", quiz.getPassPercent());
        changes.put("maxAttempts", quiz.getMaxAttempts());
        return changes;
    }

    static String preview(String content) {
        return content.length() <= AssessmentLimits.CONTENT_PREVIEW_LENGTH ? content
            : content.substring(0, AssessmentLimits.CONTENT_PREVIEW_LENGTH);
    }
}
