package mithila.mith.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Test;

class H2ToPostgresMigrationTests {
    @Test
    void copiesAllRideRecordsInForeignKeyOrder() throws Exception {
        try (Connection source = DriverManager.getConnection("jdbc:h2:mem:migration-source");
                Connection target = DriverManager.getConnection("jdbc:h2:mem:migration-target")) {
            createSchema(source);
            createSchema(target);
            try (Statement statement = source.createStatement()) {
                statement.executeUpdate("INSERT INTO user_accounts (id, name) VALUES (41, 'Rider'), (42, 'Driver')");
                statement.executeUpdate("INSERT INTO ride_bookings (id, rider_id, driver_id, pickup) "
                        + "VALUES (73, 41, 42, 'Rajnagar')");
                statement.executeUpdate("INSERT INTO ride_offers (id, ride_id, driver_id) VALUES (74, 73, 42)");
                statement.executeUpdate("INSERT INTO ride_complaints (id, ride_id, reporter_id) "
                        + "VALUES (75, 73, 41)");
                statement.executeUpdate("INSERT INTO ride_notifications (id, ride_id, user_id) "
                        + "VALUES (76, 73, 41)");
            }

            var result = H2ToPostgresMigration.copyTables(
                    source, target,
                    List.of("user_accounts", "ride_bookings", "ride_offers", "ride_complaints", "ride_notifications"),
                    false);

            assertEquals(2, result.get("user_accounts"));
            assertEquals(1, result.get("ride_bookings"));
            assertEquals(1, result.get("ride_offers"));
            assertEquals(1, result.get("ride_complaints"));
            assertEquals(1, result.get("ride_notifications"));
            try (Statement statement = target.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT id, rider_id, driver_id, pickup FROM ride_bookings")) {
                rows.next();
                assertEquals(73, rows.getLong("id"));
                assertEquals(41, rows.getLong("rider_id"));
                assertEquals(42, rows.getLong("driver_id"));
                assertEquals("Rajnagar", rows.getString("pickup"));
            }
            assertEquals(1, count(target, "ride_offers"));
            assertEquals(1, count(target, "ride_complaints"));
            assertEquals(1, count(target, "ride_notifications"));
        }
    }

    @Test
    void refusesToOverwriteAnyExistingDestinationRows() throws Exception {
        try (Connection source = DriverManager.getConnection("jdbc:h2:mem:existing-source");
                Connection target = DriverManager.getConnection("jdbc:h2:mem:existing-target")) {
            createSchema(source);
            createSchema(target);
            try (Statement statement = target.createStatement()) {
                statement.executeUpdate("INSERT INTO user_accounts (id, name) VALUES (1, 'Existing')");
            }

            assertThrows(IllegalStateException.class, () -> H2ToPostgresMigration.copyTables(
                    source, target, List.of("user_accounts", "ride_bookings"), false));
            try (Statement statement = target.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM user_accounts")) {
                rows.next();
                assertEquals(1, rows.getInt(1));
            }
        }
    }

    @Test
    void rollsBackCopiedRowsWhenDestinationSchemaIsIncomplete() throws Exception {
        try (Connection source = DriverManager.getConnection("jdbc:h2:mem:rollback-source");
                Connection target = DriverManager.getConnection("jdbc:h2:mem:rollback-target")) {
            createSchema(source);
            try (Statement statement = target.createStatement()) {
                statement.execute("CREATE TABLE user_accounts (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
                statement.execute("CREATE TABLE ride_bookings (id BIGINT PRIMARY KEY, rider_id BIGINT NOT NULL, "
                        + "driver_id BIGINT, pickup VARCHAR(160) NOT NULL, "
                        + "FOREIGN KEY (rider_id) REFERENCES user_accounts(id), "
                        + "FOREIGN KEY (driver_id) REFERENCES user_accounts(id))");
            }
            try (Statement statement = source.createStatement()) {
                statement.executeUpdate("INSERT INTO user_accounts (id, name) VALUES (41, 'Rider')");
                statement.executeUpdate("INSERT INTO ride_bookings (id, rider_id, pickup) VALUES (73, 41, 'Rajnagar')");
                statement.executeUpdate("INSERT INTO ride_offers (id, ride_id, driver_id) VALUES (74, 73, 41)");
            }

            assertThrows(IllegalStateException.class, () -> H2ToPostgresMigration.copyTables(
                    source, target, List.of("user_accounts", "ride_bookings", "ride_offers"), false));
            assertEquals(0, count(target, "user_accounts"));
            assertEquals(0, count(target, "ride_bookings"));
        }
    }

    private static void createSchema(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE user_accounts (id BIGINT PRIMARY KEY, name VARCHAR(100) NOT NULL)");
            statement.execute("CREATE TABLE ride_bookings (id BIGINT PRIMARY KEY, rider_id BIGINT NOT NULL, "
                    + "driver_id BIGINT, pickup VARCHAR(160) NOT NULL, "
                    + "FOREIGN KEY (rider_id) REFERENCES user_accounts(id), "
                    + "FOREIGN KEY (driver_id) REFERENCES user_accounts(id))");
            statement.execute("CREATE TABLE ride_offers (id BIGINT PRIMARY KEY, ride_id BIGINT NOT NULL, "
                    + "driver_id BIGINT NOT NULL, FOREIGN KEY (ride_id) REFERENCES ride_bookings(id), "
                    + "FOREIGN KEY (driver_id) REFERENCES user_accounts(id))");
            statement.execute("CREATE TABLE ride_complaints (id BIGINT PRIMARY KEY, ride_id BIGINT NOT NULL, "
                    + "reporter_id BIGINT NOT NULL, FOREIGN KEY (ride_id) REFERENCES ride_bookings(id), "
                    + "FOREIGN KEY (reporter_id) REFERENCES user_accounts(id))");
            statement.execute("CREATE TABLE ride_notifications (id BIGINT PRIMARY KEY, ride_id BIGINT, "
                    + "user_id BIGINT NOT NULL, FOREIGN KEY (ride_id) REFERENCES ride_bookings(id), "
                    + "FOREIGN KEY (user_id) REFERENCES user_accounts(id))");
        }
    }

    private static int count(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
