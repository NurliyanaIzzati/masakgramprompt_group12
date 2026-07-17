package edu.utem.ftmk.database;

import java.sql.Connection;

public class DatabaseConnectionTest {

    public static void main(String[] args) {
        try (Connection connection =
                     DatabaseConnection.getConnection()) {

            System.out.println(
                    "Database connected successfully!"
            );

            System.out.println(
                    "Database: "
                    + connection.getCatalog()
            );

            System.out.println(
                    "MySQL version: "
                    + connection
                        .getMetaData()
                        .getDatabaseProductVersion()
            );

        } catch (Exception e) {
            System.err.println(
                    "Database connection failed: "
                    + e.getMessage()
            );

            e.printStackTrace();
        }
    }
}