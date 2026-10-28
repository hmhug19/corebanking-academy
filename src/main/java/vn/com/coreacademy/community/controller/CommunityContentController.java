package vn.com.coreacademy.community.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import vn.com.coreacademy.community.dto.ContentRequests.AcceptRequest;
import vn.com.coreacademy.community.dto.ContentRequests.AccessRequest;
import vn.com.coreacademy.community.dto.ContentRequests.ContentVersion;
import vn.com.coreacademy.community.dto.ContentRequests.CreateComment;
import vn.com.coreacademy.community.dto.ContentRequests.CreateContent;
import vn.com.coreacademy.community.dto.ContentRequests.CreateTag;
import vn.com.coreacademy.community.dto.ContentRequests.LifecycleRequest;
import vn.com.coreacademy.community.dto.ContentRequests.PublishRequest;
import vn.com.coreacademy.community.dto.ContentRequests.SaveDraft;
import vn.com.coreacademy.community.dto.ContentRequests.TagsRequest;
import vn.com.coreacademy.community.dto.ContentRequests.UpdateComment;
import vn.com.coreacademy.community.dto.ContentRequests.UpdateTag;
import vn.com.coreacademy.community.dto.ContentRequests.VoteRequest;
import vn.com.coreacademy.community.dto.ContentViews.AccessView;
import vn.com.coreacademy.community.dto.ContentViews.CommentView;
import vn.com.coreacademy.community.dto.ContentViews.ContentCard;
import vn.com.coreacademy.community.dto.ContentViews.ContentDetail;
import vn.com.coreacademy.community.dto.ContentViews.RevisionDetail;
import vn.com.coreacademy.community.dto.ContentViews.TagView;
import vn.com.coreacademy.community.dto.ContentViews.VoteView;
import vn.com.coreacademy.community.repository.ContentRepository;
import vn.com.coreacademy.community.service.CommunityCommentService;
import vn.com.coreacademy.community.service.CommunityContentService;
import vn.com.coreacademy.community.service.CommunityLifecycleService;
import vn.com.coreacademy.community.service.CommunityTagService;

