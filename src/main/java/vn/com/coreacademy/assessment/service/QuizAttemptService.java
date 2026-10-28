package vn.com.coreacademy.assessment.service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.AttemptStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.BlockReason;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuestionType;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuizStatus;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ReviewPolicy;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.ScoreMethod;
import vn.com.coreacademy.assessment.constants.AssessmentLimits;
import vn.com.coreacademy.assessment.dto.AssessmentRequests;
import vn.com.coreacademy.assessment.dto.AssessmentViews;
import vn.com.coreacademy.assessment.dto.AssessmentViews.AttemptSummary;
import vn.com.coreacademy.assessment.dto.AssessmentViews.OptionText;
import vn.com.coreacademy.assessment.entity.AttemptAnswer;
import vn.com.coreacademy.assessment.entity.AttemptQuestion;
import vn.com.coreacademy.assessment.entity.Quiz;
import vn.com.coreacademy.assessment.entity.QuizAttempt;
import vn.com.coreacademy.assessment.exception.AssessmentResponseCode;
import vn.com.coreacademy.assessment.repository.AssessmentRepository;
import vn.com.coreacademy.assessment.repository.QuizAttemptRepository;
import vn.com.coreacademy.assessment.repository.custom.QuizAttemptRepositoryCustom;
import vn.com.coreacademy.assessment.service.QuestionDrawer.Drawn;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.ApiResponseCode;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.PageQuery;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess;
import vn.com.coreacademy.learning.service.LearningAssessmentAccess.StudyingLearner;

