package vn.com.coreacademy.learning.entity;

import java.time.LocalDate;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import vn.com.coreacademy.common.persistence.CreatedEntity;

/**
 * Một ngày học của một người dùng, theo ngày lịch Asia/Ho_Chi_Minh. Hệ thống ghi và chỉ thêm, nên không có cột audit
 * hay row_version. Một dòng mỗi người mỗi ngày do LearningActivityService bảo đảm dưới khóa advisory theo người dùng.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "learning_activity_days", schema = "learning_hub")
public class LearningActivityDay extends CreatedEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "activity_id", nullable = false)
    private Long id;

    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;

    @Column(name = "activity_date", nullable = false)
    private LocalDate activityDate;

    @Column(name = "first_source", length = 20, nullable = false)
    private String firstSource;
}
