package vn.com.coreacademy.learning.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.learning.entity.LessonBookmark;

public interface LessonBookmarkRepository extends JpaRepository<LessonBookmark, Long> {
    List<LessonBookmark> findByUserIdAndLessonIdAndIsDeletedFalseOrderByCreateTimeDescIdDesc(String userId,
                                                                                           Long lessonId);

    Optional<LessonBookmark> findByIdAndUserIdAndIsDeletedFalse(Long id, String userId);

    long countByUserIdAndLessonIdAndIsDeletedFalse(String userId, Long lessonId);

    /** Đánh dấu sống cùng khối và vị trí; nhiều dòng chỉ xảy ra khi dữ liệu bị ghi ngoài service. */
    @Query("""
        select b from LessonBookmark b
        where b.userId = :userId and b.lessonId = :lessonId and b.blockId = :blockId
          and b.anchorType = :anchorType and b.isDeleted = false
          and b.pdfPage is not distinct from :pdfPage
          and b.videoSecond is not distinct from :videoSecond
        order by b.id
        """)
    List<LessonBookmark> findLive(@Param("userId") String userId, @Param("lessonId") Long lessonId,
                                  @Param("blockId") Long blockId, @Param("anchorType") String anchorType,
                                  @Param("pdfPage") Integer pdfPage, @Param("videoSecond") Integer videoSecond);
}
