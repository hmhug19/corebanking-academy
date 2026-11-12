package vn.com.coreacademy.knowledge.controller;

import java.util.List;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.knowledge.dto.KnowledgeViews.DocumentTextView;
import vn.com.coreacademy.knowledge.dto.KnowledgeViews.KnowledgeDocumentView;
import vn.com.coreacademy.knowledge.entity.DocumentScope;
import vn.com.coreacademy.knowledge.service.KnowledgeDocumentService;
import vn.com.coreacademy.knowledge.service.KnowledgeDocumentTextService;

@RestController
@RequestMapping("/api/v1/knowledge")
@Tag(name = "Knowledge documents")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class KnowledgeDocumentController {
    private static final int MAX_SPAN = 20;

    private final AssistantGate gate;
    private final KnowledgeDocumentService documents;
    private final KnowledgeDocumentTextService texts;

    @PostMapping(value = "/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
        produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
        summary = "Tải tài liệu lên kho tri thức",
        description = """
            Nhận .pdf, .md hoặc .docx tối đa theo app.knowledge.max-file-size, trả ngay với status queued.
            - Mọi người dùng đã đăng nhập đều được tải lên.
            - scope: personal (chỉ mình tôi), unit (đơn vị của tôi và đơn vị con, mặc định) hoặc global (mọi người, chỉ ADMIN/SUPER_ADMIN; vai trò khác nhận 403).
            - Loại tệp được xác định từ phần mở rộng và nội dung thật, không tin MIME do trình duyệt khai báo.
            - Xử lý nền (trích xuất, chia đoạn, nhúng vector); theo dõi bằng GET /documents/{documentId}.
            """
    )
    public BaseResponse<KnowledgeDocumentView> upload(@RequestPart MultipartFile file,
                                                      @RequestParam(required = false) String scope) {
        gate.requireAccess();
        return BaseResponse.ok(documents.upload(file, parseScope(scope)));
    }

    @GetMapping(value = "/documents", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Danh sách tài liệu",
        description = """
            Các tài liệu người gọi được đọc (của mình, của đơn vị mình và toàn hệ thống), mới nhất trước, phân trang tại database.
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
            Trả trạng thái xử lý hiện tại của một tài liệu người gọi được đọc; tài liệu không đọc được trả not found.
            """
    )
    public BaseResponse<KnowledgeDocumentView> get(@PathVariable @Positive Long documentId) {
        gate.requireAccess();
        return BaseResponse.ok(documents.get(documentId));
    }

    @GetMapping(value = "/documents/{documentId}/text", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Đọc văn bản quanh đoạn được trích dẫn",
        description = """
            Trả văn bản đã trích của đoạn around và span đoạn kề mỗi bên, kèm đường dẫn mục, trang và dòng, để người dùng đọc ngữ cảnh của nguồn.
            - Cùng quy tắc nhìn thấy như tìm kiếm: tài liệu người gọi không tìm được (kể cả nguồn của khóa học) trả not found.
            - around bỏ trống thì bắt đầu từ đoạn đầu tiên; đoạn không thuộc tài liệu trả not found.
            - Không có endpoint nào trả tệp gốc.
            """
    )
    public BaseResponse<DocumentTextView> text(@PathVariable @Positive Long documentId,
                                               @RequestParam(required = false) @Positive Long around,
                                               @RequestParam(defaultValue = "2") @Min(0) @Max(MAX_SPAN) int span) {
        gate.requireAccess();
        return BaseResponse.ok(texts.text(documentId, around, span));
    }

    @DeleteMapping(value = "/documents/{documentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(
        summary = "Xóa tài liệu",
        description = """
            Xóa tệp, các đoạn và bản ghi để câu hỏi sau không còn trích dẫn tài liệu này.
            - Người tải lên luôn xóa được; ADMIN/SUPER_ADMIN xóa được tài liệu toàn hệ thống hoặc của đơn vị trong phạm vi của mình.
            - Tài liệu đang xử lý trả lỗi validation PROCESSING; tài liệu không đọc được trả not found; không đủ quyền trả 403.
            """
    )
    public BaseResponse<Void> delete(@PathVariable @Positive Long documentId) {
        gate.requireAccess();
        documents.delete(documentId);
        return BaseResponse.ok();
    }

    private static DocumentScope parseScope(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return DocumentScope.fromRequest(value);
        } catch (IllegalArgumentException exception) {
            throw BusinessException.validation("scope", "INVALID_VALUE", "Phạm vi phải là personal, unit hoặc global.");
        }
    }
}
