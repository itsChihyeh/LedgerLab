package dev.ledgerlab.sim;

import java.nio.file.Path;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

final class TestDatabase {
    private TestDatabase() {}

    static PostgreSQLContainer start() {
        var db = new PostgreSQLContainer("postgres:18")
                .withDatabaseName("sim_test")
                .withUsername("postgres")
                .withPassword("test-admin")
                .withEnv("LEDGERLAB_MIGRATOR_PASSWORD", "test-migrator")
                .withEnv("LEDGERLAB_APP_PASSWORD", "test-app")
                .withEnv("LEDGERLAB_READONLY_PASSWORD", "test-readonly")
                .withEnv("LEDGERLAB_SIMULATOR_PASSWORD", "test-simulator")
                .withCopyFileToContainer(
                        MountableFile.forHostPath(Path.of("../docker/postgres/initdb/01-roles.sh")),
                        "/docker-entrypoint-initdb.d/01-roles.sh");
        db.start();
        // Only the permission test needs the real ledger view; no main-application Java dependency.
        Flyway.configure()
                .dataSource(db.getJdbcUrl(), "migrator", "test-migrator")
                .defaultSchema("flyway")
                .schemas("flyway")
                .locations("filesystem:../src/main/resources/db/migration")
                .load()
                .migrate();
        return db;
    }

    static DataSource role(String url, String role) {
        return new DriverManagerDataSource(url, role, "test-" + role);
    }
}
