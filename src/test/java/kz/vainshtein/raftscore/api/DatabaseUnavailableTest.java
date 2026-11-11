package kz.vainshtein.raftscore.api;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.TransactionSystemException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatabaseUnavailableTest {
    @Test
    void detectsConnectionFailuresWrappedAtTransactionCommit() throws Exception {
        var failure = new TransactionSystemException("commit", new SQLException("secret SQL", "08006"));
        var response = new ApiExceptionHandler().databaseUnavailable(failure);
        assertThat(response.getStatusCode().value()).isEqualTo(503);
        assertThat(response.getBody()).isEqualTo(ApiError.of("database.unavailable"));
        assertThat(DatabaseUnavailable.isCausedBy(new CannotGetJdbcConnectionException("password"))).isTrue();
        for (var state : new String[] {"57P01", "57P02", "57P03"}) {
            assertThat(DatabaseUnavailable.isCausedBy(new SQLException("server", state))).isTrue();
        }
    }

    @Test
    void leavesIntegrityAndOtherTransactionFailuresUnchanged() {
        var integrity = new DataIntegrityViolationException("constraint", new SQLException("constraint", "23505"));
        var transaction = new TransactionSystemException("other failure");
        assertThat(DatabaseUnavailable.isCausedBy(integrity)).isFalse();
        assertThatThrownBy(() -> new ApiExceptionHandler().databaseUnavailable(transaction)).isSameAs(transaction);
    }
}
