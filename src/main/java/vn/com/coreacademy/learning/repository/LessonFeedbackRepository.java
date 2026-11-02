package vn.com.coreacademy.learning.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.learning.entity.LessonFeedback;

public interface LessonFeedbackRepository extends JpaRepository<LessonFeedback, Long> {
    /** Đánh giá sống của người dùng trên bài học; rỗng nếu chưa đánh giá. */
    Optional<LessonFeedback> findFirstByUserIdAndLessonIdAndKindAndIsDeletedFalseOrderByIdDesc(String userId,
                                                                                              Long lessonId,
                                                                                              String kind);

    /**
     * Khóa giao dịch theo khóa nghiệp vụ: bảng không có UNIQUE, nên "một đánh giá sống mỗi người và bài" và giới hạn
     * gửi mỗi ngày chỉ chính xác khi các lần ghi đồng thời của một người được xếp hàng.
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    int lock(@Param("key") String key);
}
