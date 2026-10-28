package vn.com.coreacademy.assessment.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Bật scheduling cho job tự nộp bài để không phụ thuộc module khác đã bật. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class AssessmentSchedulingConfig {
}
