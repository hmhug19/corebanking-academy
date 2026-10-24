package vn.com.coreacademy.assistant.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Một lượt hội thoại dạng văn bản.")
public record AgentMessage(
    @Schema(description = "Chỉ user hoặc assistant; vai trò khác bị từ chối.", example = "user") String role,
    @Schema(description = "Sau khi bỏ khoảng trắng hai đầu: user 1 đến 500 ký tự; assistant tối đa 4000 (dài hơn bị cắt).")
    String content) {
}
