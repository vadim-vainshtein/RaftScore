package kz.vainshtein.raftscore.backup;

import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/** Best-effort cleanup cannot change the outcome of publication or hide its failure. */
final class BackupTemporaryFiles {
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(BackupTemporaryFiles.class);
    private BackupTemporaryFiles() {}

    static void cleanup(Path directory) {
        try {
            if (!Files.exists(directory)) return;
            try (var files = Files.walk(directory)) {
                for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file);
            }
        } catch (IOException | java.io.UncheckedIOException | SecurityException e) {
            log.warn("Could not remove temporary backup files");
        }
    }
}
