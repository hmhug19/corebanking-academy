package vn.com.coreacademy.learning.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.learning.entity.LessonNote;

public interface LessonNoteRepository extends JpaRepository<LessonNote, Long> {
    List<LessonNote> findByUserIdAndLessonIdAndIsDeletedFalseOrderByCreateTimeDescIdDesc(String userId,
                                                                                       Long lessonId);

    Optional<LessonNote> findByIdAndUserIdAndIsDeletedFalse(Long id, String userId);

    long countByUserIdAndLessonIdAndIsDeletedFalse(String userId, Long lessonId);

    /**
     * Khóa giao dịch theo khóa nghiệp vụ cho cả ghi chú và đánh dấu: bảng không có UNIQUE, nên đếm giới hạn hoặc tìm
     * đánh dấu trùng rồi mới ghi chỉ chính xác khi các lần ghi đồng thời của một người được xếp hàng.
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    int lock(@Param("key") String key);
}
