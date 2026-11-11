package kz.vainshtein.raftscore.backup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;

/** Validates a bundle before allowing the configured PostgreSQL tool to modify the database. */
@Service
public class RestoreService {
    private static final long MAX_ARCHIVE = 2L * 1024 * 1024 * 1024;
    private static final long MAX_MANIFEST = 16 * 1024;
    private final Path directory;
    private final BackupStorage storage;
    private final DatabaseConnection connection;
    private final String executable, signingKey;
    private final Duration timeout;

    public RestoreService(@Value("${raftscore.backup.directory:./backups}") String directory,
                          @Value("${raftscore.backup.pg-restore:}") String executable,
                          @Value("${spring.datasource.url}") String url,
                          @Value("${spring.datasource.username}") String username,
                          @Value("${spring.datasource.password:}") String password,
                          @Value("${raftscore.backup.timeout:PT10M}") Duration timeout,
                          @Value("${raftscore.backup.signing-key:}") String signingKey) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.storage = new FileBackupStorage(this.directory);
        this.connection = new DatabaseConnection(url, username, password, timeout);
        this.executable = executable;
        this.timeout = timeout;
        this.signingKey = signingKey;
    }

    public void restore(UUID id) throws IOException, InterruptedException {
        try (var input = storage.open(id).orElseThrow(() -> new IOException("Backup not found"))) {
            restore(input, false);
        }
    }

    /** Caller owns the uploaded stream. */
    public void restoreUpload(InputStream input) throws IOException, InterruptedException {
        restore(input, true);
    }

    private synchronized void restore(InputStream input, boolean upload) throws IOException, InterruptedException {
        if (executable.isBlank() || !Path.of(executable).isAbsolute() || !Files.isRegularFile(Path.of(executable)))
            throw new IOException("A trusted absolute pg_restore executable must be configured");
        if (timeout.isNegative() || timeout.isZero()) throw new IOException("Restore timeout must be positive");
        connection.databaseAddress();
        Files.createDirectories(directory);
        var temporary = Files.createTempDirectory(directory, ".restore-");
        try {
            var archive = temporary.resolve("archive.dump");
            var manifest = extract(input, archive);
            validate(manifest, archive, upload);
            runRestore(archive);
        } finally { BackupTemporaryFiles.cleanup(temporary); }
    }

    private Properties extract(InputStream input, Path archive) throws IOException {
        var manifest = new Properties();
        var seen = new HashSet<String>();
        try (var zip = new ZipInputStream(new FilterInputStream(input) { @Override public void close() {} })) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                var name = entry.getName();
                if (entry.isDirectory() || !seen.add(name) || !(name.equals("manifest.properties") || name.equals("archive.dump")))
                    throw new IOException("Invalid backup bundle entries");
                if (name.equals("manifest.properties")) {
                    var bytes = new ByteArrayOutputStream();
                    copyBounded(zip, bytes, MAX_MANIFEST);
                    try { manifest.load(new ByteArrayInputStream(bytes.toByteArray())); }
                    catch (IllegalArgumentException e) { throw new IOException("Invalid backup manifest"); }
                } else try (var output = Files.newOutputStream(archive)) { copyBounded(zip, output, MAX_ARCHIVE); }
                zip.closeEntry();
            }
        }
        if (seen.size() != 2) throw new IOException("Invalid backup bundle entries");
        return manifest;
    }

    private static void copyBounded(InputStream input, OutputStream output, long limit) throws IOException {
        var buffer = new byte[8192];
        long total = 0;
        for (int count; (count = input.read(buffer)) != -1;) {
            total += count;
            if (total > limit) throw new IOException("Backup bundle exceeds size limit");
            output.write(buffer, 0, count);
        }
    }

    private void validate(Properties manifest, Path archive, boolean upload) throws IOException {
        var schema = connection.schemaFingerprints();
        if (!BackupService.FORMAT.equals(manifest.getProperty("formatVersion")) ||
                !schema.history().equals(manifest.getProperty("schemaVersion")) ||
                !schema.catalog().equals(manifest.getProperty("catalogVersion")))
            throw new IOException("Incompatible backup format or schema");
        try {
            if (Long.parseLong(manifest.getProperty("sizeBytes")) != Files.size(archive) || Files.size(archive) == 0)
                throw new IOException("Invalid backup archive size");
            var digest = MessageDigest.getInstance("SHA-256");
            try (var in = Files.newInputStream(archive)) {
                var buffer = new byte[8192];
                for (int n; (n = in.read(buffer)) != -1;) digest.update(buffer, 0, n);
            }
            if (!MessageDigest.isEqual(digest.digest(), HexFormat.of().parseHex(manifest.getProperty("sha256"))))
                throw new IOException("Invalid backup archive digest");
        } catch (NumberFormatException | NullPointerException e) { throw new IOException("Invalid backup manifest"); }
        catch (IllegalArgumentException e) { throw new IOException("Invalid backup manifest"); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        if (upload && !BundleAuthentication.verify(manifest, signingKey)) throw new IOException("Backup authentication failed");
    }

    private String databaseArgument() throws IOException {
        var name = DatabaseConnection.databaseName(connection.databaseAddress());

        return "dbname='" + name.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private void runRestore(Path archive) throws IOException, InterruptedException {
        var process = new ProcessBuilder(executable, "--single-transaction", "--exit-on-error", "--clean", "--if-exists", "--no-owner", "--no-privileges", "--no-password", "--dbname", databaseArgument(), archive.toString())
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        connection.configure(process);
        var child = process.start();
        try {
            if (!child.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                child.destroyForcibly();
                child.waitFor();
                throw new IOException("Restore timed out");
            }
            if (child.exitValue() != 0) throw new IOException("pg_restore failed");
        } catch (InterruptedException e) {
            child.destroyForcibly();
            while (child.isAlive()) { try { child.waitFor(); } catch (InterruptedException ignored) {} }
            Thread.currentThread().interrupt();
            throw e;
        }
    }
}
