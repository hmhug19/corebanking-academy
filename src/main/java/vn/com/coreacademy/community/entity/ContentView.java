package vn.com.coreacademy.community.entity;

import java.time.Instant;
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

/** Một lượt đọc duy nhất theo người/ngày phục vụ thống kê; các ID tham chiếu là scalar, không có quan hệ JPA. */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "community_content_views", schema = "learning_hub")
public class ContentView extends CommunityRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "view_id", nullable = false)
    private Long viewId;

    @Column(name = "content_id", nullable = false)
    private Long contentId;

    @Column(name = "user_id", nullable = false, length = 20)
    private String userId;

    @Column(name = "view_date", nullable = false)
    private LocalDate viewDate;

    @Column(name = "first_view_at", nullable = false)
    private Instant firstViewAt;

    @Column(name = "last_view_at", nullable = false)
    private Instant lastViewAt;
}
