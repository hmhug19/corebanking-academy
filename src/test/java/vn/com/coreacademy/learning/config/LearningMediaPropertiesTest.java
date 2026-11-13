package vn.com.coreacademy.learning.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LearningMediaPropertiesTest {
    @Test
    void autoTranscodeThreadsFollowTheCpusUpToFour() {
        LearningMediaProperties properties = new LearningMediaProperties();
        int cpus = Runtime.getRuntime().availableProcessors();

        assertThat(properties.getTranscodeThreads()).isZero();
        assertThat(properties.effectiveTranscodeThreads()).isEqualTo(Math.min(4, cpus)).isBetween(1, 4);
    }

    @Test
    void configuredTranscodeThreadsWin() {
        LearningMediaProperties properties = new LearningMediaProperties();
        properties.setTranscodeThreads(8);

        assertThat(properties.effectiveTranscodeThreads()).isEqualTo(8);
        assertThat(properties.getTranscodePreset()).isEqualTo("veryfast");
    }
}
