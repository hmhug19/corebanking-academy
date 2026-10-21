package vn.com.coreacademy.learning.entity;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Tiến độ từng khối; sequence chống cập nhật cũ ghi đè. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "block_progress", schema = "learning_hub")
public class BlockProgress extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "block_progress_id", nullable = false)
    private Long id;

    @Column(name = "enrollment_id", nullable = false)
    private Long enrollmentId;

    @Column(name = "block_id", nullable = false)
    private Long blockId;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "progress_percent", nullable = false)
    private BigDecimal progressPercent;

    @Column(name = "last_video_position_seconds")
    private BigDecimal lastVideoPositionSeconds;

    @Column(name = "last_pdf_page")
    private Integer lastPdfPage;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "last_access_at")
    private Instant lastAccessAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "last_event_sequence", nullable = false)
    private Long lastEventSequence;
}
