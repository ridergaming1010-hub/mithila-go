package mithila.mith.migration;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "mithila.database.migration.enabled", havingValue = "true")
public class H2ToPostgresMigration implements ApplicationRunner {
    private static final Logger logger = LoggerFactory.getLogger(H2ToPostgresMigration.class);
    private static final List<String> TABLES = List.of(
            "user_accounts",
            "ride_bookings",
            "ride_offers",
            "ride_complaints",
            "ride_notifications");

    private final DataSource targetDataSource;
    private final String sourceUrl;
    private final String sourceUsername;
    private final String sourcePassword;

    public H2ToPostgresMigration(
            DataSource targetDataSource,
            @Value("${mithila.database.migration.source-url}") String sourceUrl,
            @Value("${mithila.database.migration.source-username}") String sourceUsername,
            @Value("${mithila.database.migration.source-password}") String sourcePassword) {
        this.targetDataSource = targetDataSource;
        this.sourceUrl = sourceUrl;
        this.sourceUsername = sourceUsername;
        this.sourcePassword = sourcePassword;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!sourceUrl.startsWith("jdbc:h2:")) {
            throw new IllegalArgumentException("The migration source must be an H2 JDBC URL.");
        }

        try (Connection source = DriverManager.getConnection(sourceUrl, sourceUsername, sourcePassword);
                Connection target = targetDataSource.getConnection()) {
            String targetProduct = target.getMetaData().getDatabaseProductName();
            if (!targetProduct.toLowerCase(Locale.ROOT).contains("postgresql")) {
                throw new IllegalArgumentException("The migration destination must be PostgreSQL.");
            }
            Map<String, Integer> copiedRows = copyTables(source, target, TABLES, true);
            logger.info("H2 to PostgreSQL migration completed. Rows copied by table: {}", copiedRows);
        }
    }

    static Map<String, Integer> copyTables(
            Connection source, Connection target, List<String> tables, boolean resetSequences) throws SQLException {
        Map<String, TableInfo> sourceTables = tableInfo(source, tables);
        Map<String, TableInfo> targetTables = tableInfo(target, tables);
        Map<String, Integer> copiedRows = new LinkedHashMap<>();

        for (String table : tables) {
            TableInfo targetTable = targetTables.get(table);
            if (targetTable != null && countRows(target, targetTable) != 0) {
                throw new IllegalStateException(
                        "Destination table " + table + " is not empty. Migration stopped without changing it.");
            }
        }

        boolean originalAutoCommit = target.getAutoCommit();
        target.setAutoCommit(false);
        try {
            for (String table : tables) {
                TableInfo sourceTable = sourceTables.get(table);
                TableInfo targetTable = targetTables.get(table);
                if (sourceTable == null) {
                    copiedRows.put(table, 0);
                    continue;
                }
                if (targetTable == null) {
                    throw new IllegalStateException("Destination table " + table + " does not exist.");
                }

                List<String> columns = sourceTable.columns().stream()
                        .filter(column -> targetTable.columnsByName().containsKey(column.toLowerCase(Locale.ROOT)))
                        .toList();
                if (columns.size() != sourceTable.columns().size()) {
                    throw new IllegalStateException(
                            "Destination table " + table + " is missing one or more source columns.");
                }
                if (columns.stream().noneMatch(column -> "id".equalsIgnoreCase(column))) {
                    throw new IllegalStateException("Table " + table + " is missing its id column.");
                }

                int count = copyTable(source, target, sourceTable, targetTable, columns);
                copiedRows.put(table, count);
            }
            if (resetSequences) {
                resetPostgresSequences(target, targetTables, tables);
            }
            target.commit();
        } catch (SQLException | RuntimeException exception) {
            target.rollback();
            throw exception;
        } finally {
            target.setAutoCommit(originalAutoCommit);
        }

        return Map.copyOf(copiedRows);
    }

    private static int copyTable(
            Connection source, Connection target, TableInfo sourceTable, TableInfo targetTable, List<String> columns)
            throws SQLException {
        String selectSql = "SELECT " + quoteColumns(source, sourceTable, columns)
                + " FROM " + quote(source, sourceTable.name());
        String insertSql = "INSERT INTO " + quote(target, targetTable.name())
                + " (" + quoteColumns(target, targetTable, columns) + ") VALUES ("
                + String.join(", ", java.util.Collections.nCopies(columns.size(), "?")) + ")";
        int count = 0;
        try (Statement query = source.createStatement();
                ResultSet rows = query.executeQuery(selectSql);
                PreparedStatement insert = target.prepareStatement(insertSql)) {
            while (rows.next()) {
                for (int index = 0; index < columns.size(); index++) {
                    insert.setObject(index + 1, rows.getObject(sourceTable.columnsByName()
                            .get(columns.get(index).toLowerCase(Locale.ROOT))));
                }
                insert.addBatch();
                count++;
                if (count % 500 == 0) insert.executeBatch();
            }
            insert.executeBatch();
        }
        return count;
    }

    private static Map<String, TableInfo> tableInfo(Connection connection, List<String> requestedTables)
            throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        Map<String, TableInfo> found = new HashMap<>();
        try (ResultSet tables = metadata.getTables(connection.getCatalog(), null, null, new String[] {"TABLE"})) {
            while (tables.next()) {
                String name = tables.getString("TABLE_NAME");
                String normalized = name.toLowerCase(Locale.ROOT);
                if (!requestedTables.contains(normalized)) continue;

                List<String> columns = new ArrayList<>();
                try (ResultSet columnRows = metadata.getColumns(
                        connection.getCatalog(), tables.getString("TABLE_SCHEM"), name, null)) {
                    while (columnRows.next()) columns.add(columnRows.getString("COLUMN_NAME"));
                }
                Map<String, String> columnsByName = new HashMap<>();
                for (String column : columns) columnsByName.put(column.toLowerCase(Locale.ROOT), column);
                found.put(normalized, new TableInfo(name, columns, columnsByName));
            }
        }
        return found;
    }

    private static long countRows(Connection connection, TableInfo table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT COUNT(*) FROM " + quote(connection, table.name()))) {
            result.next();
            return result.getLong(1);
        }
    }

    private static String quoteColumns(Connection connection, TableInfo table, List<String> normalizedColumns)
            throws SQLException {
        List<String> quoted = new ArrayList<>();
        for (String normalized : normalizedColumns) {
            String actual = table.columnsByName().get(normalized.toLowerCase(Locale.ROOT));
            if (actual == null) throw new IllegalStateException("Missing expected column " + normalized + ".");
            quoted.add(quote(connection, actual));
        }
        return String.join(", ", quoted);
    }

    private static String quote(Connection connection, String identifier) throws SQLException {
        String quote = connection.getMetaData().getIdentifierQuoteString().trim();
        return quote.isEmpty() ? identifier : quote + identifier.replace(quote, quote + quote) + quote;
    }

    private static void resetPostgresSequences(
            Connection target, Map<String, TableInfo> tables, List<String> tableNames) throws SQLException {
        for (String tableName : tableNames) {
            TableInfo table = tables.get(tableName);
            if (table == null || !table.columnsByName().containsKey("id")) continue;
            String actualTable = table.name();
            String sql = "SELECT setval(pg_get_serial_sequence(?, 'id'), "
                    + "COALESCE(MAX(" + quote(target, table.columnsByName().get("id")) + "), 1), "
                    + "MAX(" + quote(target, table.columnsByName().get("id")) + ") IS NOT NULL) FROM "
                    + quote(target, actualTable);
            try (PreparedStatement statement = target.prepareStatement(sql)) {
                statement.setString(1, actualTable);
                statement.executeQuery().close();
            }
        }
    }

    private record TableInfo(String name, List<String> columns, Map<String, String> columnsByName) { }
}
