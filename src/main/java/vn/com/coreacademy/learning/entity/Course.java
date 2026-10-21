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

/** Định danh khóa học ổn định qua các phiên bản. Các cột ID liên kết là scalar, không tạo khóa ngoại vật lý. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "courses", schema = "learning_hub")
public class Course extends LearningRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "course_id", nullable = false)
    private Long id;

    @Column(name = "code", length = 50, nullable = false)
    private String code;

    @Column(name = "owner_org_id", nullable = false)
    private Long ownerOrgId;

    @Column(name = "owner_team_id")
    private Long ownerTeamId;

    @Column(name = "owner_user_id", length = 20, nullable = false)
    private String ownerUserId;

    @Column(name = "published_revision_id")
    private Long publishedRevisionId;

    @Column(name = "lifecycle_status", length = 20, nullable = false)
    private String lifecycleStatus;

    @Column(name = "copied_from_course_id")
    private Long copiedFromCourseId;
}
