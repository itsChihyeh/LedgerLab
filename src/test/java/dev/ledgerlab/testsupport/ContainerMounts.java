package dev.ledgerlab.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.stream.Stream;

/**
 * Makes a host directory readable by a container that runs as another user. JUnit creates {@code @TempDir} with
 * owner-only permissions ({@code rwx------}); the {@code prom/prometheus} and {@code prom/alertmanager} images run as
 * {@code nobody}, so on Linux they could not even {@code stat} a file bind-mounted from it (CI failure, 2026-11-07).
 * Docker Desktop on Windows and macOS does not apply host permissions, which is why it only showed up in CI. On a file
 * system without POSIX permissions this does nothing.
 */
public final class ContainerMounts {
    private ContainerMounts() {}

    /** Opens {@code dir} and everything under it for reading: directories {@code rwxr-xr-x}, files {@code rw-r--r--}. */
    public static Path readable(Path dir) {
        if (Files.getFileAttributeView(dir, PosixFileAttributeView.class) == null) return dir;
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.toList())
                Files.setPosixFilePermissions(
                        path, PosixFilePermissions.fromString(Files.isDirectory(path) ? "rwxr-xr-x" : "rw-r--r--"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return dir;
    }
}
