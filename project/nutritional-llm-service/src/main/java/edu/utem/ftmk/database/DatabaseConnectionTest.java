package edu.utem.ftmk.database;

import java.sql.Connection;

public class DatabaseConnectionTest {

    public static void main(String[] args) {
        try (Connection conn = DatabaseConnection.getConnection()) {
            System.out.println(" Database connected successfully!");
        } catch (Exception e) {
            System.err.println(" Connection failed!");
            e.printStackTrace();
        }
    }
}