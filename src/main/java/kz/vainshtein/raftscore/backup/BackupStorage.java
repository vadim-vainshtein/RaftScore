package kz.vainshtein.raftscore.backup;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.UUID;

/**
 * Boundary for local backup artifacts. Implementations must keep their storage paths private.
 * This interface does not perform authorization: every future backup endpoint, including
 * GET, HEAD and download, must explicitly require FULL_ACCESS. The default API GET/HEAD
 * policy permits any authenticated user and is insufficient for backups.
 */
public interface BackupStorage {
    /**
     * Consumes the complete artifact and publishes it with its metadata atomically. The
     * implementation generates a stable, opaque UUID, records the actual byte count and
     * publication time, and never exposes a partial artifact or metadata on failure.
     * It must not close {@code content}; the caller retains ownership even on failure.
     * Format and schema versions describe the supplied artifact, not the current server.
     *
     * @throws IOException if reading or publication fails
     */
    BackupMetadata store(InputStream content, String formatVersion, String schemaVersion) throws IOException;

    java.util.List<BackupMetadata> list() throws IOException;

    /** Returns published metadata, or empty if the ID is unknown. */
    Optional<BackupMetadata> find(UUID id) throws IOException;

    /**
     * Opens a published artifact, or returns empty if the ID is unknown. The caller owns
     * and must close the returned stream. An I/O failure is not reported as a missing ID.
     */
    Optional<InputStream> open(UUID id) throws IOException;
}
