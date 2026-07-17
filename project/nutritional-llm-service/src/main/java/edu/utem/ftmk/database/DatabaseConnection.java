package edu.utem.ftmk.database;

import java.sql.Connection;
import java.sql.DriverManager;

public class DatabaseConnection {

    
    private static final String URL = 
            "jdbc:mysql://localhost:3306/masakgramprompt?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC";

    private static final String USER = "root";
    
    
    private static final String PASSWORD = "1234"; 

    public static Connection getConnection() throws Exception {
        return DriverManager.getConnection(URL, USER, PASSWORD);
    }
}