package kz.vainshtein.raftscore.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

class RestoreServiceTest {
    @TempDir Path directory;
    @Test void rejectsUnexpectedAndDuplicateEntriesBeforeDatabaseAccess() throws Exception {
        var executable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        var service = new RestoreService(directory.toString(), executable.toString(), "jdbc:postgresql://localhost/test", "user", "secret", Duration.ofSeconds(1), "key");
        var bytes = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("../archive.dump"));
            zip.write(1);
        }
        assertThatThrownBy(() -> service.restoreUpload(new ByteArrayInputStream(bytes.toByteArray())))
                .hasMessageContaining("entries");
    }
}
