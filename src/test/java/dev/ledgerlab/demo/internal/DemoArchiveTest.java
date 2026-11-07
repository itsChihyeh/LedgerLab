package dev.ledgerlab.demo.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/** The archive the daily reset leaves behind, as the demo page reads it (AC-9.5). No database involved. */
class DemoArchiveTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void ac_9_5_noDirectoryMeansNoHistory() {
        Map<String, Object> body = new DemoArchive("", mapper).read();
        assertThat(body.get("lastReset")).isNull();
        assertThat((List<?>) body.get("archive")).isEmpty();
    }

    @Test
    void ac_9_5_listsTheNewestSevenAndSkipsBrokenFiles(@TempDir Path dir) throws IOException {
        for (int day = 1; day <= 9; day++) {
            Files.writeString(dir.resolve("2026-11-0" + day + ".json"), "{\"day\":" + day + "}");
        }
        Files.writeString(dir.resolve("2026-11-10.json"), "{ not json");
        Files.writeString(dir.resolve("notes.json"), "{\"day\":0}");
        Files.writeString(dir.resolve(DemoArchive.LAST_RESET_FILE), "2026-11-10T03:00:00Z\n");

        Map<String, Object> body = new DemoArchive(dir.toString(), mapper).read();

        assertThat(body.get("lastReset")).isEqualTo("2026-11-10T03:00:00Z");
        List<?> archive = Objects.requireNonNull((List<?>) body.get("archive"));
        assertThat(archive).hasSize(DemoArchive.KEEP);
        assertThat(((Map<?, ?>) archive.get(0)).get("file")).isEqualTo("2026-11-09.json");
        assertThat(((Map<?, ?>) archive.get(DemoArchive.KEEP - 1)).get("file")).isEqualTo("2026-11-03.json");
    }

    @Test
    void ac_9_5_aMissingDirectoryIsAnEmptyHistory(@TempDir Path dir) {
        Map<String, Object> body = new DemoArchive(dir.resolve("absent").toString(), mapper).read();
        assertThat(body.get("lastReset")).isNull();
        assertThat((List<?>) body.get("archive")).isEmpty();
    }
}
