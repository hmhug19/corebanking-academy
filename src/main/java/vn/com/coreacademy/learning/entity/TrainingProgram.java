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

/** Một chương trình thuộc lĩnh vực. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "training_programs", schema = "learning_hub")
public class TrainingProgram extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "program_id", nullable = false)
    private Long id;

    @Column(name = "field_id", nullable = false)
    private Long fieldId;

    @Column(name = "owner_org_id", nullable = false)
    private Long ownerOrgId;

    @Column(name = "code", length = 50, nullable = false)
    private String code;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String description;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;

    @Column(name = "copied_from_program_id")
    private Long copiedFromProgramId;
}
