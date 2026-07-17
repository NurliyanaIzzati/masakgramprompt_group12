package edu.utem.ftmk.server;

import edu.utem.ftmk.analyzer.LLMAnalyzer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * MasakGramServer
 *
 * Entry point for the MasakGramPrompt server.
 *
 * The server provides:
 * 1. TCP socket communication on port 5000 for the Java Swing client.
 * 2. An internal HTTP API on port 8080 for statistics, experiment details,
 *    execution summaries and CSV exports used by the Swing client.
 *
 * Each connected Swing client is handled in a separate thread.
 */
public class MasakGramServer {

    private static final int TCP_PORT = 5000;

    private final LLMAnalyzer analyzer;

    public MasakGramServer() {
        this.analyzer = new LLMAnalyzer();
    }

    public void start() {
        System.out.println("=========================================");
        System.out.println("  MasakGramPrompt Server Starting...");

        // Internal API used by the Java Swing application.
        HttpApiServer.start();

        System.out.println("  TCP Server       : port " + TCP_PORT);
        System.out.println("=========================================");

        try (ServerSocket serverSocket = new ServerSocket(TCP_PORT)) {
            System.out.println("[TCP] Waiting for Swing client connections...");

            while (true) {
                Socket clientSocket = serverSocket.accept();

                System.out.println(
                    "[TCP] Client connected: "
                    + clientSocket.getInetAddress().getHostAddress()
                );

                Thread clientThread = new Thread(
                    new ClientHandler(clientSocket, analyzer),
                    "tcp-client-" + clientSocket.getPort()
                );

                clientThread.start();
            }

        } catch (IOException e) {
            System.err.println(
                "[TCP] Server error on port "
                + TCP_PORT + ": " + e.getMessage()
            );
            e.printStackTrace();
        }
    }

    public static void main(String[] args) {
        new MasakGramServer().start();
    }
}