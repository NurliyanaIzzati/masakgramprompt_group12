package edu.utem.ftmk.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Creates MySQL connections without storing private credentials in source code.
 *
 * Configuration can be supplied either as environment variables or Java
 * system properties. Environment variables take priority.
 */
public final class DatabaseConnection {

    private static final String DEFAULT_URL =
            "jdbc:mysql://127.0.0.1:3306/masakgramprompt"
            + "?useSSL=false"
            + "&allowPublicKeyRetrieval=true"
            + "&serverTimezone=UTC";

    private DatabaseConnection() {
        // Utility class.
    }

    public static Connection getConnection() throws SQLException {
        String url = readSetting("MASAKGRAM_DB_URL", DEFAULT_URL);
        String user = readSetting("MASAKGRAM_DB_USER", "root");
        String password = readSetting("MASAKGRAM_DB_PASSWORD", null);

        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "MASAKGRAM_DB_PASSWORD is not configured. "
                    + "Add it to the MasakGramServer Eclipse Run Configuration "
                    + "under the Environment tab."
            );
        }

        return DriverManager.getConnection(url, user, password);
    }

    private static String readSetting(String name, String defaultValue) {
        String value = System.getenv(name);

        if (value == null || value.isBlank()) {
            value = System.getProperty(name);
        }

        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }
}