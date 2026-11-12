package vn.com.coreacademy.knowledge.extraction;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextQualityTest {
    @Test
    void vietnameseSyllablesAndPlainWordsAreReadable() {
        for (String word : List.of("Quy", "trình", "nghiệp", "vụ", "thường", "người", "khuyến", "Đăng", "giờ", "quốc",
            "checklist", "ECR", "Core", "Banking", "ươn", "yêu")) {
            assertThat(TextQuality.isReadable(word)).as(word).isTrue();
        }
    }

    @Test
    void lettersFromALegacyCodePageAreNot() {
        for (String word : List.of("Hµ", "®ãng", "sæ", "cuèi", "ngµy", "tr×nh", "Hä", "qu¶n", "lý¹")) {
            assertThat(TextQuality.isReadable(word)).as(word).isFalse();
        }
    }

    @Test
    void textWithoutWordsIsNotJudged() {
        assertThat(TextQuality.readableWordShare(List.of("2026 - 11 / 12", "", "|||"))).isEqualTo(1.0);
    }
}
