package vn.com.coreacademy.knowledge.dto;

import jakarta.validation.constraints.NotNull;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Câu hỏi một lượt; mỗi câu hỏi được trả lời độc lập, backend không giữ hội thoại.")
public record ChatRequest(
    @Schema(description = "Từ 1 đến 300 ký tự sau khi bỏ khoảng trắng hai đầu.", example = "CIF là gì?")
    @NotNull String question) {
}
