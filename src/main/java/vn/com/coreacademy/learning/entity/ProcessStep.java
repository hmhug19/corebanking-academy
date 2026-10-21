package vn.com.coreacademy.learning.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Một bước trong khối quy trình. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "process_steps", schema = "learning_hub")
public class ProcessStep extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "step_id", nullable = false)
    private Long id;

    @Column(name = "block_id", nullable = false)
    private Long blockId;

    @Column(name = "title", length = 255, nullable = false)
    private String title;

    @Column(name = "description", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String description;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;
}
