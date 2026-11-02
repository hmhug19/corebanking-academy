package vn.com.coreacademy.learning.service;

import java.util.List;
import java.util.function.Function;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.com.coreacademy.common.dto.PageResponse;
import vn.com.coreacademy.common.exception.BusinessException;
import vn.com.coreacademy.common.validation.TextValues;
import vn.com.coreacademy.learning.constants.LearningCodes.AnchorType;
import vn.com.coreacademy.learning.constants.LearningCodes.SavedKind;
import vn.com.coreacademy.learning.constants.LearningLimits;
import vn.com.coreacademy.learning.dto.LearningNoteRequests.AddNote;
import vn.com.coreacademy.learning.dto.LearningNoteRequests.ToggleBookmark;
import vn.com.coreacademy.learning.dto.LearningNoteViews.Anchor;
import vn.com.coreacademy.learning.dto.LearningNoteViews.BookmarkToggle;
import vn.com.coreacademy.learning.dto.LearningNoteViews.BookmarkView;
import vn.com.coreacademy.learning.dto.LearningNoteViews.NoteView;
import vn.com.coreacademy.learning.dto.LearningNoteViews.SavedCounts;
import vn.com.coreacademy.learning.dto.LearningNoteViews.SavedItemView;
import vn.com.coreacademy.learning.dto.LearningNoteViews.SavedPage;
import vn.com.coreacademy.learning.entity.LessonBookmark;
import vn.com.coreacademy.learning.entity.LessonNote;
import vn.com.coreacademy.learning.exception.LearningResponseCode;
import vn.com.coreacademy.learning.mapper.LessonNoteMapper;
import vn.com.coreacademy.learning.repository.LessonAnchorRepository.BlockBounds;
import vn.com.coreacademy.learning.repository.LessonBookmarkRepository;
import vn.com.coreacademy.learning.repository.LessonNoteRepository;
import vn.com.coreacademy.learning.repository.LessonSavedRepository;
import vn.com.coreacademy.learning.repository.LessonSavedRepository.Counts;
import vn.com.coreacademy.learning.repository.LessonSavedRepository.SavedRow;
import vn.com.coreacademy.learning.service.LessonAnchors.Resolved;

