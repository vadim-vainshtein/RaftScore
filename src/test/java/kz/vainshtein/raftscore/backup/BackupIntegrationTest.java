package kz.vainshtein.raftscore.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import testsupport.PostgreSqlIntegrationTest;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.zip.ZipInputStream;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class BackupIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired Environment environment;
    @TempDir Path directory;

    private BackupService service(String executable) {
        return new BackupService(directory.toString(), executable, environment.getProperty("spring.datasource.url").split("\\?", 2)[0],
                environment.getProperty("spring.datasource.username"), environment.getProperty("spring.datasource.password"), Duration.ofSeconds(30), "test-signing-key");
    }

    @Test
    void dumpsDisposableDatabaseAndDownloadsPortableBundle() throws Exception {
        var executable = Path.of("C:/Program Files/PostgreSQL/18/bin/pg_dump.exe");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(executable));
        var service = service(executable.toString());
        var backup = service.create();
        assertThat(service.list()).containsExactly(backup);
        var response = new BackupController(service).download(backup.id());
        var output = new ByteArrayOutputStream();
        response.getBody().writeTo(output);
        try (var zip = new ZipInputStream(new java.io.ByteArrayInputStream(output.toByteArray()))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("manifest.properties");
            var manifest = new java.util.Properties();
            manifest.load(zip);
            assertThat(output.size()).isEqualTo(backup.sizeBytes());
            assertThat(zip.getNextEntry().getName()).isEqualTo("archive.dump");
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            zip.transferTo(new java.security.DigestOutputStream(java.io.OutputStream.nullOutputStream(), digest));
            assertThat(java.util.HexFormat.of().formatHex(digest.digest())).isEqualTo(manifest.getProperty("sha256"));
            assertThat(zip.getNextEntry()).isNull();
        }
    }

    @Test
    void failedProcessLeavesNoPublishedOrTemporaryBackup() throws Exception {
        var executable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(executable));
        var service = service(executable.toString());
        assertThatThrownBy(service::create).hasMessageContaining("pg_dump failed");
        try (var files = Files.list(directory)) { assertThat(files.toList()).isEmpty(); }
    }
}
