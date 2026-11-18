package vn.com.coreacademy.assessment.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.QuizType;
import vn.com.coreacademy.assessment.constants.AssessmentCodes.SelectionMode;
import vn.com.coreacademy.assessment.dto.AssessmentRequests;
import vn.com.coreacademy.assessment.dto.CourseExamRequest;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.learning.dto.LearningDetails;
import vn.com.coreacademy.learning.service.LearningContentService;

/** Điều phối hai module qua service công khai; lỗi cấu hình/pool không để lại khóa hoặc đề dở dang. */
@Service
@RequiredArgsConstructor
public class CourseExamService {
    private final LearningContentService courses;
    private final QuizService quizzes;

    @Transactional
    public LearningDetails.CourseDetail save(CourseExamRequest request) {
        boolean creating = request.courseId() == null;
        if (creating ? request.course() == null || request.draft() != null || request.revisionId() != null
            || request.quizId() != null : request.course() != null || request.draft() == null
            || request.revisionId() == null) {
            throw BusinessException.validation("course", "INVALID_VALUE",
                "Gửi thông tin tạo khóa mới hoặc định danh và nội dung bản nháp hiện hữu.");
        }
        var target = request.lessonTarget();
        String expectedType = target == null ? QuizType.FINAL_EXAM : QuizType.PRACTICE;
        if (!expectedType.equals(request.quiz().quizType()) || target != null && request.quizId() != null) {
            throw BusinessException.validation("quiz.quizType", "INVALID_VALUE",
                "Chọn đúng loại bài kiểm tra cho bài học hoặc cho cuối khóa.");
        }
        if (request.quizId() != null && request.expectedQuizVersion() == null) {
            throw BusinessException.validation("expectedQuizVersion", "REQUIRED", "Thiếu phiên bản bài kiểm tra.");
        }
        var saved = creating ? courses.create(request.course())
            : courses.saveDraft(request.courseId(), request.revisionId(), request.draft());
        Long courseId = saved.course().id();
        var config = withCourse(request.quiz(), courseId);
        var quiz = request.quizId() == null ? quizzes.create(config)
            : quizzes.update(request.quizId(), request.expectedQuizVersion(), config);
        var pooled = quizzes.replacePool(quiz.quiz().id(), quiz.quiz().rowVersion(), request.pool());
        if (pooled.activePoolSize() < config.questionCount()
            || SelectionMode.FIXED.equals(config.selectionMode())
                && request.pool().items().size() != config.questionCount()) {
            throw BusinessException.validation("pool", "INVALID_VALUE",
                "Chọn đủ câu hỏi đang sử dụng cho bài kiểm tra; đề cố định phải có đúng số câu đã chọn.");
        }
        if (target != null) {
            return courses.attachLessonQuiz(saved, target.chapter(), target.lesson(), pooled.quiz().id());
        }
        return courses.attachFinalExam(courseId, saved.revision().id(), saved.revision().rowVersion(),
            pooled.quiz().id());
    }

    private AssessmentRequests.QuizRequest withCourse(AssessmentRequests.QuizRequest request, Long courseId) {
        if (request.courseId() != null && !courseId.equals(request.courseId())) {
            throw BusinessException.validation("quiz.courseId", "INVALID_VALUE", "Bài thi phải thuộc đúng khóa học.");
        }
        return new AssessmentRequests.QuizRequest(courseId, request.title(), request.description(), request.quizType(),
            request.selectionMode(), request.questionCount(), request.shuffleQuestions(), request.shuffleOptions(),
            request.timeLimitMinutes(), request.openAt(), request.closeAt(), request.graceSeconds(),
            request.passPercent(), request.scoringMode(), request.scoreMethod(), request.reviewPolicy(),
            request.maxAttempts(), request.retakeWaitHours(), request.allowRetakeAfterPass(),
            request.requireLessonsCompleted());
    }
}
