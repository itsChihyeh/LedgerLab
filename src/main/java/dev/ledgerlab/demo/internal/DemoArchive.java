package dev.ledgerlab.demo.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The daily-reset archive (section 5, AC-9.5), read only. {@code scripts/demo/reset.sh} writes one summary per
 * reset into a directory outside the stack and mounts it read-only here, so wiping the database never wipes the
 * history; this class only lists what is there. A missing or unreadable directory is an empty history.
 */
@Component
@Profile("demo")
final class DemoArchive {
    private static final Logger LOG = LoggerFactory.getLogger(DemoArchive.class);
    static final int KEEP = 7;
    static final String LAST_RESET_FILE = "last-reset.txt";
    private static final long MAX_FILE_BYTES = 256 * 1024;

    private final @Nullable Path directory;
    private final ObjectMapper mapper;

    DemoArchive(@Value("${ledgerlab.demo.archive-dir:}") String directory, ObjectMapper mapper) {
        this.directory = directory.isBlank() ? null : Path.of(directory);
        this.mapper = mapper;
    }

    /** {@code lastReset} (null when unknown) and the newest {@value #KEEP} archived summaries, newest first. */
    Map<String, Object> read() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("lastReset", lastReset());
        body.put("archive", summaries());
        return body;
    }

    private @Nullable String lastReset() {
        if (directory == null) return null;
        try {
            String text = Files.readString(directory.resolve(LAST_RESET_FILE), StandardCharsets.UTF_8)
                    .strip();
            return text.isEmpty() ? null : text;
        } catch (IOException missing) {
            return null;
        }
    }

    private List<Object> summaries() {
        List<Object> found = new ArrayList<>();
        if (directory == null || !Files.isDirectory(directory)) return found;
        List<Path> files;
        try (Stream<Path> listing = Files.list(directory)) {
            files = listing.filter(file -> file.getFileName().toString().matches("\\d{4}-\\d{2}-\\d{2}.*\\.json"))
                    .sorted(Comparator.comparing(
                                    (Path file) -> file.getFileName().toString())
                            .reversed())
                    .toList();
        } catch (IOException unreadable) {
            LOG.warn("Cannot list the demo archive", unreadable);
            return found;
        }
        for (Path file : files) {
            if (found.size() >= KEEP) break;
            try {
                if (Files.size(file) > MAX_FILE_BYTES) continue;
                JsonNode summary = mapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("file", file.getFileName().toString());
                entry.put("summary", summary);
                found.add(entry);
            } catch (IOException | RuntimeException broken) {
                LOG.warn("Skipping an unreadable archive file {}", file.getFileName());
            }
        }
        return found;
    }
}
