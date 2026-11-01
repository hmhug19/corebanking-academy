package vn.com.coreacademy.learning.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;

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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vn.com.coreacademy.common.dto.BaseResponse;
import vn.com.coreacademy.common.web.StandardApiErrors;
import vn.com.coreacademy.learning.dto.LearningNoteRequests.AddNote;
import vn.com.coreacademy.learning.dto.LearningNoteViews.NoteView;
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
}
