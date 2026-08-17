package db.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class V1__Repair_legacy_required_columnsTest {

    @Test
    void backfillsLegacyRowsBeforeMakingNewColumnsRequired() throws Exception {
        String url = "jdbc:h2:mem:flyway_legacy_repair;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        try (Connection connection = DriverManager.getConnection(url, "sa", "")) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE generated_test_cases (id VARCHAR(36) PRIMARY KEY)");
                statement.execute("CREATE TABLE test_generation_requests (id VARCHAR(36) PRIMARY KEY)");
                statement.execute("INSERT INTO generated_test_cases (id) VALUES ('case-1')");
                statement.execute("INSERT INTO test_generation_requests (id) VALUES ('request-1')");
            }
        }

        Flyway flyway = Flyway.configure()
                .dataSource(url, "sa", "")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load();

        assertEquals(1, flyway.migrate().migrationsExecuted);

        try (Connection connection = DriverManager.getConnection(url, "sa", "");
             Statement statement = connection.createStatement()) {
            try (ResultSet cases = statement.executeQuery(
                    "SELECT deterministic, validation_attempts FROM generated_test_cases WHERE id = 'case-1'")) {
                assertTrue(cases.next());
                assertFalse(cases.getBoolean("deterministic"));
                assertEquals(0, cases.getInt("validation_attempts"));
            }
            try (ResultSet requests = statement.executeQuery(
                    "SELECT agents_enabled FROM test_generation_requests WHERE id = 'request-1'")) {
                assertTrue(requests.next());
                assertTrue(requests.getBoolean("agents_enabled"));
            }
        }

        assertEquals(0, flyway.migrate().migrationsExecuted);
    }
}
