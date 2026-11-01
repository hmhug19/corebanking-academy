package vn.com.coreacademy.learning.repository;

import java.time.LocalDate;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import vn.com.coreacademy.learning.entity.LearningActivityDay;

public interface LearningActivityDayRepository extends JpaRepository<LearningActivityDay, Long> {
    boolean existsByUserIdAndActivityDate(String userId, LocalDate activityDate);

    /**
     * Khóa giao dịch theo người dùng để hai request đồng thời không cùng ghi ngày hôm nay: bảng không có UNIQUE, nên
     * kiểm tra tồn tại rồi ghi chỉ an toàn khi các lần ghi của một người được xếp hàng.
     */
    @Query(value = "select 1 from pg_advisory_xact_lock(hashtextextended(:key, 0))", nativeQuery = true)
    int lockUser(@Param("key") String key);
}
