package kz.vainshtein.raftscore.backup;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/** Publishes a bundle and its metadata as one atomically renamed directory. */
final class FileBackupStorage implements BackupStorage {
    private final Path directory;
    FileBackupStorage(Path directory) { this.directory = directory; }

    @Override
    public BackupMetadata store(InputStream content, String formatVersion, String schemaVersion) throws IOException {
        Files.createDirectories(directory);
        var pending = Files.createTempDirectory(directory, ".publish-");
        try {
            var bundle = pending.resolve("bundle.zip");
            Files.copy(content, bundle);
            var metadata = new BackupMetadata(UUID.randomUUID(), Instant.now(), Files.size(bundle), formatVersion, schemaVersion);
            var properties = new Properties();
            properties.setProperty("id", metadata.id().toString());
            properties.setProperty("createdAt", metadata.createdAt().toString());
            properties.setProperty("sizeBytes", Long.toString(metadata.sizeBytes()));
            properties.setProperty("formatVersion", metadata.formatVersion());
            properties.setProperty("schemaVersion", metadata.schemaVersion());
            try (var out = Files.newOutputStream(pending.resolve("metadata.properties"))) { properties.store(out, null); }
            Files.move(pending, directory.resolve(metadata.id().toString()), StandardCopyOption.ATOMIC_MOVE);
            return metadata;
        } finally {
            BackupTemporaryFiles.cleanup(pending);
        }
    }

    @Override
    public Optional<BackupMetadata> find(UUID id) throws IOException {
        var path = directory.resolve(id.toString());
        if (!Files.isDirectory(path)) return Optional.empty();
        var properties = new Properties();
        try (var in = Files.newInputStream(path.resolve("metadata.properties"))) { properties.load(in); }
        try {
            var metadata = new BackupMetadata(UUID.fromString(properties.getProperty("id")), Instant.parse(properties.getProperty("createdAt")),
                    Long.parseLong(properties.getProperty("sizeBytes")), properties.getProperty("formatVersion"), properties.getProperty("schemaVersion"));
            if (!id.equals(metadata.id()) || metadata.sizeBytes() != Files.size(path.resolve("bundle.zip"))) throw new IOException("Invalid backup metadata");
            return Optional.of(metadata);
        } catch (RuntimeException e) { throw new IOException("Invalid backup metadata", e); }
    }

    @Override
    public Optional<InputStream> open(UUID id) throws IOException {
        if (find(id).isEmpty()) return Optional.empty();
        return Optional.of(Files.newInputStream(directory.resolve(id.toString()).resolve("bundle.zip")));
    }

    @Override
    public List<BackupMetadata> list() throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        var result = new ArrayList<BackupMetadata>();
        try (var entries = Files.list(directory)) {
            for (var entry : entries.toList()) {
                UUID id;
                try { id = UUID.fromString(entry.getFileName().toString()); }
                catch (IllegalArgumentException e) { continue; }
                find(id).ifPresent(result::add);
            }
        }
        result.sort(Comparator.comparing(BackupMetadata::createdAt).reversed());
        return result;
    }
}
