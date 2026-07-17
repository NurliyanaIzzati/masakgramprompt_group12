package edu.utem.ftmk.debug;

import java.io.*;
import java.net.*;

public class DebugTCPTest {

    public static void main(String[] args) {
        String model = "llama3.2:3b";
        int transcriptId = 30;
        String technique = "zero-shot";

        System.out.println("=== DEBUG TCP TEST ===");
        System.out.println("Connecting to server...");

        try (Socket socket = new Socket("localhost", 5000);
             PrintWriter out = new PrintWriter(socket.getOutputStream(), true);
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {

            System.out.println("✅ Connected to server");

            // Send the same command as MasakGramClient
            String command = "RUN|" + model + "|" + transcriptId + "|" + technique;
            System.out.println("Sending: " + command);
            out.println(command);

            System.out.println("Waiting for response...");
            
            // Read response with timeout
            socket.setSoTimeout(300000); // 5 minutes timeout
            
            String response = in.readLine();
            
            System.out.println("=== RESPONSE ===");
            if (response == null) {
                System.out.println("❌ No response (null)");
            } else if (response.startsWith("ERROR:")) {
                System.out.println("❌ ERROR: " + response);
            } else {
                System.out.println("✅ Response received!");
                System.out.println("Length: " + response.length());
                System.out.println("Preview: " + response.substring(0, Math.min(500, response.length())));
                
                // Save response to file
                try (FileWriter fw = new FileWriter("tcp_debug_response.txt")) {
                    fw.write(response);
                    System.out.println("✅ Full response saved to tcp_debug_response.txt");
                }
            }

        } catch (SocketTimeoutException e) {
            System.err.println("❌ TIMEOUT: Server took too long to respond!");
        } catch (Exception e) {
            System.err.println("❌ ERROR: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("=== DEBUG TCP TEST COMPLETE ===");
    }
}