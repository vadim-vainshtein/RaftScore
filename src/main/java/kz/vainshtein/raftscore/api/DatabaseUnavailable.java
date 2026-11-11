package kz.vainshtein.raftscore.api;

import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLTransientConnectionException;
import java.sql.SQLException;
import org.hibernate.exception.JDBCConnectionException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;

/** Identifies connection failures without treating other database errors as outages. */
public final class DatabaseUnavailable {
    private DatabaseUnavailable() {
    }

    public static boolean isCausedBy(Throwable failure) {
        for (var cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof CannotGetJdbcConnectionException || cause instanceof JDBCConnectionException
                    || cause instanceof SQLTransientConnectionException || cause instanceof SQLNonTransientConnectionException) {
                return true;
            }
            if (cause instanceof SQLException sql && sql.getSQLState() != null && (sql.getSQLState().startsWith("08") || sql.getSQLState().equals("57P01")
                    || sql.getSQLState().equals("57P02") || sql.getSQLState().equals("57P03"))) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

    public static ApiError error() {
        return ApiError.of("database.unavailable");
    }
}
