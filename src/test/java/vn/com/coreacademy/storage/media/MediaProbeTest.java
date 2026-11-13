package vn.com.coreacademy.storage.media;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MediaProbeTest {
    private final MediaProbe probe = new MediaProbe(
        new MediaProbe.Settings("ffprobe", "ffmpeg", Duration.ofSeconds(30)), new ObjectMapper());

    @TempDir
    Path directory;

    @Test
    void killedOrOutOfResourceToolIsAnInfrastructureFailure() {
        // SIGKILL (container OOM killer), SIGTERM, ffmpeg ENOMEM and ENOSPC.
        assertThat(List.of(137, 143, 244, 228)).allMatch(MediaProbe::infrastructureFailure);
        // Success, ffprobe's error, ffmpeg's AVERROR_INVALIDDATA ("Invalid data found when processing
        // input", measured on a junk .mp4) and EINVAL.
        assertThat(List.of(0, 1, 183, 234)).noneMatch(MediaProbe::infrastructureFailure);
    }

    @Test
    void toolKilledBySystemIsRetriedNotReportedAsInvalidFile() throws IOException {
        assertThatThrownBy(() -> probe.run(exitWith(137), Duration.ofSeconds(30)))
            .isInstanceOf(IllegalStateException.class)
            .isNotInstanceOf(MediaProbe.Rejected.class);
        assertThatThrownBy(() -> probe.run(exitWith(244), Duration.ofSeconds(30)))
            .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toolRefusingTheFileStillRejectsIt() throws IOException {
        assertThatThrownBy(() -> probe.run(exitWith(1), Duration.ofSeconds(30)))
            .isInstanceOf(MediaProbe.Rejected.class)
            .hasMessage("ASSET_INVALID");
    }

    @Test
    void successfulToolReturnsItsOutput() throws IOException {
        assertThat(probe.run(exitWith(0), Duration.ofSeconds(30))).contains("{\"ok\":true}");
    }

    @Test
    void transcodeUsesTheConfiguredThreadsAndPreset() throws IOException {
        MediaProbe tuned = new MediaProbe(new MediaProbe.Settings("ffprobe", "ffmpeg", Duration.ofSeconds(30),
            Duration.ofHours(6), 4, "superfast"), new ObjectMapper());
        ObjectMapper json = new ObjectMapper();
        JsonNode video = json.readTree("{\"index\":0}");
        JsonNode audio = json.readTree("{\"index\":1}");

        List<String> command = tuned.mp4Command(Path.of("in.mp4"), Path.of("out.mp4"), video, audio,
            new BigDecimal("600"), 500_000_000L, false);

        // Decoder threads come before -i, encoder threads after it: both follow the setting.
        int input = command.indexOf("-i");
        assertThat(command.subList(0, input)).containsSequence("-threads", "4");
        assertThat(command.subList(input, command.size())).containsSequence("-threads", "4")
            .containsSequence("-preset", "superfast");
    }

    @Test
    void settingsWithoutTuningKeepTwoThreadsAndVeryfast() {
        var settings = new MediaProbe.Settings("ffprobe", "ffmpeg", Duration.ofSeconds(30));
        assertThat(settings.threads()).isEqualTo(2);
        assertThat(settings.preset()).isEqualTo("veryfast");
        assertThatThrownBy(() -> new MediaProbe.Settings("ffprobe", "ffmpeg", Duration.ofSeconds(30),
            Duration.ofHours(6), 0, "veryfast")).isInstanceOf(IllegalArgumentException.class);
    }

    /** A stand-in for ffmpeg: a real child process that prints a line and exits with `code`. */
    private List<String> exitWith(int code) throws IOException {
        Path source = directory.resolve("Exit.java");
        Files.writeString(source, """
            public class Exit {
                public static void main(String[] args) {
                    System.out.println("{\\"ok\\":true}");
                    System.exit(Integer.parseInt(args[0]));
                }
            }
            """);
        String java = ProcessHandle.current().info().command().orElse("java");
        return List.of(java, source.toString(), Integer.toString(code));
    }
}
