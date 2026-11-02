package vn.com.coreacademy.learning.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    /** Phản hồi riêng còn sống của người dùng trên bài học thuộc các loại đã cho, mới nhất trước. */
    List<LessonFeedback> findByUserIdAndLessonIdAndKindInAndIsDeletedFalseOrderByCreateTimeDescIdDesc(
        String userId, Long lessonId, Collection<String> kinds);

    /**
     * Số phản hồi người dùng đã gửi trên bài học thuộc các loại đã cho trong khoảng [from, to), tính cả dòng đã xóa
     * vì giới hạn là số lần gửi, không phải số dòng còn sống.
     */
    @Query("""
        select count(f) from LessonFeedback f
        where f.userId = :userId and f.lessonId = :lessonId and f.kind in :kinds
          and f.createTime >= :from and f.createTime < :to
        """)
    long countSent(@Param("userId") String userId, @Param("lessonId") Long lessonId,
                   @Param("kinds") Collection<String> kinds, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Khóa giao dịch theo khóa nghiệp vụ: bảng không có UNIQUE, nên "một đánh giá sống mỗi người và bài" và giới hạn
     * gửi mỗi ngày chỉ chính xác khi các lần ghi đồng thời của một người được xếp hàng.
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    int lock(@Param("key") String key);
}
