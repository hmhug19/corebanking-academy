package vn.com.coreacademy.learning.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.knowledge.service.KnowledgeReindexService;
import vn.com.coreacademy.knowledge.service.KnowledgeReindexService.Status;
import vn.com.coreacademy.learning.service.LearningAccessPolicy;
import vn.com.coreacademy.learning.service.XpAdjustmentService;

@RestController
@RequestMapping(value = "/api/v1/admin/knowledge/reindex", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "KnowledgeReindexAdminController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class KnowledgeReindexAdminController {
    private final KnowledgeReindexService reindex;
    private final LearningAccessPolicy access;

    @PostMapping
    @Operation(
        summary = "Lập chỉ mục lại tài liệu và khóa học",
        description = """
            Chạy nền: lập chỉ mục lại mọi tài liệu và revision khóa học có đoạn do bộ tách đoạn khác phiên bản
            (app.knowledge.chunk.version) hoặc do mô hình nhúng khác cấu hình (app.knowledge.ollama.embed-model) tạo ra.
            Mỗi mục được thay trong một transaction nên đoạn cũ vẫn trả lời cho đến khi đoạn mới có hiệu lực.
            Trả ngay trạng thái hiện tại (state RUNNING, total); đang chạy thì không bắt đầu lượt mới mà trả trạng thái
            lượt đó. Mục lỗi được đếm trong failed và vẫn lỗi thời cho lượt sau; mô hình không kết nối được thì
            lượt dừng với state ABORTED. Chỉ quản trị hệ thống (ADMIN hoặc SUPER_ADMIN, phạm vi GLOBAL); vai trò khác nhận 403.
            """
    )
    public BaseResponse<Status> start() {
        requireSystemAdmin();

        return BaseResponse.ok(reindex.start());
    }

    @GetMapping("/status")
    @Operation(
        summary = "Tiến độ lập chỉ mục lại",
        description = """
            Trạng thái lượt gần nhất kể từ lúc backend khởi động: state IDLE (chưa chạy), RUNNING, FINISHED hoặc
            ABORTED; total, done, failed là số mục; startedTime, finishedTime (null khi đang chạy).
            Chỉ quản trị hệ thống (phạm vi GLOBAL).
            """
    )
    public BaseResponse<Status> status() {
        requireSystemAdmin();

        return BaseResponse.ok(reindex.status());
    }

    private void requireSystemAdmin() {
        if (!XpAdjustmentService.isSystemAdmin(access.actor())) {
            throw new AccessDeniedException("System admin required");
        }
    }
}
