package dev.ledgerlab.testsupport;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * One real PostgreSQL for the whole test run (Testcontainers), with the same role bootstrap script as Docker
 * Compose. Every test gets its own database copied from a template, so the append-only ledger never has to be
 * cleaned between tests.
 *
 * <ul>
 *   <li>{@code ledgerlab}: created by the image and the bootstrap script (roles, sim_* schemas); not migrated.
 *   <li>{@code ledgerlab_migrated}: a copy of it with every Flyway migration applied, used as the template for
 *       {@link #freshMigrated()}.
 * </ul>
 *
 * <p>Works from JUnit Jupiter and from jqwik alike, because it is a plain static singleton.
 */
public final class TestDatabase {

    // Test-only credentials for a throwaway container; never used anywhere else.
    private static final String SUPERUSER_PASSWORD = "test-superuser";
    public static final String MIGRATOR_PASSWORD = "test-migrator";
    public static final String APP_PASSWORD = "test-app";
    public static final String READONLY_PASSWORD = "test-readonly";
    public static final String SIMULATOR_PASSWORD = "test-simulator";

    private static final String BLANK = "ledgerlab";
    private static final String MIGRATED = "ledgerlab_migrated";

    private static final AtomicInteger COUNTER = new AtomicInteger();
    private static PostgreSQLContainer container;

    private TestDatabase() {}

    /** A new database with all migrations applied. */
    public static Handle freshMigrated() {
        return copyOf(started(), MIGRATED);
    }

    /** A new database as the bootstrap script leaves it, before any migration (for the Spring Boot test). */
    public static Handle freshBlank() {
        return copyOf(started(), BLANK);
    }

    private static synchronized PostgreSQLContainer started() {
        if (container == null) {
            PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18")
                    .withDatabaseName(BLANK)
                    .withUsername("postgres")
                    .withPassword(SUPERUSER_PASSWORD)
                    .withEnv("LEDGERLAB_MIGRATOR_PASSWORD", MIGRATOR_PASSWORD)
                    .withEnv("LEDGERLAB_APP_PASSWORD", APP_PASSWORD)
                    .withEnv("LEDGERLAB_READONLY_PASSWORD", READONLY_PASSWORD)
                    .withEnv("LEDGERLAB_SIMULATOR_PASSWORD", SIMULATOR_PASSWORD)
                    .withCopyFileToContainer(
                            MountableFile.forHostPath(Path.of("docker/postgres/initdb/01-roles.sh")),
                            "/docker-entrypoint-initdb.d/01-roles.sh");
            postgres.start();
            container = postgres;

            Handle migrated = createDatabase(postgres, MIGRATED, BLANK);
            Flyway.configure()
                    .dataSource(migrated.url(), "migrator", MIGRATOR_PASSWORD)
                    .defaultSchema("flyway")
                    .schemas("flyway")
                    .load()
                    .migrate();
        }
        return container;
    }

    private static Handle copyOf(PostgreSQLContainer postgres, String template) {
        return createDatabase(postgres, "t" + COUNTER.incrementAndGet() + "_" + template, template);
    }

    private static Handle createDatabase(PostgreSQLContainer postgres, String name, String template) {
        JdbcClient admin = JdbcClient.create(dataSource(postgres.getJdbcUrl(), "postgres", SUPERUSER_PASSWORD));
        admin.sql("CREATE DATABASE " + name + " TEMPLATE " + template + " OWNER migrator")
                .update();
        // Database-level privileges are not copied from the template; repeat what 01-roles.sh grants.
        admin.sql("REVOKE ALL ON DATABASE " + name + " FROM PUBLIC").update();
        admin.sql("GRANT CONNECT ON DATABASE " + name + " TO app, readonly, simulator")
                .update();
        admin.sql("GRANT CONNECT, CREATE, TEMPORARY ON DATABASE " + name + " TO migrator")
                .update();
        String url = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + name;
        return new Handle(name, url);
    }

    private static DataSource dataSource(String url, String user, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(url, user, password);
        dataSource.setDriverClassName("org.postgresql.Driver");
        return dataSource;
    }

    /** One test database and a connection source for each role. Connections open per use and close after it. */
    public record Handle(String name, String url) {

        public DataSource app() {
            return dataSource(url, "app", APP_PASSWORD);
        }

        public DataSource readonly() {
            return dataSource(url, "readonly", READONLY_PASSWORD);
        }

        public DataSource migrator() {
            return dataSource(url, "migrator", MIGRATOR_PASSWORD);
        }

        public DataSource simulator() {
            return dataSource(url, "simulator", SIMULATOR_PASSWORD);
        }

        public DataSource superuser() {
            return dataSource(url, "postgres", SUPERUSER_PASSWORD);
        }

        /** Drops the database; for tests that create many (property tries), so the container's disk stays small. */
        public void drop() {
            JdbcClient.create(dataSource(container.getJdbcUrl(), "postgres", SUPERUSER_PASSWORD))
                    .sql("DROP DATABASE " + name + " WITH (FORCE)")
                    .update();
        }
    }
}
