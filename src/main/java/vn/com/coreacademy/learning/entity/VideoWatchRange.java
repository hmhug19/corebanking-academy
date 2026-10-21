package vn.com.coreacademy.learning.entity;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Khoảng video được backend chấp nhận, gộp phần giao nhau. Các cột ID liên kết là scalar, không tạo khóa ngoại vật
 * lý.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "video_watch_ranges", schema = "learning_hub")
public class VideoWatchRange extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "watch_range_id", nullable = false)
    private Long id;

    @Column(name = "block_progress_id", nullable = false)
    private Long blockProgressId;

    @Column(name = "start_second", nullable = false)
    private BigDecimal startSecond;

    @Column(name = "end_second", nullable = false)
    private BigDecimal endSecond;
}
