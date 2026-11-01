package vn.com.coreacademy.learning.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.constants.LearningCodes.SavedKind;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningNoteRequests.AddNote;
import vn.com.coreacademy.learning.dto.LearningNoteRequests.ToggleBookmark;
import vn.com.coreacademy.learning.dto.LearningNoteViews.BookmarkToggle;
import vn.com.coreacademy.learning.dto.LearningNoteViews.BookmarkView;
import vn.com.coreacademy.learning.dto.LearningNoteViews.NoteView;
import vn.com.coreacademy.learning.dto.LearningNoteViews.SavedPage;
import vn.com.coreacademy.learning.service.LearningNotesService;

@RestController
@RequestMapping(value = "/api/v1/me/learning", produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "LearningNotesController")
@StandardApiErrors
@Validated
@RequiredArgsConstructor
public class LearningNotesController {
    private final LearningNotesService notes;

    @GetMapping("/lessons/{lessonId}/notes")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Ghi chú của tôi trên bài học",
        description = """
            Ghi chú còn sống của người gọi trên bài học, mới nhất trước.
            - Chỉ học viên (LEARNER, OWN); vai trò khác nhận 403.
            - Bài học phải thuộc phiên bản của một lần học của người gọi, nếu không là LEARNING-NOT_FOUND.
            """
    )
    public BaseResponse<List<NoteView>> list(@PathVariable @Positive Long lessonId) {
        return BaseResponse.ok(notes.notes(lessonId));
    }

    @PostMapping("/lessons/{lessonId}/notes")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Thêm ghi chú",
        description = """
            Thêm ghi chú riêng gắn với cả bài, một trang PDF hoặc một giây video.
            - anchor.type=none không có khối; pdf cần blockId và page; video cần blockId và second (giây nguyên).
            - Khối không thuộc bài, sai loại hoặc vị trí ngoài tài nguyên (trang 1..page_count, giây 0..duration)
              là LEARNING-INVALID_REFERENCE.
            - text sau khi cắt khoảng trắng dài 1 đến 4000 ký tự, nếu không là CORE-VALIDATION_FAILED.
            - Tối đa 500 ghi chú còn sống mỗi bài, vượt là LEARNING-NOTE_LIMIT.
            """
    )
    public BaseResponse<NoteView> add(@PathVariable @Positive Long lessonId, @Valid @RequestBody AddNote request) {
        return BaseResponse.ok(notes.addNote(lessonId, request));
    }

    @DeleteMapping("/notes/{noteId}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xóa ghi chú",
        description = """
            Xóa mềm ghi chú của người gọi.
            - Ghi chú của người khác, không tồn tại hoặc đã xóa đều là LEARNING-NOT_FOUND.
            """
    )
    public BaseResponse<Void> delete(@PathVariable @Positive Long noteId) {
        notes.deleteNote(noteId);
        return BaseResponse.ok();
    }

    @GetMapping("/lessons/{lessonId}/bookmarks")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Đánh dấu của tôi trên bài học",
        description = """
            Đánh dấu còn sống của người gọi trên bài học, mới nhất trước.
            - Chỉ học viên (LEARNER, OWN); vai trò khác nhận 403.
            - Bài học phải thuộc phiên bản của một lần học của người gọi, nếu không là LEARNING-NOT_FOUND.
            """
    )
    public BaseResponse<List<BookmarkView>> bookmarks(@PathVariable @Positive Long lessonId) {
        return BaseResponse.ok(notes.bookmarks(lessonId));
    }

    @PutMapping("/lessons/{lessonId}/bookmarks")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Bật hoặc tắt đánh dấu",
        description = """
            Đánh dấu một trang PDF hoặc một giây video; gọi lại cùng khối và vị trí thì bỏ đánh dấu.
            - anchor.type chỉ nhận pdf hoặc video; luật khối và vị trí như khi thêm ghi chú.
            - saved=true kèm bookmark vừa tạo; saved=false khi đánh dấu cũ bị bỏ (bookmark là null).
            - label tối đa 200 ký tự; bỏ trống thì backend đặt "{tên khối} — trang n" hoặc "{tên khối} — m:ss".
            - Tối đa 500 đánh dấu còn sống mỗi bài, vượt là LEARNING-NOTE_LIMIT.
            """
    )
    public BaseResponse<BookmarkToggle> toggle(@PathVariable @Positive Long lessonId,
                                               @Valid @RequestBody ToggleBookmark request) {
        return BaseResponse.ok(notes.toggleBookmark(lessonId, request));
    }

    @DeleteMapping("/bookmarks/{bookmarkId}")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Xóa đánh dấu",
        description = """
            Xóa mềm đánh dấu của người gọi theo ID, dùng cho trang Đã lưu.
            - Đánh dấu của người khác, không tồn tại hoặc đã xóa đều là LEARNING-NOT_FOUND.
            """
    )
    public BaseResponse<Void> deleteBookmark(@PathVariable @Positive Long bookmarkId) {
        notes.deleteBookmark(bookmarkId);
        return BaseResponse.ok();
    }

    @GetMapping("/saved")
    @ApiResponse(responseCode = "200",
        description = "Kết quả xử lý; kiểm tra status=SUCCESS hoặc ERROR.", useReturnTypeSchema = true)
    @Operation(
        summary = "Đã lưu và ghi chú",
        description = """
            Ghi chú hoặc đánh dấu còn sống của người gọi trên mọi bài học, mới nhất trước, kèm tên khóa và bài.
            - kind=notes (mặc định) hoặc bookmarks chọn tab; mỗi dòng có kind là note hoặc bookmark.
            - counts luôn có số của cả hai tab. Dòng của ghi chú có text, của đánh dấu có label.
            - Chỉ học viên (LEARNER, OWN); chỉ dữ liệu của chính người gọi.
            """
    )
    public BaseResponse<SavedPage> saved(
        @Parameter(description = "notes | bookmarks")
        @RequestParam(defaultValue = SavedKind.NOTES) @Pattern(regexp = SavedKind.PATTERN) String kind,
        @RequestParam(defaultValue = "0") @Min(0) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(LearningLimits.MAX_SAVED_PAGE_SIZE) int size) {
        return BaseResponse.ok(notes.saved(kind, page, size));
    }
}
