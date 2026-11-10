package kz.vainshtein.raftscore.backup;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Public description of a published backup; it never contains a storage path. */
public record BackupMetadata(UUID id, Instant createdAt, long sizeBytes, String formatVersion, String schemaVersion) {
    public BackupMetadata {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(createdAt, "createdAt");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must be non-negative");
        }
        requireVersion(formatVersion, "formatVersion");
        requireVersion(schemaVersion, "schemaVersion");
    }

    private static void requireVersion(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
