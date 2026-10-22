package dev.ledgerlab.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;

/**
 * Direct SQL as a given database role, with explicit transactions. Used by the tests that prove the database
 * itself refuses an operation (AGENTS.md: permission tests connect directly as {@code app} / {@code readonly}).
 */
public final class Sql {

    private Sql() {}

    @FunctionalInterface
    public interface Work {
        void run(Connection connection) throws SQLException;
    }

    /** Runs the work in one transaction and commits it; any failure, including at commit, propagates. */
    public static void inTransaction(DataSource dataSource, Work work) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                work.run(connection);
                connection.commit();
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            }
        }
    }

    /** Runs the statements in one transaction and commits. */
    public static void execute(DataSource dataSource, String... statements) throws SQLException {
        inTransaction(dataSource, connection -> {
            try (Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
            }
        });
    }

    /** Asserts that running the statements in one transaction fails with the given SQLSTATE. */
    public static void assertRejected(String sqlState, DataSource dataSource, String... statements) {
        assertSqlState(sqlState, catchThrowable(() -> execute(dataSource, statements)));
    }

    /** Asserts that the work fails, while running or at commit, with the given SQLSTATE. */
    public static void assertRejected(String sqlState, DataSource dataSource, Work work) {
        assertSqlState(sqlState, catchThrowable(() -> inTransaction(dataSource, work)));
    }

    /** Finds the SQLException in the cause chain and checks its SQLSTATE. */
    public static void assertSqlState(String sqlState, Throwable thrown) {
        assertThat(thrown).as("expected the database to refuse with SQLSTATE %s", sqlState).isNotNull();
        Throwable cause = thrown;
        while (cause != null && !(cause instanceof SQLException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("no SQLException in the cause chain of %s", thrown).isInstanceOf(SQLException.class);
        assertThat(((SQLException) cause).getSQLState())
                .as("SQLSTATE of: %s", cause.getMessage())
                .isEqualTo(sqlState);
    }

    public static long queryLong(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement();
                var rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).as("no row from %s", sql).isTrue();
            return rs.getLong(1);
        }
    }
}
