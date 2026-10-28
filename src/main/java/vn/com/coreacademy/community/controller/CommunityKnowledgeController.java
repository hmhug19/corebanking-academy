package vn.com.coreacademy.community.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.DocumentRequests.CreateKnowledgeRequest;
import vn.com.coreacademy.community.dto.DocumentRequests.CreatePostmortem;
import vn.com.coreacademy.community.dto.KnowledgeDtos.HelpAction;
import vn.com.coreacademy.community.dto.KnowledgeDtos.HelpRequest;
import vn.com.coreacademy.community.dto.KnowledgeDtos.HelpRequestView;
import vn.com.coreacademy.community.dto.KnowledgeDtos.KnowledgeAction;
import vn.com.coreacademy.community.dto.KnowledgeDtos.KnowledgeRequestCard;
import vn.com.coreacademy.community.dto.KnowledgeDtos.KnowledgeRequestView;
import vn.com.coreacademy.community.service.CommunityKnowledgeService;

@RestController
@RequestMapping(value = "/api/v1/community", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
@StandardApiErrors
@RequiredArgsConstructor
@Tag(name = "Community knowledge", description = "Yêu cầu chia sẻ kiến thức và hỗ trợ chuyên môn")
public class CommunityKnowledgeController {
    private final CommunityKnowledgeService knowledge;

    @PostMapping("/questions/{contentId}/help-requests")
    @Operation(summary = "Mời chuyên gia hỗ trợ câu hỏi")
    @ApiResponse(responseCode = "200", description = "Lời mời hiện tại theo đối tượng.", useReturnTypeSchema = true)
    public BaseResponse<HelpRequestView> help(@PathVariable @Positive Long contentId,
        @Valid @RequestBody HelpRequest request) {
        return BaseResponse.ok(knowledge.help(contentId, request));
    }

    @PutMapping("/help-requests/{helpRequestId}")
    @Operation(summary = "Phản hồi lời mời hỗ trợ")
    @ApiResponse(responseCode = "200", description = "Lời mời sau kiểm tra actor/version.", useReturnTypeSchema = true)
    public BaseResponse<HelpRequestView> respond(@PathVariable @Positive Long helpRequestId,
        @Valid @RequestBody HelpAction request) {
        return BaseResponse.ok(knowledge.helpAction(helpRequestId, request));
    }

    @GetMapping("/knowledge-requests")
    @Operation(summary = "Tìm yêu cầu chia sẻ kiến thức")
    @ApiResponse(responseCode = "200", description = "Danh sách theo quyền hiện tại.", useReturnTypeSchema = true)
    public BaseResponse<PageResponse<KnowledgeRequestCard>> requests(@RequestParam(required = false) String q,
        @RequestParam(required = false) String status, @RequestParam(required = false) Long guildId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(knowledge.requests(q, status, guildId, page, size));
    }

    @PostMapping("/knowledge-requests")
    @Operation(summary = "Tạo yêu cầu chia sẻ kiến thức")
    @ApiResponse(responseCode = "200", description = "Yêu cầu nháp, retry theo clientRequestId.",
        useReturnTypeSchema = true)
    public BaseResponse<KnowledgeRequestView> create(@Valid @RequestBody CreateKnowledgeRequest request) {
        return BaseResponse.ok(knowledge.createRequest(request));
    }

    @PostMapping("/knowledge-requests/{contentId}/actions")
    @Operation(summary = "Nhận hoặc hoàn thành yêu cầu kiến thức")
    @ApiResponse(responseCode = "200", description = "Trạng thái và tài liệu đủ quyền đối tượng.",
        useReturnTypeSchema = true)
    public BaseResponse<KnowledgeRequestView> action(@PathVariable @Positive Long contentId,
        @Valid @RequestBody KnowledgeAction request) {
        return BaseResponse.ok(knowledge.action(contentId, request));
    }

    @GetMapping("/lessons-learned")
    @Operation(summary = "Tìm bài học kinh nghiệm")
    @ApiResponse(responseCode = "200", description = "Nội dung đã xuất bản còn quyền đọc.", useReturnTypeSchema = true)
    public BaseResponse<PageResponse<ContentCard>> lessons(@RequestParam(required = false) String q,
        @RequestParam(required = false) List<Long> tagIds, @RequestParam(required = false) Long guildId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(knowledge.lessons(q, tagIds, guildId, page, size));
    }

    @PostMapping("/lessons-learned")
    @Operation(summary = "Viết bài học kinh nghiệm")
    @ApiResponse(responseCode = "200", description = "Bản nháp bài học kinh nghiệm.", useReturnTypeSchema = true)
    public BaseResponse<ContentDetail> postmortem(@Valid @RequestBody CreatePostmortem request) {
        return BaseResponse.ok(knowledge.postmortem(request));
    }

    @GetMapping("/feed")
    @Operation(summary = "Xem bảng tin tri thức")
    @ApiResponse(responseCode = "200", description = "Bảng tin lọc ACL trước phân trang.", useReturnTypeSchema = true)
    public BaseResponse<PageResponse<ContentCard>> feed(@RequestParam(defaultValue = "false") boolean following,
        @RequestParam(required = false) Long guildId, @RequestParam(required = false) String kind,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(knowledge.feed(following, guildId, kind, page, size));
    }
}
