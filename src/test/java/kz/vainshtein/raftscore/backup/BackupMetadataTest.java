package kz.vainshtein.raftscore.backup;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class BackupMetadataTest {
    private final UUID id = UUID.randomUUID();
    private final Instant createdAt = Instant.parse("2026-01-02T03:04:05Z");

    @Test
    void retainsArtifactIdentityAndCompatibilityInformation() {
        var metadata = new BackupMetadata(id, createdAt, 42, "format-1", "schema-2");

        assertThat(metadata.id()).isEqualTo(id);
        assertThat(metadata.createdAt()).isEqualTo(createdAt);
        assertThat(metadata.sizeBytes()).isEqualTo(42);
        assertThat(metadata.formatVersion()).isEqualTo("format-1");
        assertThat(metadata.schemaVersion()).isEqualTo("schema-2");
    }

    @Test
    void rejectsIncompleteOrInvalidMetadata() {
        assertThatNullPointerException().isThrownBy(() -> new BackupMetadata(null, createdAt, 0, "1", "1"));
        assertThatNullPointerException().isThrownBy(() -> new BackupMetadata(id, null, 0, "1", "1"));
        assertThatIllegalArgumentException().isThrownBy(() -> new BackupMetadata(id, createdAt, -1, "1", "1"));
        assertThatIllegalArgumentException().isThrownBy(() -> new BackupMetadata(id, createdAt, 0, " ", "1"));
        assertThatIllegalArgumentException().isThrownBy(() -> new BackupMetadata(id, createdAt, 0, "1", null));
    }
}
