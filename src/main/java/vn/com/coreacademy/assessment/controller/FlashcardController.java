package vn.com.coreacademy.assessment.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.assessment.repository.FlashcardRepository.Answer;
import vn.com.coreacademy.assessment.repository.FlashcardRepository.ManagedCard;
import vn.com.coreacademy.assessment.repository.FlashcardRepository.StudyCard;
import vn.com.coreacademy.assessment.service.FlashcardService;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;

@RestController
@Validated
@StandardApiErrors
@Tag(name = "Flashcard ôn tập")
@RequiredArgsConstructor
public class FlashcardController {
    private final FlashcardService flashcards;

    @GetMapping("/api/v1/courses/{courseId}/flashcards")
    public BaseResponse<List<ManagedCard>> managed(@PathVariable @Positive long courseId) {
        return BaseResponse.ok(flashcards.managed(courseId));
    }

    @PutMapping("/api/v1/courses/{courseId}/flashcards/{questionId}")
    public BaseResponse<List<ManagedCard>> change(@PathVariable @Positive long courseId,
        @PathVariable @Positive long questionId, @Valid @RequestBody Publication request) {
        return BaseResponse.ok(flashcards.change(courseId, questionId, request.status(), request.questionVersion()));
    }

    @GetMapping("/api/v1/me/courses/{courseId}/flashcards")
    public BaseResponse<List<StudyCard>> study(@PathVariable @Positive long courseId) {
        return BaseResponse.ok(flashcards.study(courseId));
    }

    @GetMapping("/api/v1/me/courses/{courseId}/flashcards/{cardId}/answer")
    public BaseResponse<Answer> reveal(@PathVariable @Positive long courseId, @PathVariable @Positive long cardId,
        @RequestParam @PositiveOrZero long questionVersion) {
        return BaseResponse.ok(flashcards.reveal(courseId, cardId, questionVersion));
    }

    @PatchMapping("/api/v1/me/courses/{courseId}/flashcards/{cardId}/progress")
    public BaseResponse<Boolean> progress(@PathVariable @Positive long courseId, @PathVariable @Positive long cardId,
        @Valid @RequestBody Progress request) {
        flashcards.save(courseId, cardId, request.questionVersion(), request.rating(), request.starred());
        return BaseResponse.ok(true);
    }

    public record Publication(@NotNull @Pattern(regexp = "DRAFT|PUBLISHED|WITHDRAWN") String status,
                              @PositiveOrZero Long questionVersion) {
    }

    public record Progress(@NotNull @PositiveOrZero Long questionVersion,
                           @Pattern(regexp = "AGAIN|KNOWN") String rating, Boolean starred) {
    }
}
