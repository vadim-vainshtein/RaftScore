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
    private final String url;
    private final String username;
    private final String password;
    private final Duration timeout;

    public BackupService(@Value("${raftscore.backup.directory:./backups}") String directory,
                         @Value("${raftscore.backup.pg-dump:}") String executable,
                         @Value("${spring.datasource.url}") String url,
                         @Value("${spring.datasource.username}") String username,
                         @Value("${spring.datasource.password:}") String password,
                         @Value("${raftscore.backup.timeout:PT10M}") Duration timeout) {
        this.directory = Path.of(directory).toAbsolutePath().normalize();
        this.storage = new FileBackupStorage(this.directory);
        this.executable = executable;
        this.url = url;
        this.username = username;
        this.password = password;
        this.timeout = timeout;
    }

    public BackupMetadata create() throws IOException, InterruptedException {
        if (executable.isBlank() || !Path.of(executable).isAbsolute() || !Files.isRegularFile(Path.of(executable))) {
            throw new IOException("A trusted absolute pg_dump executable must be configured");
        }
        if (timeout.isNegative() || timeout.isZero()) throw new IOException("Backup timeout must be positive");
        var connection = databaseAddress();
        Files.createDirectories(directory);
        var temporary = Files.createTempDirectory(directory, ".pending-");
        try {
            var schema = schemaFingerprint();
            var archive = temporary.resolve("archive.dump");
            var process = new ProcessBuilder(executable, "--format=custom", "--no-password", "--file", archive.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
            var env = process.environment();
            env.keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("PG"));
            env.put("PGHOST", connection.getHost());
            env.put("PGPORT", Integer.toString(connection.getPort() < 0 ? 5432 : connection.getPort()));
            env.put("PGDATABASE", databaseName(connection));
            env.put("PGUSER", username);
            env.put("PGPASSWORD", password);
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
            if (!schema.equals(schemaFingerprint())) throw new IOException("Database schema changed during backup");
            var manifest = new Properties();
            manifest.setProperty("sizeBytes", Long.toString(Files.size(archive)));
            manifest.setProperty("formatVersion", FORMAT);
            manifest.setProperty("schemaVersion", schema);
            manifest.setProperty("sha256", fileHash(archive));
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
                return storage.store(in, FORMAT, schema);
            }
        } finally {
            BackupTemporaryFiles.cleanup(temporary);
        }
    }

    private URI databaseAddress() throws IOException {
        try {
            if (!url.startsWith("jdbc:postgresql://")) throw new IllegalArgumentException();
            var uri = URI.create(url.substring(5));
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                    || uri.getPath() == null || uri.getPath().length() < 2 || uri.getPath().substring(1).contains("/")) throw new IllegalArgumentException();
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IOException("Backup requires a simple PostgreSQL JDBC URL without options");
        }
    }

    static String databaseName(URI connection) {
        return java.net.URLDecoder.decode(connection.getRawPath().substring(1), StandardCharsets.UTF_8);
    }
    private String schemaFingerprint() throws IOException {
        try (var connection = java.sql.DriverManager.getConnection(url, username, password);
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT id, author, filename, md5sum FROM databasechangelog ORDER BY orderexecuted")) {
            var digest = MessageDigest.getInstance("SHA-256");
            var count = 0;
            while (rows.next()) {
                count++;
                for (var index = 1; index <= 4; index++) {
                    var value = rows.getString(index);
                    if (value == null) throw new IOException("Incomplete Liquibase schema history");
                    var bytes = value.getBytes(StandardCharsets.UTF_8);
                    digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                    digest.update(bytes);
                }
            }
            if (count == 0) throw new IOException("Empty Liquibase schema history");
            return "liquibase-sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (java.sql.SQLException e) {
            throw new IOException("Could not read database schema history");
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

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
