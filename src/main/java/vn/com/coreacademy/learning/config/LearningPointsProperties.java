package vn.com.coreacademy.learning.config;

import jakarta.validation.constraints.Min;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Trọng số XP của phiên bản cấu hình 1, bind qua app.learning.points.* hoặc biến môi trường APP_LEARNING_POINTS_*.
 * Chỉ dùng một lần để gieo phiên bản 1 khi bảng phiên bản còn trống; sau đó trọng số nằm ở
 * gamification_config_versions và đổi trọng số chỉ áp dụng từ lúc đổi. Điểm thưởng của khóa lấy từ chính khóa.
 */
@Component("learningPointsProperties")
@ConfigurationProperties("app.learning.points")
@Validated
@Getter
@Setter
@NoArgsConstructor
public class LearningPointsProperties {
    /** XP cho mỗi bài học hoàn thành (mỗi bài chỉ tính một lần dù học lại ở nhiều lần học hoặc phiên bản). */
    @Min(0)
    private int lesson = 5;

    /** XP cho mỗi bài kiểm tra đạt (mỗi bài kiểm tra chỉ tính một lần). */
    @Min(0)
    private int quizPass = 30;
}