/**
 * Ghi chú và đánh dấu riêng của học viên trên bài học. Mọi thao tác chỉ chạm dữ liệu của chính người gọi; ghi chú
 * của người khác, đã xóa hoặc không tồn tại đều trả LEARNING-NOT_FOUND để không lộ sự tồn tại. Vị trí neo luôn được
 * kiểm trên tài nguyên thật của khối, không tin giá trị client gửi.
 */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class LearningNotesService {
    private final LessonAnchors lessonAnchors;
    private final LessonNoteRepository notes;
    private final LessonBookmarkRepository bookmarks;
    private final LessonSavedRepository saved;
    private final LessonNoteMapper mapper;

    /** Ghi chú còn sống của người gọi trên bài học, mới nhất trước. */
    public List<NoteView> notes(Long lessonId) {
        String userId = lessonAnchors.requireLessonAccess(lessonId).userId();
        return notes.findByUserIdAndLessonIdAndIsDeletedFalseOrderByCreateTimeDescIdDesc(userId, lessonId).stream()
            .map(mapper::view).toList();
    }

    /**
     * Thêm ghi chú. Thứ tự kiểm: quyền vào bài, nội dung, vị trí neo, rồi giới hạn dưới khóa advisory của người dùng
     * để hai request đồng thời không cùng vượt giới hạn.
     */
    @Transactional
    public NoteView addNote(Long lessonId, AddNote request) {
        String userId = lessonAnchors.requireLessonAccess(lessonId).userId();
        String text = noteText(request.text());
        Resolved resolved = lessonAnchors.resolve(lessonId, request.anchor(), true);

        notes.lock("lesson-note:" + userId);
        if (notes.countByUserIdAndLessonIdAndIsDeletedFalse(userId, lessonId) >= LearningLimits.MAX_NOTES_PER_LESSON) {
            throw new BusinessException(LearningResponseCode.NOTE_LIMIT);
        }

        Anchor anchor = resolved.anchor();
        LessonNote note = new LessonNote();
        note.setUserId(userId);
        note.setLessonId(lessonId);
        note.setBlockId(anchor.blockId());
        note.setAnchorType(anchor.type());
        note.setPdfPage(anchor.page());
        note.setVideoSecond(anchor.second());
        note.setBody(text);
        note.setCreatedBy(userId);
        note.setUpdatedBy(userId);
        return mapper.view(notes.saveAndFlush(note));
    }

    /** Xóa mềm. Ghi chú của người khác, không có hoặc đã xóa đều là LEARNING-NOT_FOUND. */
    @Transactional
    public void deleteNote(Long noteId) {
        String userId = lessonAnchors.learnerId();
        LessonNote note = notes.findByIdAndUserIdAndIsDeletedFalse(noteId, userId)
            .orElseThrow(() -> new BusinessException(LearningResponseCode.NOT_FOUND));
        softDelete(note, userId);
    }

    /** Đánh dấu còn sống của người gọi trên bài học, mới nhất trước. */
    public List<BookmarkView> bookmarks(Long lessonId) {
        String userId = lessonAnchors.requireLessonAccess(lessonId).userId();
        return bookmarks.findByUserIdAndLessonIdAndIsDeletedFalseOrderByCreateTimeDescIdDesc(userId, lessonId)
            .stream().map(mapper::view).toList();
    }

    /**
     * Bật hoặc tắt đánh dấu: nếu đã có đánh dấu sống cùng khối và vị trí thì xóa mềm nó, nếu chưa thì thêm mới. Bảng
     * không có UNIQUE nên "một đánh dấu sống mỗi người, khối và vị trí" do khóa advisory của người dùng bảo đảm: hai
     * lần bật đồng thời được xếp hàng, lần sau thấy kết quả của lần trước. Bỏ đánh dấu không bị giới hạn chặn.
     */
    @Transactional
    public BookmarkToggle toggleBookmark(Long lessonId, ToggleBookmark request) {
        String userId = lessonAnchors.requireLessonAccess(lessonId).userId();
        Resolved resolved = lessonAnchors.resolve(lessonId, request.anchor(), false);
        Anchor anchor = resolved.anchor();

        notes.lock("lesson-bookmark:" + userId);
        List<LessonBookmark> live = bookmarks.findLive(userId, lessonId, anchor.blockId(), anchor.type(),
            anchor.page(), anchor.second());
        if (!live.isEmpty()) {
            live.forEach(found -> softDelete(found, userId));
            return new BookmarkToggle(false, null);
        }
        if (bookmarks.countByUserIdAndLessonIdAndIsDeletedFalse(userId, lessonId)
            >= LearningLimits.MAX_BOOKMARKS_PER_LESSON) {
            throw new BusinessException(LearningResponseCode.NOTE_LIMIT);
        }

        LessonBookmark bookmark = new LessonBookmark();
        bookmark.setUserId(userId);
        bookmark.setLessonId(lessonId);
        bookmark.setBlockId(anchor.blockId());
        bookmark.setAnchorType(anchor.type());
        bookmark.setPdfPage(anchor.page());
        bookmark.setVideoSecond(anchor.second());
        bookmark.setLabel(label(request.label(), resolved.block(), anchor));
        bookmark.setCreatedBy(userId);
        bookmark.setUpdatedBy(userId);
        return new BookmarkToggle(true, mapper.view(bookmarks.saveAndFlush(bookmark)));
    }

    /** Bỏ đánh dấu theo ID, cùng luật với xóa ghi chú: của người khác, không có hoặc đã xóa đều là NOT_FOUND. */
    @Transactional
    public void deleteBookmark(Long bookmarkId) {
        String userId = lessonAnchors.learnerId();
        LessonBookmark bookmark = bookmarks.findByIdAndUserIdAndIsDeletedFalse(bookmarkId, userId)
            .orElseThrow(() -> new BusinessException(LearningResponseCode.NOT_FOUND));
        softDelete(bookmark, userId);
    }

    /**
     * Trang Đã lưu: ghi chú hoặc đánh dấu còn sống của người gọi trên mọi bài, mới nhất trước, kèm tên khóa và bài.
     * Không cần quyền vào bài: dữ liệu luôn của chính người gọi, nên mục của bài đã mất quyền vẫn xem và xóa được.
     * counts luôn đủ cả hai tab bất kể tab đang xem.
     */
    public SavedPage saved(String kind, int page, int size) {
        String userId = lessonAnchors.learnerId();
        Counts counts = saved.counts(userId);
        boolean notesTab = SavedKind.NOTES.equals(kind);
        long total = notesTab ? counts.notes() : counts.bookmarks();
        long offset = (long) page * size;
        List<SavedRow> rows = offset >= total ? List.of()
            : notesTab ? saved.notes(userId, (int) offset, size) : saved.bookmarks(userId, (int) offset, size);
        Function<SavedRow, SavedItemView> view = row -> new SavedItemView(
            notesTab ? SavedKind.NOTE_ITEM : SavedKind.BOOKMARK_ITEM, row.id(), row.lessonId(),
            new Anchor(row.anchorType(), row.blockId(), row.pdfPage(), row.videoSecond()), row.text(), row.label(),
            row.createTime(), row.courseId(), row.courseTitle(), row.lessonTitle());
        return new SavedPage(PageResponse.from(new PageImpl<>(rows.stream().map(view).toList(),
            PageRequest.of(page, size), total)), new SavedCounts(counts.notes(), counts.bookmarks()));
    }

    private static void softDelete(LessonNote note, String userId) {
        note.setIsDeleted(true);
        note.setUpdatedBy(userId);
    }

    private static void softDelete(LessonBookmark bookmark, String userId) {
        bookmark.setIsDeleted(true);
        bookmark.setUpdatedBy(userId);
    }

    /**
     * Nhãn người dùng gửi sau khi cắt khoảng trắng; để trống thì đặt nhãn mặc định "{tên khối} — trang n" hoặc
     * "{tên khối} — m:ss" để danh sách đánh dấu luôn đọc được mà giao diện không phải tự dựng nhãn.
     */
    private static String label(String requested, BlockBounds block, Anchor anchor) {
        if (requested != null && !requested.isBlank()) {
            return requested.trim();
        }
        String position = AnchorType.PDF.equals(anchor.type()) ? "trang " + anchor.page()
            : "%d:%02d".formatted(anchor.second() / 60, anchor.second() % 60);
        String label = block.title() + " — " + position;
        return label.length() <= LearningLimits.MAX_BOOKMARK_LABEL_LENGTH ? label
            : block.title().substring(0, LearningLimits.MAX_BOOKMARK_LABEL_LENGTH - position.length() - 4) + "… — "
                + position;
    }

    private static String noteText(String raw) {
        String text = TextValues.required(raw, "text");
        if (text.length() > LearningLimits.MAX_NOTE_LENGTH) {
            throw BusinessException.validation("text", "INVALID_LENGTH",
                "Ghi chú tối đa %d ký tự.".formatted(LearningLimits.MAX_NOTE_LENGTH));
        }
        return text;
    }
}
