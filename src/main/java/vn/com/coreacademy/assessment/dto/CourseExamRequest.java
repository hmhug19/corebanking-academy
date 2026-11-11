package vn.com.coreacademy.assessment.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import vn.com.coreacademy.learning.dto.LearningRequests;

/** Một lần ghi nguyên tử: tạo khóa hoặc lưu nháp hiện hữu, cấu hình đề và bộ câu hỏi cuối khóa. */
public record CourseExamRequest(
    @Positive Long courseId,
    @Positive Long revisionId,
    @Valid LearningRequests.CreateCourse course,
    @Valid LearningRequests.DraftRequest draft,
    @Positive Long quizId,
    @Min(0) Long expectedQuizVersion,
    @NotNull @Valid AssessmentRequests.QuizRequest quiz,
    @NotNull @Valid AssessmentRequests.PoolRequest pool) {
}
