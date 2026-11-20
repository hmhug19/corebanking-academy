package vn.com.coreacademy.learning.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Tài liệu của khối Mô phỏng luồng: các Đối tượng đặt trên sàn lưới và các Bước chuyển theo thứ tự phát. Không có
 * thực thể liên kết; đường nối giữa hai Đối tượng được suy ra từ các bước. Quy tắc: xem {@code FlowModelRules}.
 */
@Schema(description = "Mô phỏng luồng: đối tượng trên sàn lưới và bước chuyển theo thứ tự phát (xem docs/api/LEARNING.md).")
public record FlowModel(Integer version, List<FlowObject> objects, List<FlowStep> steps) {

    /** Một Đối tượng: id do client sinh, tên, loại cố định, ô lưới x/z. */
    public record FlowObject(String id, String name, String kind, Integer x, Integer z) {
    }

    /** Một Bước chuyển: từ Đối tượng {@code from} tới {@code to}; {@code to} null = tự xử lý. */
    public record FlowStep(String id, String from, String to, String title, String description) {
    }
}