@RestController
@RequestMapping(value = "/api/v1/community", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Community content")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class CommunityContentController {
    private final CommunityContentService contents;
    private final CommunityTagService tags;
    private final CommunityCommentService comments;
    private final CommunityLifecycleService lifecycle;

    @PostMapping("/contents/{contentId}/lifecycle")
    @ApiResponse(responseCode = "200", description = "Kết quả vòng đời hoặc tombstone khi xóa.",
        useReturnTypeSchema = true)
    @Operation(summary = "Thay đổi vòng đời nội dung", description = """
        Tác giả hoặc maintainer đóng, mở lại, lưu trữ hoặc xóa mềm theo version hiện tại.
        - Question có answer xuất bản của người khác không được tự xóa.
        """)
    public BaseResponse<ContentDetail> lifecycle(@PathVariable @Positive Long contentId,
        @Valid @RequestBody LifecycleRequest request) {
        return BaseResponse.ok(lifecycle.change(contentId, request));
    }

    @GetMapping("/questions")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Danh sách câu hỏi", description = """
        Lọc quyền trước phân trang; chỉ hiển thị nội dung đã xuất bản.
        """)
    public BaseResponse<PageResponse<ContentCard>> questions(@RequestParam(required = false) String q,
        @RequestParam(required = false) List<Long> tagIds, @RequestParam(required = false) String resolutionStatus,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String sort) {
        return BaseResponse.ok(contents.list(new ContentRepository.Search("question", q, tagIds, resolutionStatus, null,
            false, null, null, page, size, sort)));
    }

    @PostMapping("/questions")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Tạo câu hỏi", description = """
        Lưu bản nháp; xuất bản bằng API publish sau khi kiểm tra nội dung.
        """)
    public BaseResponse<ContentDetail> createQuestions(@Valid @RequestBody CreateContent request) {
        return BaseResponse.ok(contents.create("question", null, request));
    }

    @GetMapping("/articles")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Danh sách bài viết", description = """
        Lọc quyền trước phân trang; chỉ hiển thị nội dung đã xuất bản.
        """)
    public BaseResponse<PageResponse<ContentCard>> articles(@RequestParam(required = false) String q,
        @RequestParam(required = false) List<Long> tagIds, @RequestParam(required = false) String resolutionStatus,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size,
        @RequestParam(required = false) String sort) {
        return BaseResponse.ok(contents.list(new ContentRepository.Search("article", q, tagIds, resolutionStatus, null,
            false, null, null, page, size, sort)));
    }

    @PostMapping("/articles")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Tạo bài viết", description = """
        Lưu bản nháp; xuất bản bằng API publish sau khi kiểm tra nội dung.
        """)
    public BaseResponse<ContentDetail> createArticles(@Valid @RequestBody CreateContent request) {
        return BaseResponse.ok(contents.create("article", null, request));
    }

    @GetMapping("/questions/suggestions")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Tìm câu hỏi trước khi đăng", description = """
        Chỉ gợi ý câu hỏi đã xuất bản mà người gọi còn được đọc.
        """)
    public BaseResponse<List<ContentCard>> suggestions(@RequestParam @Size(min = 5, max = 300) String q,
        @RequestParam(defaultValue = "5") @Min(1) @Max(10) int limit) {
        return BaseResponse.ok(contents.list(new ContentRepository.Search("question", q, null, null, null,
            false, null, null, 0, limit, null)).items());
    }

    @GetMapping("/questions/{questionId}")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Chi tiết câu hỏi", description = """
        Đọc draft chỉ khi có quyền biên soạn; không mở ACL bằng query parameter.
        """)
    public BaseResponse<ContentDetail> question(@PathVariable @Positive Long questionId,
        @RequestParam(defaultValue = "false") boolean includeOwnDraft) {
        return BaseResponse.ok(contents.question(questionId, includeOwnDraft));
    }

    @GetMapping("/questions/{questionId}/answers")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Danh sách câu trả lời", description = """
        Sắp xếp câu được accept trước, sau đó theo điểm và thời điểm.
        """)
    public BaseResponse<PageResponse<ContentCard>> answers(@PathVariable @Positive Long questionId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(contents.list(new ContentRepository.Search("answer", null, null, null, questionId,
            false, null, null, page, size, null)));
    }

    @PostMapping("/questions/{questionId}/answers")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Viết câu trả lời", description = """
        Tạo draft kế thừa quyền câu hỏi; không làm tăng số answer đã xuất bản.
        """)
    public BaseResponse<ContentDetail> answer(@PathVariable @Positive Long questionId,
        @Valid @RequestBody CreateContent request) {
        return BaseResponse.ok(contents.create("answer", questionId, request));
    }

    @PutMapping("/questions/{questionId}/accepted-answer")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Xác nhận câu trả lời", description = """
        Chỉ người hỏi được xác nhận revision cụ thể hoặc bỏ xác nhận.
        """)
    public BaseResponse<ContentDetail> accept(@PathVariable @Positive Long questionId,
        @Valid @RequestBody AcceptRequest request) {
        return BaseResponse.ok(contents.accept(questionId, request));
    }

    @GetMapping("/contents/{contentId}")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Chi tiết nội dung", description = """
        Kiểm quyền hiện tại trước khi trả nội dung hoặc lịch sử.
        """)
    public BaseResponse<ContentDetail> detail(@PathVariable @Positive Long contentId,
        @RequestParam(defaultValue = "false") boolean includeOwnDraft) {
        return BaseResponse.ok(contents.detail(contentId, includeOwnDraft));
    }

    @GetMapping("/me/contents")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Nội dung của tôi", description = """
        Chỉ trả nội dung do người gọi tạo và còn quyền hiện tại.
        """)
    public BaseResponse<PageResponse<ContentCard>> mine(@RequestParam(required = false) String kind,
        @RequestParam(required = false) String publicationState,
        @RequestParam(required = false) String lifecycleStatus, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(contents.list(new ContentRepository.Search(kind, null, null, null, null, true,
            publicationState, lifecycleStatus, page, size, null)));
    }

    @PostMapping("/contents/{contentId}/draft")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Lấy hoặc tạo draft", description = """
        Trả draft hiện có để retry không sinh thêm revision.
        """)
    public BaseResponse<RevisionDetail> draft(@PathVariable @Positive Long contentId,
        @Valid @RequestBody ContentVersion request) {
        return BaseResponse.ok(contents.draft(contentId, request.expectedContentVersion()));
    }

    @PutMapping("/contents/{contentId}/draft")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Lưu draft", description = """
        Kiểm version content và revision trước khi thay toàn bộ payload của draft.
        """)
    public BaseResponse<RevisionDetail> saveDraft(@PathVariable @Positive Long contentId,
        @Valid @RequestBody SaveDraft request) {
        return BaseResponse.ok(contents.saveDraft(contentId, request));
    }

    @PostMapping("/contents/{contentId}/publish")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Xuất bản nội dung", description = """
        Giữ payload bản cũ bất biến; Wiki/Radar phải qua luồng duyệt riêng.
        """)
    public BaseResponse<ContentDetail> publish(@PathVariable @Positive Long contentId,
        @Valid @RequestBody PublishRequest request) {
        return BaseResponse.ok(contents.publish(contentId, request));
    }

    @GetMapping("/contents/{contentId}/revisions")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Lịch sử phiên bản", description = """
        Reader chỉ thấy các revision từng xuất bản và còn ACL.
        """)
    public BaseResponse<PageResponse<RevisionDetail>> history(@PathVariable @Positive Long contentId,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(contents.history(contentId, page, size));
    }

    @GetMapping("/contents/{contentId}/revisions/{revisionId}")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Chi tiết phiên bản", description = """
        Kiểm revision thuộc đúng nội dung và quyền đọc hiện tại.
        """)
    public BaseResponse<RevisionDetail> revision(@PathVariable @Positive Long contentId,
        @PathVariable @Positive Long revisionId) {
        return BaseResponse.ok(contents.revision(contentId, revisionId));
    }

    @PutMapping("/contents/{contentId}/access")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Thay phạm vi truy cập", description = """
        Chỉ tác giả; không cho grant chéo tổ chức hoặc đổi ACL answer độc lập.
        """)
    public BaseResponse<AccessView> access(@PathVariable @Positive Long contentId,
        @Valid @RequestBody AccessRequest request) {
        return BaseResponse.ok(contents.access(contentId, request));
    }

    @PutMapping("/contents/{contentId}/tags")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Thay tag nội dung", description = """
        Tất cả tag phải active trong cùng đơn vị; không gửi tag trùng.
        """)
    public BaseResponse<ContentDetail> tags(@PathVariable @Positive Long contentId,
        @Valid @RequestBody TagsRequest request) {
        return BaseResponse.ok(contents.tags(contentId, request));
    }

    @PutMapping("/contents/{contentId}/vote")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Bình chọn", description = """
        SET -1, 0 hoặc 1; gửi lại không cộng thêm điểm, không tự vote nội dung của mình.
        """)
    public BaseResponse<VoteView> vote(@PathVariable @Positive Long contentId,
        @Valid @RequestBody VoteRequest request) {
        return BaseResponse.ok(contents.vote(contentId, request.value()));
    }

    @GetMapping("/tags")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Danh mục tag", description = """
        Danh mục cùng đơn vị; không trả usage count của bài riêng tư.
        """)
    public BaseResponse<PageResponse<TagView>> tagCatalog(@RequestParam(required = false) String q,
        @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(tags.list(q, page, size));
    }

    @PostMapping("/tags")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Tạo tag", description = """
        Cần org moderator; slug và alias được kiểm trùng dưới khóa ghi.
        """)
    public BaseResponse<TagView> createTag(@Valid @RequestBody CreateTag request) {
        return BaseResponse.ok(tags.create(request));
    }

    @PutMapping("/tags/{tagId}")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Cập nhật tag", description = """
        Cần org moderator và version hiện tại; không sửa tag đã merge.
        """)
    public BaseResponse<TagView> updateTag(@PathVariable @Positive Long tagId, @Valid @RequestBody UpdateTag request) {
        return BaseResponse.ok(tags.update(tagId, request));
    }

    @GetMapping("/contents/{contentId}/comments")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Danh sách bình luận", description = """
        Bình luận ẩn hoặc xóa chỉ trả tombstone để giữ quan hệ reply.
        """)
    public BaseResponse<PageResponse<CommentView>> comments(@PathVariable @Positive Long contentId,
        @RequestParam(required = false) Long reviewThreadId, @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
        return BaseResponse.ok(comments.list(contentId, reviewThreadId, page, size));
    }

    @PostMapping("/contents/{contentId}/comments")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Viết bình luận", description = """
        Tối đa ba cấp reply; parent phải thuộc cùng nội dung, revision và review thread.
        """)
    public BaseResponse<CommentView> comment(@PathVariable @Positive Long contentId,
        @Valid @RequestBody CreateComment request) {
        return BaseResponse.ok(comments.create(contentId, request));
    }

    @PutMapping("/comments/{commentId}")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Sửa bình luận", description = """
        Chỉ tác giả khi nội dung cha còn cho tương tác.
        """)
    public BaseResponse<CommentView> updateComment(@PathVariable @Positive Long commentId,
        @Valid @RequestBody UpdateComment request) {
        return BaseResponse.ok(comments.update(commentId, request));
    }

    @DeleteMapping("/comments/{commentId}")
    @ApiResponse(responseCode = "200", description = "Kiểm tra status=SUCCESS hoặc ERROR.",
        useReturnTypeSchema = true)
    @Operation(summary = "Xóa bình luận", description = """
        Xóa mềm, giữ reply; chỉ tác giả khi nội dung cha còn cho tương tác.
        """)
    public BaseResponse<Void> deleteComment(@PathVariable @Positive Long commentId,
        @RequestParam @Min(0) Long expectedVersion) {
        comments.delete(commentId, expectedVersion);
        return BaseResponse.ok();
    }
}
