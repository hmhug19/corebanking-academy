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

/** Một đối tượng chọn giao với đúng một cột ID đích. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "assignment_targets", schema = "learning_hub")
public class AssignmentTarget extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "target_id", nullable = false)
    private Long id;

    @Column(name = "assignment_id", nullable = false)
    private Long assignmentId;

    @Column(name = "target_type", length = 20, nullable = false)
    private String targetType;

    @Column(name = "user_id", length = 20)
    private String userId;

    @Column(name = "team_id")
    private Long teamId;

    @Column(name = "org_id")
    private Long orgId;

    @Column(name = "group_id")
    private Long groupId;

    @Column(name = "include_descendants", nullable = false)
    private Boolean includeDescendants;

    @Column(name = "target_name_snapshot", length = 255, nullable = false)
    private String targetNameSnapshot;
}
