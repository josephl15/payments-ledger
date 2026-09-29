package dev.joseph.ledger;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Test-only corruption helper. Runs work in ONE transaction with {@code session_replication_role = replica},
 * which switches off ordinary triggers (so the append-only triggers do not fire) and foreign-key enforcement for
 * that transaction only. CHECK constraints stay enforced.
 *
 * <p>It uses {@code SET LOCAL}, which reverts automatically at commit or rollback, so the pooled connection goes
 * back to the pool in its normal state. A session-level {@code SET} would leak into every later test that borrows
 * the same connection. The production triggers are never changed; this only works because the Testcontainers
 * database user is a superuser (a normal application role would be refused).
 *
 * <p>Purpose: later phases need to inject bad data (a lone entry, a wrong cached balance) to prove that
 * reconciliation detects it, and then remove it again. Deleting ledger entries is impossible any other way.
 */
final class ReplicaRole {

    private ReplicaRole() {}

    /** Work to run inside the replica-role transaction. */
    @FunctionalInterface
    interface SqlWork {
        void run(Connection connection) throws SQLException;
    }

    static void asReplica(DataSource dataSource, SqlWork work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("SET LOCAL session_replication_role = replica");
                }
                work.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                // The pool hands this connection out again, so put it back in its default mode.
                connection.setAutoCommit(true);
            }
        }
    }
}
