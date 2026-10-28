package vn.com.coreacademy.assessment.entity;

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

/** Lượt thi cấp thêm cho một học viên; chỉ dòng chưa thu hồi được cộng vào số lượt. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "quiz_attempt_grants", schema = "learning_hub")
public class QuizAttemptGrant {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "grant_id", nullable = false)
    private Long id;

    @Column(name = "quiz_id", nullable = false)
    private Long quizId;

    @Column(name = "user_id", length = 20, nullable = false)
    private String userId;

    @Column(name = "extra_attempts", nullable = false)
    private Integer extraAttempts;

    @Column(name = "reason", columnDefinition = "text", nullable = false)
    private String reason;

    @Column(name = "granted_by", length = 20, nullable = false)
    private String grantedBy;

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt;

    @Column(name = "is_deleted", nullable = false)
    private Boolean deleted = false;

    @Column(name = "deleted_by", length = 20)
    private String deletedBy;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
