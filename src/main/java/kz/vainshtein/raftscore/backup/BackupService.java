package kz.vainshtein.raftscore.backup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Creates PostgreSQL custom archives and publishes each archive and manifest together. */
@Service
public class BackupService {
    static final String FORMAT = "postgresql-custom-v1";
    private final Path directory;
    private final BackupStorage storage;
    private final String executable;
    private final DatabaseConnection connection;
    private final String signingKey;
    private final Duration timeout;

    @org.springframework.beans.factory.annotation.Autowired
    public BackupService(@Value("${raftscore.backup.directory:./backups}") String directory,
                         @Value("${raftscore.backup.pg-dump:}") String executable,
                         @Value("${spring.datasource.url}") String url,
                         @Value("${spring.datasource.username}") String username,
                         @Value("${spring.datasource.password:}") String password,
                         @Value("${raftscore.backup.timeout:PT10M}") Duration timeout,
                         @Value("${raftscore.backup.signing-key:}") String signingKey) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.storage = new FileBackupStorage(this.directory);
        this.executable = executable;
        this.connection = new DatabaseConnection(url, username, password, timeout);
        this.signingKey = signingKey;
        this.timeout = timeout;
    }

    BackupService(String directory, String executable, String url, String username, String password, Duration timeout) {
        this(directory, executable, url, username, password, timeout, "");
    }

    public BackupMetadata create() throws IOException, InterruptedException {
        if (executable.isBlank() || !Path.of(executable).isAbsolute() || !Files.isRegularFile(Path.of(executable))) {
            throw new IOException("A trusted absolute pg_dump executable must be configured");
        }
        if (timeout.isNegative() || timeout.isZero()) throw new IOException("Backup timeout must be positive");
        connection.databaseAddress();
        Files.createDirectories(directory);
        var temporary = Files.createTempDirectory(directory, ".pending-");
        try {
            var schema = connection.schemaFingerprints();
            var archive = temporary.resolve("archive.dump");
            var process = new ProcessBuilder(executable, "--format=custom", "--no-password", "--file", archive.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            connection.configure(process);
            var child = process.start();
            try {
                if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                    child.destroyForcibly();
                    child.waitFor();
                    throw new IOException("Backup timed out");
                }
                if (child.exitValue() != 0 || !Files.isRegularFile(archive) || Files.size(archive) == 0) {
                    throw new IOException("pg_dump failed to create a backup");
                }
            } catch (InterruptedException e) {
                child.destroyForcibly();
                while (child.isAlive()) {
                    try { child.waitFor(); } catch (InterruptedException ignored) { /* Preserve interruption below. */ }
                }
                Thread.currentThread().interrupt();
                throw e;
            }
            if (!schema.equals(connection.schemaFingerprints())) throw new IOException("Database schema changed during backup");
            var manifest = new Properties();
            manifest.setProperty("sizeBytes", Long.toString(Files.size(archive)));
            manifest.setProperty("formatVersion", FORMAT);
            manifest.setProperty("schemaVersion", schema.history());
            manifest.setProperty("catalogVersion", schema.catalog());
            manifest.setProperty("sha256", fileHash(archive));
            if (!signingKey.isBlank()) manifest.setProperty("hmacSha256", BundleAuthentication.sign(manifest, signingKey));
            var bundle = temporary.resolve("bundle.zip");
            try (var zip = new java.util.zip.ZipOutputStream(Files.newOutputStream(bundle))) {
                zip.putNextEntry(new java.util.zip.ZipEntry("manifest.properties"));
                manifest.store(zip, null);
                zip.closeEntry();
                zip.putNextEntry(new java.util.zip.ZipEntry("archive.dump"));
                Files.copy(archive, zip);
                zip.closeEntry();
            }
            try (var in = Files.newInputStream(bundle)) {
                return storage.store(in, FORMAT, schema.history());
            }
        } finally {
            BackupTemporaryFiles.cleanup(temporary);
        }
    }

    static String databaseName(URI connection) { return DatabaseConnection.databaseName(connection); }

    private static String fileHash(Path path) throws IOException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(path)) {
                var buffer = new byte[8192];
                for (int read; (read = in.read(buffer)) != -1;) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    public List<BackupMetadata> list() throws IOException { return storage.list(); }
    public Optional<java.io.InputStream> archive(UUID id) throws IOException { return storage.open(id); }
}
