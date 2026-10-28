package vn.com.coreacademy.assessment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Nhóm kiến thức/năng lực dùng chung toàn hệ thống. parent_id là quan hệ logic, không có FK. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "competencies", schema = "learning_hub")
public class Competency extends AssessmentRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "competency_id", nullable = false)
    private Long id;

    @Column(name = "code", length = 50, nullable = false)
    private String code;

    @Column(name = "name", length = 255, nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @Column(name = "parent_id")
    private Long parentId;

    @Column(name = "sort_no", nullable = false)
    private Integer sortNo;

    @Column(name = "status", length = 20, nullable = false)
    private String status;

    @Column(name = "is_deleted", nullable = false)
    private Boolean deleted = false;
}
