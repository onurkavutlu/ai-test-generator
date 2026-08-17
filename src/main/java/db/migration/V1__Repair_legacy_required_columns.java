package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Veritabanı daha önce {@code hibernate.ddl-auto=update} ile oluşturulduğunda,
 * eski satırlar nedeniyle yeni NOT NULL kolonları eklenemiyordu. Flyway bu
 * migrasyonu Hibernate'den önce çalıştırır: kolonu varsayılanla ekler, olası
 * null'ları doldurur ve son olarak zorunluluk kuralını koyar.
 *
 * <p>Yeni bir kurulumda tablolar henüz Hibernate tarafından oluşturulmadığı
 * için migration no-op'tur. Böylece mevcut bootstrap davranışı değişmez.</p>
 */
public class V1__Repair_legacy_required_columns extends BaseJavaMigration {

    @Override
    public void migrate(Context context) throws SQLException {
        Connection connection = context.getConnection();
        repairRequiredColumn(connection, "generated_test_cases", "deterministic", "BOOLEAN", "FALSE");
        repairRequiredColumn(connection, "generated_test_cases", "validation_attempts", "INTEGER", "0");
        repairRequiredColumn(connection, "test_generation_requests", "agents_enabled", "BOOLEAN", "TRUE");
    }

    private void repairRequiredColumn(Connection connection, String table, String column,
                                      String sqlType, String defaultValue) throws SQLException {
        if (!tableExists(connection, table)) {
            return;
        }

        if (!columnExists(connection, table, column)) {
            execute(connection, "ALTER TABLE " + table + " ADD COLUMN " + column
                    + " " + sqlType + " DEFAULT " + defaultValue);
        }
        execute(connection, "ALTER TABLE " + table + " ALTER COLUMN " + column
                + " SET DEFAULT " + defaultValue);

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + table + " SET " + column + " = " + defaultValue
                        + " WHERE " + column + " IS NULL")) {
            update.executeUpdate();
        }
        execute(connection, "ALTER TABLE " + table + " ALTER COLUMN " + column + " SET NOT NULL");
    }

    private boolean tableExists(Connection connection, String table) throws SQLException {
        return metadataContains(connection, null, table);
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        return metadataContains(connection, table, column);
    }

    private boolean metadataContains(Connection connection, String table, String name) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String schema = connection.getSchema();
        try (ResultSet resultSet = table == null
                ? metadata.getTables(null, schema, "%", new String[]{"TABLE"})
                : metadata.getColumns(null, schema, "%", "%")) {
            while (resultSet.next()) {
                String actualTable = resultSet.getString("TABLE_NAME");
                String actualName = table == null ? actualTable : resultSet.getString("COLUMN_NAME");
                if (actualTable != null && actualName != null
                        && actualTable.equalsIgnoreCase(table == null ? name : table)
                        && actualName.equalsIgnoreCase(name)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
