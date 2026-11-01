package vn.com.coreacademy.learning.config;

import jakarta.validation.constraints.Min;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Trọng số điểm học tập, bind qua app.learning.points.* hoặc biến môi trường APP_LEARNING_POINTS_*. Đổi trọng số làm
 * điểm của mọi người đổi theo vì điểm được tính lại khi đọc; điểm thưởng của khóa lấy từ chính khóa.
 */
@Component("learningPointsProperties")
@ConfigurationProperties("app.learning.points")
@Validated
@Getter
@Setter
@NoArgsConstructor
public class LearningPointsProperties {
    /** Điểm cho mỗi bài học hoàn thành (mỗi bài chỉ tính một lần dù học lại ở nhiều lần học hoặc phiên bản). */
    @Min(0)
    private int lesson = 5;

    /** Điểm cho mỗi bài kiểm tra đạt (mỗi bài kiểm tra chỉ tính một lần). */
    @Min(0)
    private int quizPass = 30;

    /** Điểm cho mỗi ngày trong chuỗi ngày học. */
    @Min(0)
    private int streakDay = 10;

    /** Số ngày tối đa của chuỗi được tính điểm; chuỗi dài hơn vẫn hiển thị đủ nhưng không thêm điểm. */
    @Min(1)
    private int streakCap = 30;
}
