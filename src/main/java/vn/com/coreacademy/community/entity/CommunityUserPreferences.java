package vn.com.coreacademy.community.entity;

import java.time.Instant;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Onboarding và lịch nhận bản tin của một người; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_user_preferences", schema = "learning_hub")
public class CommunityUserPreferences extends CommunityRecord {
    @Id
    @Column(name = "user_id", nullable = false, length = 20)
    private String userId;

    @Column(name = "onboarding_completed_at")
    private Instant onboardingCompletedAt;

    @Column(name = "time_zone", nullable = false, length = 100)
    private String timeZone;

    @Column(name = "digest_frequency", nullable = false, length = 30)
    private String digestFrequency;

    @Column(name = "digest_local_time", nullable = false)
    private LocalTime digestLocalTime;

    @Column(name = "digest_weekday")
    private Integer digestWeekday;

    @Column(name = "next_digest_at")
    private Instant nextDigestAt;

    @Column(name = "notification_preferences_json", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private JsonNode notificationPreferencesJson;
}