/**
 * Luồng làm bài của học viên. Mọi mốc thời gian dùng giờ server; đề đã phát và cấu hình chấm được chụp vào lượt
 * để giảng viên sửa đề/câu hỏi sau đó không đổi lượt đang làm hoặc đã làm.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class QuizAttemptService {
    private static final int MAX_USER_AGENT = 500;
    private static final int MAX_CLIENT_IP = 45;
    private static final Set<String> MINE_SORTS = Set.of("id", "startedAt", "submittedAt");

    private final LearningAssessmentAccess learning;
    private final AssessmentRepository repository;
    private final AttemptGrader grader;
    private final AttemptJson json;
    private final AttemptReadModel readModel;
    private final QuestionDrawer drawer;
    private final QuizAttemptRepository attemptRepository;

    private record Block(String reason, Instant availableAt) {
    }

    /** Màn giới thiệu: cấu hình công khai, tổng hợp lượt và lý do chưa thể làm bài (nếu có). */
    public AssessmentViews.QuizIntro intro(Long quizId) {
        Quiz quiz = visibleQuiz(quizId);
        StudyingLearner learner = learning.requireStudyingLearner(quiz.getCourseId());
        Instant now = ServerTime.now();
        List<QuizAttempt> attempts = repository.attempts(quizId, learner.userId());
        AttemptSummary summary = summarize(quiz, attempts, repository.grantedAttempts(quizId, learner.userId()), now);
        Block block = summary.runningAttemptId() != null ? null : block(quiz, summary, learner, now);
        var view = new AssessmentViews.LearnerQuiz(quiz.getId(), quiz.getTitle(), quiz.getDescription(),
            quiz.getQuizType(), quiz.getStatus(), quiz.getQuestionCount(), quiz.getTimeLimitMinutes(),
            quiz.getPassPercent(), quiz.getMaxAttempts(), quiz.getRetakeWaitHours(), quiz.getOpenAt(),
            quiz.getCloseAt(), quiz.getScoreMethod(), quiz.getReviewPolicy(), quiz.getShuffleQuestions(),
            repository.poolHasActiveMultiple(quizId));
        return new AssessmentViews.QuizIntro(view, new AssessmentViews.CourseRef(quiz.getCourseId(),
            learner.courseTitle()), summary, block == null, block == null ? null : block.reason(),
            block == null ? null : block.availableAt(), now);
    }

    /**
     * Bắt đầu lượt mới hoặc trả lại lượt đang làm còn hạn (resume, kể cả đổi máy). Lượt đang làm đã quá hạn được
     * tự nộp trước khi xét lượt mới. Khóa theo cặp (đề, học viên) chặn hai request đồng thời tạo hai lượt.
     */
    @Transactional
    public AssessmentViews.AttemptSession start(Long quizId, String clientIp, String userAgent) {
        Quiz quiz = visibleQuiz(quizId);
        StudyingLearner learner = learning.requireStudyingLearner(quiz.getCourseId());
        repository.lockLearnerQuiz(quizId, learner.userId());
        Instant now = ServerTime.now();

        List<QuizAttempt> attempts = repository.attempts(quizId, learner.userId());
        QuizAttempt running = attempts.stream().filter(a -> AttemptStatus.IN_PROGRESS.equals(a.getStatus()))
            .findFirst().orElse(null);
        if (running != null) {
            running = repository.lockAttempt(running.getId());
            if (!grader.expired(running, now)) {
                return session(running, quiz.getTitle(), now);
            }
            grader.finalizeExpired(running);
            repository.flush();
        }

        AttemptSummary summary = summarize(quiz, attempts, repository.grantedAttempts(quizId, learner.userId()), now);
        Block block = block(quiz, summary, learner, now);
        if (block != null) {
            throw new BusinessException(blockCode(block.reason()));
        }
        int attemptNo = attempts.stream().mapToInt(QuizAttempt::getAttemptNo).max().orElse(0) + 1;
        QuizAttempt attempt = createAttempt(quiz, learner, attemptNo, now, clientIp, userAgent);
        return session(attempt, quiz.getTitle(), now);
    }

    public AssessmentViews.AttemptSession session(Long attemptId) {
        QuizAttempt attempt = ownAttempt(repository.require(QuizAttempt.class, attemptId));
        Quiz quiz = repository.require(Quiz.class, attempt.getQuizId());
        return session(attempt, quiz.getTitle(), ServerTime.now());
    }

    /** Lưu đáp án và/hoặc cờ xem lại của một câu; chỉ khi lượt còn IN_PROGRESS và chưa quá deadline + ân hạn. */
    @Transactional
    public AssessmentViews.AnswerReceipt saveAnswer(Long attemptId, Long attemptQuestionId,
                                                    AssessmentRequests.AnswerPatch patch) {
        boolean hasSelection = patch.has("selectedOptionIds");
        boolean hasFlag = patch.has("flagged");
        if (!hasSelection && !hasFlag) {
            throw BusinessException.validation("", "EMPTY_UPDATE", "Cần gửi ít nhất một thông tin để cập nhật.");
        }
        if (hasSelection && patch.getSelectedOptionIds() == null) {
            throw BusinessException.validation("selectedOptionIds", "REQUIRED", "Thông tin này là bắt buộc.");
        }
        if (hasFlag && patch.getFlagged() == null) {
            throw BusinessException.validation("flagged", "REQUIRED", "Thông tin này là bắt buộc.");
        }

        QuizAttempt attempt = ownAttempt(repository.lockAttempt(attemptId));
        Instant now = ServerTime.now();
        if (!AttemptStatus.IN_PROGRESS.equals(attempt.getStatus()) || grader.expired(attempt, now)) {
            throw new BusinessException(AssessmentResponseCode.ATTEMPT_CLOSED);
        }
        AttemptQuestion question = repository.require(AttemptQuestion.class, attemptQuestionId);
        if (!question.getAttemptId().equals(attemptId)) {
            throw new BusinessException(AssessmentResponseCode.NOT_FOUND);
        }

        AttemptAnswer answer = repository.answer(attemptQuestionId).orElse(null);
        if (hasSelection) {
            List<Long> selected = validSelection(question, patch.getSelectedOptionIds());
            if (answer == null) {
                answer = new AttemptAnswer();
                answer.setAttemptQuestionId(attemptQuestionId);
                answer.setAnsweredAt(now);
                answer.setSelectedOptionIds(json.write(selected));
                repository.persist(answer);
            } else {
                answer.setSelectedOptionIds(json.write(selected));
                answer.setChangedAt(now);
                answer.setChangeCount(answer.getChangeCount() + 1);
            }
        }
        if (hasFlag) {
            question.setFlagged(patch.getFlagged());
        }
        attempt.setLastSavedAt(now);
        repository.flush();

        List<Long> current = answer == null ? List.of() : json.ids(answer.getSelectedOptionIds());
        return new AssessmentViews.AnswerReceipt(attemptQuestionId, current, question.getFlagged(), now,
            attempt.getDeadlineAt(), now);
    }

    /** Nộp bài và chấm phía server; gọi lại sau khi đã nộp trả về đúng kết quả cũ (idempotent). */
    @Transactional
    public AssessmentViews.AttemptResult submit(Long attemptId) {
        QuizAttempt attempt = ownAttempt(repository.lockAttempt(attemptId));
        if (AttemptStatus.IN_PROGRESS.equals(attempt.getStatus())) {
            grader.submit(attempt, ServerTime.now());
            repository.flush();
        }
        return result(attempt);
    }

    public AssessmentViews.AttemptResult result(Long attemptId) {
        QuizAttempt attempt = ownAttempt(repository.require(QuizAttempt.class, attemptId));
        if (AttemptStatus.IN_PROGRESS.equals(attempt.getStatus())) {
            throw new BusinessException(AssessmentResponseCode.ATTEMPT_IN_PROGRESS);
        }
        return result(attempt);
    }

    public AssessmentViews.AttemptHistory history(Long quizId) {
        Quiz quiz = visibleQuiz(quizId);
        String userId = learning.actor().person().id();
        Instant now = ServerTime.now();
        List<QuizAttempt> attempts = repository.attempts(quizId, userId);
        AttemptSummary summary = summarize(quiz, attempts, repository.grantedAttempts(quizId, userId), now);
        List<AssessmentViews.AttemptRow> rows = attempts.stream().map(a -> new AssessmentViews.AttemptRow(a.getId(),
            a.getAttemptNo(), a.getStatus(), a.getSubmitType(), a.getStartedAt(), a.getSubmittedAt(),
            AttemptReadModel.duration(a),
            a.getTotalQuestions(), a.getCorrectCount(), a.getScorePercent(), a.getPassed())).toList();
        return new AssessmentViews.AttemptHistory(quiz.getId(), quiz.getTitle(), summary, rows);
    }

    /** Mọi lượt của caller qua các đề (trang Tiến độ); chỉ dữ liệu của chính caller. */
    public PageResponse<AssessmentViews.MyAttemptRow> mine(Long quizId, String status, int page, int size,
                                                           String sort) {
        var filter = mineFilter(quizId, status);
        return PageResponse.from(attemptRepository.searchMine(filter, PageQuery.of(page, size, sort, MINE_SORTS)));
    }

    /** CSV tối đa MAX_EXPORT_ROWS dòng, UTF-8 BOM; ô bắt đầu bằng ký tự công thức được vô hiệu hóa. */
    public byte[] exportMine(Long quizId, String status, String sort) {
        var filter = mineFilter(quizId, status);
        Sort order = PageQuery.of(0, 1, sort, MINE_SORTS).getSort();
        var rows = attemptRepository.searchMine(filter,
            PageRequest.of(0, AssessmentLimits.MAX_EXPORT_ROWS + 1, order)).getContent();
        if (rows.size() > AssessmentLimits.MAX_EXPORT_ROWS) {
            throw new BusinessException(AssessmentResponseCode.EXPORT_TOO_LARGE);
        }
        return Csv.write(List.of("Bài kiểm tra", "Lượt", "Trạng thái", "Cách nộp", "Bắt đầu", "Nộp lúc",
            "Số câu đúng", "Tổng số câu", "Điểm %", "Đạt"), rows.stream().map(row -> new Object[] {row.quizTitle(),
            row.attemptNo(), row.status(), row.submitType(), row.startedAt(), row.submittedAt(), row.correctCount(),
            row.totalQuestions(), row.scorePercent(), row.passed()}).toList());
    }

    private QuizAttemptRepositoryCustom.MineFilter mineFilter(Long quizId, String status) {
        if (status != null && !Set.of(AttemptStatus.IN_PROGRESS, AttemptStatus.SUBMITTED,
            AttemptStatus.AUTO_SUBMITTED, AttemptStatus.VOIDED).contains(status)) {
            throw BusinessException.validation("status", "INVALID_VALUE", "Giá trị không hợp lệ.");
        }
        return new QuizAttemptRepositoryCustom.MineFilter(learning.actor().person().id(), quizId, status);
    }

    private QuizAttempt createAttempt(Quiz quiz, StudyingLearner learner, int attemptNo, Instant now,
                                      String clientIp, String userAgent) {
        List<Drawn> drawn = drawer.draw(quiz);
        BigDecimal maxScore = drawn.stream().map(Drawn::points).reduce(BigDecimal.ZERO, BigDecimal::add);
        Instant deadline = now.plus(Duration.ofMinutes(quiz.getTimeLimitMinutes()));
        if (quiz.getCloseAt() != null && quiz.getCloseAt().isBefore(deadline)) {
            deadline = quiz.getCloseAt();
        }

        QuizAttempt attempt = new QuizAttempt();
        attempt.setQuizId(quiz.getId());
        attempt.setUserId(learner.userId());
        attempt.setEnrollmentId(learner.enrollmentId());
        attempt.setAttemptNo(attemptNo);
        attempt.setStatus(AttemptStatus.IN_PROGRESS);
        attempt.setStartedAt(now);
        attempt.setDeadlineAt(deadline);
        attempt.setTimeLimitMinutes(quiz.getTimeLimitMinutes());
        attempt.setPassPercent(quiz.getPassPercent());
        attempt.setScoringMode(quiz.getScoringMode());
        attempt.setGraceSeconds(quiz.getGraceSeconds());
        attempt.setTotalQuestions(drawn.size());
        attempt.setMaxScore(maxScore);
        attempt.setOrgId(learner.orgId());
        attempt.setTeamId(learner.teamId());
        attempt.setClientIp(truncate(clientIp, MAX_CLIENT_IP));
        attempt.setUserAgent(truncate(userAgent, MAX_USER_AGENT));
        repository.persist(attempt);
        repository.flush();

        for (int i = 0; i < drawn.size(); i++) {
            Drawn item = drawn.get(i);
            AttemptQuestion snapshot = new AttemptQuestion();
            snapshot.setAttemptId(attempt.getId());
            snapshot.setQuestionId(item.question().getId());
            snapshot.setQuestionVersion(item.question().getContentVersion());
            snapshot.setDisplayOrder(i + 1);
            snapshot.setQuestionType(item.question().getQuestionType());
            snapshot.setContentSnapshot(item.question().getContent());
            snapshot.setOptionsSnapshot(json.write(item.options()));
            snapshot.setCorrectOptionIds(json.write(item.correctIds()));
            snapshot.setExplanationSnapshot(item.question().getExplanation());
            snapshot.setPoints(item.points());
            snapshot.setCompetencyId(item.question().getCompetencyId());
            snapshot.setDifficulty(item.question().getDifficulty());
            repository.persist(snapshot);
        }
        repository.flush();
        return attempt;
    }

    private AssessmentViews.AttemptSession session(QuizAttempt attempt, String quizTitle, Instant now) {
        Map<Long, AttemptAnswer> answers = readModel.answersByQuestion(attempt.getId());
        List<AssessmentViews.SessionQuestion> questions = repository.attemptQuestions(attempt.getId()).stream()
            .map(q -> {
                AttemptAnswer answer = answers.get(q.getId());
                return new AssessmentViews.SessionQuestion(q.getId(), q.getDisplayOrder(), q.getQuestionType(),
                    q.getContentSnapshot(), json.options(q.getOptionsSnapshot()),
                    answer == null ? List.of() : json.ids(answer.getSelectedOptionIds()), q.getFlagged());
            }).toList();
        boolean expired = AttemptStatus.IN_PROGRESS.equals(attempt.getStatus()) && grader.expired(attempt, now);
        return new AssessmentViews.AttemptSession(attempt.getId(), attempt.getQuizId(), quizTitle,
            attempt.getAttemptNo(), attempt.getStatus(), attempt.getStartedAt(), attempt.getDeadlineAt(), now,
            expired, attempt.getTotalQuestions(), questions);
    }

    private AssessmentViews.AttemptResult result(QuizAttempt attempt) {
        Quiz quiz = repository.require(Quiz.class, attempt.getQuizId());
        Instant now = ServerTime.now();
        AttemptSummary summary = summarize(quiz, repository.attempts(quiz.getId(), attempt.getUserId()),
            repository.grantedAttempts(quiz.getId(), attempt.getUserId()), now);
        List<AttemptQuestion> questions = repository.attemptQuestions(attempt.getId());
        Map<Long, AttemptAnswer> answers = readModel.answersByQuestion(attempt.getId());
        boolean graded = !AttemptStatus.VOIDED.equals(attempt.getStatus());
        boolean reviewAvailable = graded && reviewAllowed(quiz, summary, now);
        List<AssessmentViews.ReviewQuestion> review = reviewAvailable ? questions.stream()
            .map(q -> readModel.review(q, answers.get(q.getId()))).toList() : null;
        return new AssessmentViews.AttemptResult(attempt.getId(), quiz.getId(), quiz.getTitle(), quiz.getCourseId(),
            attempt.getAttemptNo(), attempt.getStatus(), attempt.getSubmitType(), attempt.getStartedAt(),
            attempt.getSubmittedAt(), AttemptReadModel.duration(attempt), attempt.getTotalQuestions(),
            attempt.getCorrectCount(), attempt.getRawScore(), attempt.getMaxScore(), attempt.getScorePercent(),
            attempt.getPassPercent(), attempt.getPassed(), summary,
            graded ? readModel.competencies(questions, answers) : List.of(), quiz.getReviewPolicy(), reviewAvailable,
            review);
    }

    /**
     * Tổng hợp lượt của (đề, học viên). Lượt tính ngay khi bắt đầu, trừ VOIDED. passed/finalScore theo score_method:
     * BEST = từng đạt/điểm cao nhất, LAST = lượt chấm gần nhất.
     */
    private static AttemptSummary summarize(Quiz quiz, List<QuizAttempt> attempts, int extra, Instant now) {
        List<QuizAttempt> graded = attempts.stream()
            .filter(a -> Set.of(AttemptStatus.SUBMITTED, AttemptStatus.AUTO_SUBMITTED).contains(a.getStatus()))
            .sorted(Comparator.comparing(QuizAttempt::getSubmittedAt).thenComparing(QuizAttempt::getId))
            .toList();
        int used = (int) attempts.stream().filter(a -> !AttemptStatus.VOIDED.equals(a.getStatus())).count();
        int left = Math.max(0, quiz.getMaxAttempts() + extra - used);
        BigDecimal best = graded.stream().map(QuizAttempt::getScorePercent).filter(Objects::nonNull)
            .max(BigDecimal::compareTo).orElse(null);
        QuizAttempt last = graded.isEmpty() ? null : graded.get(graded.size() - 1);
        boolean everPassed = graded.stream().anyMatch(a -> Boolean.TRUE.equals(a.getPassed()));
        boolean byLast = ScoreMethod.LAST.equals(quiz.getScoreMethod());
        boolean passed = byLast ? last != null && Boolean.TRUE.equals(last.getPassed()) : everPassed;
        BigDecimal lastScore = last == null ? null : last.getScorePercent();
        Long running = attempts.stream().filter(a -> AttemptStatus.IN_PROGRESS.equals(a.getStatus()))
            .filter(a -> !now.isAfter(a.getDeadlineAt().plusSeconds(a.getGraceSeconds())))
            .map(QuizAttempt::getId).findFirst().orElse(null);
        Instant lastSubmitted = last == null ? null : last.getSubmittedAt();
        Instant next = lastSubmitted == null || quiz.getRetakeWaitHours() == 0 ? null
            : lastSubmitted.plus(Duration.ofHours(quiz.getRetakeWaitHours()));
        return new AttemptSummary(quiz.getMaxAttempts(), extra, used, left, best, lastScore, byLast ? lastScore : best,
            passed, everPassed, running, lastSubmitted, next);
    }

    /** Điều kiện bắt đầu lượt mới theo thứ tự ưu tiên hiển thị cho học viên. */
    private Block block(Quiz quiz, AttemptSummary summary, StudyingLearner learner, Instant now) {
        if (QuizStatus.CLOSED.equals(quiz.getStatus())
            || (quiz.getCloseAt() != null && !now.isBefore(quiz.getCloseAt()))) {
            return new Block(BlockReason.CLOSED, null);
        }
        if (quiz.getOpenAt() != null && now.isBefore(quiz.getOpenAt())) {
            return new Block(BlockReason.NOT_OPEN, quiz.getOpenAt());
        }
        if (repository.activePoolSize(quiz.getId()) < quiz.getQuestionCount()) {
            return new Block(BlockReason.NOT_READY, null);
        }
        if (quiz.getRequireLessonsCompleted()
            && !learning.requiredLessonsCompleted(learner.enrollmentId(), learner.revisionId())) {
            return new Block(BlockReason.LESSONS_REQUIRED, null);
        }
        if (summary.everPassed() && !quiz.getAllowRetakeAfterPass()) {
            return new Block(BlockReason.PASSED, null);
        }
        if (summary.attemptsLeft() <= 0) {
            return new Block(BlockReason.NO_ATTEMPTS, null);
        }
        if (summary.nextAttemptAt() != null && now.isBefore(summary.nextAttemptAt())) {
            return new Block(BlockReason.WAIT, summary.nextAttemptAt());
        }
        return null;
    }

    private static ApiResponseCode blockCode(String reason) {
        return switch (reason) {
            case BlockReason.CLOSED -> AssessmentResponseCode.QUIZ_CLOSED;
            case BlockReason.NOT_OPEN -> AssessmentResponseCode.QUIZ_NOT_OPEN;
            case BlockReason.NOT_READY -> AssessmentResponseCode.QUIZ_NOT_READY;
            case BlockReason.LESSONS_REQUIRED -> AssessmentResponseCode.LESSONS_REQUIRED;
            case BlockReason.PASSED -> AssessmentResponseCode.ALREADY_PASSED;
            case BlockReason.NO_ATTEMPTS -> AssessmentResponseCode.NO_ATTEMPTS_LEFT;
            default -> AssessmentResponseCode.RETAKE_WAIT;
        };
    }

    private static boolean reviewAllowed(Quiz quiz, AttemptSummary summary, Instant now) {
        return switch (quiz.getReviewPolicy()) {
            case ReviewPolicy.AFTER_SUBMIT -> true;
            case ReviewPolicy.AFTER_PASS -> summary.everPassed();
            case ReviewPolicy.AFTER_CLOSE -> QuizStatus.CLOSED.equals(quiz.getStatus())
                || (quiz.getCloseAt() != null && !now.isBefore(quiz.getCloseAt()));
            default -> false;
        };
    }

    /** Chỉ chọn trong phương án của đề đã phát; câu SINGLE tối đa 1 lựa chọn. Giữ thứ tự, bỏ trùng. */
    private List<Long> validSelection(AttemptQuestion question, List<Long> requested) {
        List<Long> selected = List.copyOf(new LinkedHashSet<>(requested));
        Set<Long> allowed = json.options(question.getOptionsSnapshot()).stream().map(OptionText::id)
            .collect(Collectors.toSet());
        if (!allowed.containsAll(selected)) {
            throw BusinessException.validation("selectedOptionIds", "INVALID_VALUE",
                "Phương án không thuộc câu hỏi trong đề.");
        }
        if (QuestionType.SINGLE.equals(question.getQuestionType()) && selected.size() > 1) {
            throw BusinessException.validation("selectedOptionIds", "INVALID_VALUE",
                "Câu một đáp án chỉ được chọn một phương án.");
        }
        return selected;
    }

    /** Đề DRAFT hoặc đã xóa không lộ với học viên; dùng chung mã không tìm thấy để tránh dò dữ liệu. */
    private Quiz visibleQuiz(Long quizId) {
        Quiz quiz = repository.require(Quiz.class, quizId);
        if (Boolean.TRUE.equals(quiz.getDeleted()) || QuizStatus.DRAFT.equals(quiz.getStatus())) {
            throw new BusinessException(AssessmentResponseCode.NOT_FOUND);
        }
        return quiz;
    }

    /** Lượt của người khác trả cùng mã không tìm thấy, không phân biệt với ID không tồn tại. */
    private QuizAttempt ownAttempt(QuizAttempt attempt) {
        if (!attempt.getUserId().equals(learning.actor().person().id())) {
            throw new BusinessException(AssessmentResponseCode.NOT_FOUND);
        }
        return attempt;
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }
}
