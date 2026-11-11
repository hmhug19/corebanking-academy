package vn.com.coreacademy.assessment.controller;

import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assessment.dto.CourseExamRequest;
import vn.com.coreacademy.assessment.service.CourseExamService;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.dto.LearningDetails;

@RestController
@RequestMapping(value = "/api/v1/course-exams", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "CourseExamController")
@StandardApiErrors
@RequiredArgsConstructor
public class CourseExamController {
    private final CourseExamService exams;

    @PostMapping
    @Operation(summary = "Lưu khóa học kèm bài thi cuối khóa và bộ câu hỏi",
        description = "Một giao dịch; kiểm quyền quản lý khóa, phiên bản nháp/đề và chương trình của mọi câu hỏi.")
    public BaseResponse<LearningDetails.CourseDetail> save(@Valid @RequestBody CourseExamRequest request) {
        return BaseResponse.ok(exams.save(request));
    }
}
