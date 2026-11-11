package kz.vainshtein.raftscore.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import testsupport.PostgreSqlIntegrationTest;
import java.nio.file.*;
import java.sql.DriverManager;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
class RestoreIntegrationTest extends PostgreSqlIntegrationTest {
    @Autowired Environment environment;
    @TempDir Path directory;
    private String property(String name) { return environment.getProperty("spring.datasource." + name); }
    private String url() { return property("url").split("\\?", 2)[0]; }

    @Test
    void signedUploadRestoresDisposableDatabaseAndTamperingIsRejected() throws Exception {
        var dump = Path.of("C:/Program Files/PostgreSQL/18/bin/pg_dump.exe");
        var restore = Path.of("C:/Program Files/PostgreSQL/18/bin/pg_restore.exe");
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(dump) && Files.isRegularFile(restore));
        var backup = new BackupService(directory.toString(), dump.toString(), url(), property("username"), property("password"), Duration.ofSeconds(30), "portable-secret");
        var service = new RestoreService(directory.toString(), restore.toString(), url(), property("username"), property("password"), Duration.ofSeconds(30), "portable-secret");
        try (var db = DriverManager.getConnection(url(), property("username"), property("password")); var sql = db.createStatement()) {
            sql.execute("CREATE TABLE restore_test_marker (value integer)");
            sql.execute("INSERT INTO restore_test_marker VALUES (1)");
            var artifact = backup.create();
            var legacyBytes = new java.io.ByteArrayOutputStream();
            try (var source = new java.util.zip.ZipInputStream(backup.archive(artifact.id()).orElseThrow());
                 var zip = new java.util.zip.ZipOutputStream(legacyBytes)) {
                for (var entry = source.getNextEntry(); entry != null; entry = source.getNextEntry()) {
                    zip.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                    if (entry.getName().equals("manifest.properties")) {
                        var manifest = new java.util.Properties();
                        manifest.load(source);
                        manifest.remove("catalogVersion");
                        manifest.remove("hmacSha256");
                        manifest.store(zip, null);
                    } else source.transferTo(zip);
                    zip.closeEntry();
                }
            }
            var legacy = new FileBackupStorage(directory).store(new java.io.ByteArrayInputStream(legacyBytes.toByteArray()), artifact.formatVersion(), artifact.schemaVersion());
            assertThatThrownBy(() -> service.restore(legacy.id())).hasMessageContaining("schema");
            sql.execute("UPDATE restore_test_marker SET value = 2");
            sql.execute("CREATE TABLE restore_test_extra (value integer)");
            sql.execute("INSERT INTO restore_test_extra VALUES (7)");
            assertThatThrownBy(() -> service.restore(artifact.id())).hasMessageContaining("schema");
            try (var rows = sql.executeQuery("SELECT value FROM restore_test_marker")) { assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(2); }
            try (var rows = sql.executeQuery("SELECT value FROM restore_test_extra")) { assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(7); }
            sql.execute("DROP TABLE restore_test_extra");
            try (var in = backup.archive(artifact.id()).orElseThrow()) { service.restoreUpload(in); }
            try (var rows = sql.executeQuery("SELECT value FROM restore_test_marker")) { assertThat(rows.next()).isTrue(); assertThat(rows.getInt(1)).isEqualTo(1); }
            var wrongKey = new RestoreService(directory.toString(), restore.toString(), url(), property("username"), property("password"), Duration.ofSeconds(30), "wrong-secret");
            try (var in = backup.archive(artifact.id()).orElseThrow()) { assertThatThrownBy(() -> wrongKey.restoreUpload(in)).hasMessageContaining("authentication"); }
        } finally {
            try (var db = DriverManager.getConnection(url(), property("username"), property("password")); var sql = db.createStatement()) {
                sql.execute("DROP TABLE IF EXISTS restore_test_extra");
                sql.execute("DROP TABLE IF EXISTS restore_test_marker");
            }
        }
    }
}
