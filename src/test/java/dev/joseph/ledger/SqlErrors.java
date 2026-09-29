package dev.joseph.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.SQLException;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.postgresql.util.PSQLException;

/**
 * Test helper: asserts that a SQL statement is rejected by the database, and by which rule.
 *
 * <p>Spring wraps most database errors in its own exception types, but a trigger's RAISE EXCEPTION (SQLSTATE
 * P0001) is not translated to a specific class, so tests look at the underlying {@link SQLException} instead:
 * its SQLSTATE (23505 unique, 23514 check, 23503 foreign key, P0001 trigger) and, for constraint violations,
 * the constraint name that PostgreSQL reports.
 */
final class SqlErrors {

    private SqlErrors() {}

    /** Walks the cause chain to the first {@link SQLException}. */
    static SQLException sqlException(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof SQLException se) {
                return se;
            }
        }
        throw new AssertionError("no SQLException in the cause chain", failure);
    }

    /** The statement must fail with this SQLSTATE and, if {@code constraint} is not null, this constraint name. */
    static void assertRejected(ThrowingCallable statement, String sqlState, String constraint) {
        Throwable failure = catchThrowable(statement);
        assertThat(failure).as("the database should have rejected the statement").isNotNull();
        SQLException se = sqlException(failure);
        assertThat(se.getSQLState()).as("SQLSTATE of: %s", se.getMessage()).isEqualTo(sqlState);
        if (constraint != null) {
            assertThat(((PSQLException) se).getServerErrorMessage().getConstraint())
                    .as("violated constraint")
                    .isEqualTo(constraint);
        }
    }
}
