package kz.vainshtein.raftscore.backup;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Properties;

final class DatabaseConnection {
    private static final String CATALOG_QUERY = """
            SELECT object_name FROM (
              SELECT 'schema:' || quote_ident(n.nspname) AS object_name
                FROM pg_namespace n
               WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
              UNION ALL
              SELECT 'relation:' || c.relkind::text || ':' || quote_ident(n.nspname) || '.' || quote_ident(c.relname)
                FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
               WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
                 AND c.relkind IN ('r', 'p', 'S', 'v', 'm', 'f', 'i', 'I', 'c')
              UNION ALL
              SELECT 'routine:' || p.prokind::text || ':' || quote_ident(n.nspname) || '.' || quote_ident(p.proname)
                     || '(' || pg_get_function_identity_arguments(p.oid) || ')'
                FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
              UNION ALL
              SELECT 'extension:' || quote_ident(e.extname) || ':' || e.extversion
                FROM pg_extension e
              UNION ALL
              SELECT 'operator:' || quote_ident(n.nspname) || '.' || o.oprname || ':'
                     || coalesce(format_type(o.oprleft, NULL), '') || ':' || coalesce(format_type(o.oprright, NULL), '')
                FROM pg_operator o JOIN pg_namespace n ON n.oid = o.oprnamespace
               WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
              UNION ALL
              SELECT 'type:' || t.typtype::text || ':' || quote_ident(n.nspname) || '.' || quote_ident(t.typname)
                FROM pg_type t JOIN pg_namespace n ON n.oid = t.typnamespace
               WHERE n.nspname !~ '^pg_' AND n.nspname <> 'information_schema'
                 AND t.typtype IN ('d', 'e', 'r', 'm')
            ) objects ORDER BY object_name COLLATE "C"
            """;
    private final String url, username, password;
    private final Duration timeout;

    DatabaseConnection(String url, String username, String password, Duration timeout) {
        this.url = url;
        this.username = username;
        this.password = password;
        this.timeout = timeout;
    }

    void configure(ProcessBuilder process) throws IOException {
        var address = databaseAddress();
        var env = process.environment();
        env.keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("PG"));
        env.put("PGHOST", address.getHost());
        env.put("PGPORT", Integer.toString(address.getPort() < 0 ? 5432 : address.getPort()));
        env.put("PGDATABASE", databaseName(address));
        env.put("PGUSER", username);
        env.put("PGPASSWORD", password);
    }

    URI databaseAddress() throws IOException {
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

    record SchemaFingerprints(String history, String catalog) {}

    SchemaFingerprints schemaFingerprints() throws IOException {
        int seconds = timeoutSeconds();
        var properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectTimeout", Integer.toString(seconds));
        properties.setProperty("socketTimeout", Integer.toString(seconds));
        properties.setProperty("cancelSignalTimeout", Integer.toString(seconds));
        try (var connection = DriverManager.getConnection(url, properties)) {
            connection.setAutoCommit(false);
            connection.setTransactionIsolation(java.sql.Connection.TRANSACTION_REPEATABLE_READ);
            try (var statement = connection.createStatement()) {
                statement.setQueryTimeout(seconds);
                var history = hashRows(statement, "SELECT id, author, filename, md5sum FROM databasechangelog ORDER BY orderexecuted", 4, true);
                var catalog = hashRows(statement, CATALOG_QUERY, 1, false);
                return new SchemaFingerprints("liquibase-sha256:" + history, "catalog-sha256-v1:" + catalog);
            }
        } catch (java.sql.SQLException e) {
            throw new IOException("Could not read database schema history or catalog");
        }
    }

    private int timeoutSeconds() throws IOException {
        if (timeout.isNegative() || timeout.isZero()) throw new IOException("Backup timeout must be positive");
        long seconds = timeout.getSeconds();
        if (timeout.getNano() != 0 && seconds < Integer.MAX_VALUE) seconds++;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, seconds));
    }

    private static String hashRows(java.sql.Statement statement, String query, int columns, boolean requireRows) throws IOException, java.sql.SQLException {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            var count = 0;
            try (var rows = statement.executeQuery(query)) {
                while (rows.next()) {
                    count++;
                    for (var index = 1; index <= columns; index++) {
                        var value = rows.getString(index);
                        if (value == null) throw new IOException("Incomplete database schema information");
                        var bytes = value.getBytes(StandardCharsets.UTF_8);
                        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
                        digest.update(bytes);
                    }
                }
            }
            if (requireRows && count == 0) throw new IOException("Empty Liquibase schema history");
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
