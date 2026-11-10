package vn.com.coreacademy.knowledge.controller;

import java.util.List;

import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import vn.com.coreacademy.assistant.agent.AssistantGate;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.knowledge.dto.KnowledgeViews.KnowledgeDocumentView;
import vn.com.coreacademy.knowledge.service.KnowledgeDocumentService;

@RestController
@RequestMapping("/api/v1/knowledge")
@Tag(name = "Knowledge documents")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class KnowledgeDocumentController {
    private final AssistantGate gate;
    private final KnowledgeDocumentService documents;

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Tải tài liệu lên kho tri thức",
        description = """
            Nhận .pdf, .md hoặc .docx tối đa theo app.knowledge.max-file-size, trả ngay với status queued.
            - Mọi người dùng đã đăng nhập đều được tải lên; kho dùng chung cho mọi vai trò.
            - Loại tệp được xác định từ phần mở rộng và nội dung thật, không tin MIME do trình duyệt khai báo.
            - Xử lý nền (trích xuất, chia đoạn, nhúng vector); theo dõi bằng GET /documents/{documentId}.
            """
    )
    public BaseResponse<KnowledgeDocumentView> upload(@RequestPart MultipartFile file) {
        gate.requireAccess();
        return BaseResponse.ok(documents.upload(file));
    }

    @GetMapping(value = "/documents", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Danh sách tài liệu",
        description = """
            Toàn bộ kho dùng chung, mới nhất trước, phân trang tại database.
            - mine=true chỉ lấy tài liệu do người gọi tải lên (dùng để theo dõi tiến độ xử lý).
            - status lặp lại được (status=queued&status=processing); bỏ trống để lấy mọi trạng thái.
            """
    )
    public BaseResponse<PageResponse<KnowledgeDocumentView>> list(
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(defaultValue = "false") boolean mine,
        @Parameter(description = "queued, processing, ready hoặc failed") @RequestParam(required = false)
        List<String> status) {
        gate.requireAccess();
        return BaseResponse.ok(documents.list(page, size, mine, status));
    }

    @GetMapping(value = "/documents/{documentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Chi tiết tài liệu",
        description = """
            Trả trạng thái xử lý hiện tại của một tài liệu trong kho dùng chung.
            """
    )
    public BaseResponse<KnowledgeDocumentView> get(@PathVariable @Positive Long documentId) {
        gate.requireAccess();
        return BaseResponse.ok(documents.get(documentId));
    }
}
