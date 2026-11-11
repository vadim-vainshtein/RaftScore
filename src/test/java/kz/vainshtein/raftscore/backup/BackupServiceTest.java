package kz.vainshtein.raftscore.backup;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BackupServiceTest {
    @TempDir Path directory;

    @Test
    void rejectsUnconfiguredExecutableWithoutPublishingAnything() throws Exception {
        var service = new BackupService(directory.toString(), "", "jdbc:postgresql://localhost/test", "user", "secret", Duration.ofSeconds(1));
        assertThatThrownBy(service::create).hasMessageContaining("trusted absolute pg_dump");
        assertThat(service.list()).isEmpty();
    }
    @Test
    void databaseNamesMatchJdbcFormDecoding() {
        assertThat(BackupService.databaseName(java.net.URI.create("postgresql://localhost/name+space"))).isEqualTo("name space");
        assertThat(BackupService.databaseName(java.net.URI.create("postgresql://localhost/name%2Bplus"))).isEqualTo("name+plus");
    }

    @Test
    void cleanupFailureDoesNotEscape() {
        try (var files = org.mockito.Mockito.mockStatic(java.nio.file.Files.class)) {
            files.when(() -> java.nio.file.Files.exists(directory)).thenReturn(true);
            files.when(() -> java.nio.file.Files.walk(directory)).thenThrow(new java.io.IOException("denied"));
            org.assertj.core.api.Assertions.assertThatCode(() -> BackupTemporaryFiles.cleanup(directory)).doesNotThrowAnyException();
        }
    }
    @Test
    void schemaConnectionIsBoundedWhenServerDoesNotRespond() throws Exception {
        try (var server = new java.net.ServerSocket(0)) {
            var peer = Thread.ofVirtual().start(() -> {
                try (var socket = server.accept()) { Thread.sleep(5000); }
                catch (Exception ignored) { }
            });
            try {
                var connection = new DatabaseConnection("jdbc:postgresql://127.0.0.1:" + server.getLocalPort() + "/test", "user", "secret", Duration.ofSeconds(1));
                var start = System.nanoTime();
                assertThatThrownBy(connection::schemaFingerprints).isInstanceOf(java.io.IOException.class);
                assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(4));
            } finally { peer.interrupt(); peer.join(); }
        }
    }
}
