package vn.com.coreacademy.assessment.config;

import java.time.Duration;

import jakarta.validation.constraints.NotNull;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/** Tham số vận hành Quiz Engine; bind qua app.assessment.* hoặc biến môi trường. */
@Component("assessmentProperties")
@ConfigurationProperties("app.assessment")
@Validated
@Getter
@Setter
@NoArgsConstructor
public class AssessmentProperties {
    /** Chu kỳ quét lượt thi quá hạn để tự nộp. */
    @NotNull
    private Duration autoSubmitInterval = Duration.ofMinutes(1);

    @NotNull
    private Duration autoSubmitInitialDelay = Duration.ofSeconds(30);
}
