package edu.utem.ftmk.server;

import edu.utem.ftmk.analyzer.LLMAnalyzer;
import edu.utem.ftmk.database.DatabaseManager;
import edu.utem.ftmk.prompt.PromptManager;

import java.io.BufferedReader;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Handles one Swing client connection.
 *
 * Supported commands:
 * RUN|model|transcriptId|technique  - process one transcript
 * RUN_BATCH|model|tech1,tech2       - server retrieves and processes all transcripts
 * STOP_BATCH                        - request the active server-side batch to stop
 * PING                              - connection test
 * QUIT / EXIT                       - close the connection
 */
public class ClientHandler implements Runnable {

    private static final long LLM_TIMEOUT_MS = 900_000L; // 15 minutes per LLM call
    private static final int MAX_RESPONSE_LENGTH = 100_000;

    private final Socket clientSocket;
    private final LLMAnalyzer analyzer;
    private final DatabaseManager db;
    private final PromptManager promptMgr;

    private volatile PrintWriter out;
    private volatile BatchExperimentRunner activeBatch;
    private volatile Thread activeBatchThread;

    public ClientHandler(Socket clientSocket, LLMAnalyzer analyzer) {
        this.clientSocket = clientSocket;
        this.analyzer = analyzer;
        this.db = new DatabaseManager();
        this.promptMgr = new PromptManager();

        try {
            // Keep the command connection open while a batch runs for hours.
            this.clientSocket.setSoTimeout(0);
        } catch (IOException e) {
            System.err.println("[TCP] Failed to configure socket: " + e.getMessage());
        }
    }

    @Override
    public void run() {
        String clientAddr = clientSocket.getInetAddress().getHostAddress();
        System.out.println("[TCP] Handler started for: " + clientAddr);

        try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(clientSocket.getInputStream()));
             PrintWriter writer = new PrintWriter(clientSocket.getOutputStream(), true)) {

            this.out = writer;
            String message;

            while ((message = in.readLine()) != null) {
                System.out.println("[TCP] Received: " + message);
                handleCommand(message.trim());
            }

        } catch (EOFException | java.net.SocketException e) {
            System.out.println("[TCP] Client disconnected: " + clientAddr);

        } catch (IOException e) {
            System.err.println("[TCP] IO error for " + clientAddr + ": " + e.getMessage());

        } finally {
            // The batch belongs to the server. Closing the Swing client does not
            // cancel it; only an explicit STOP_BATCH command requests a stop.
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }

            System.out.println("[TCP] Handler closed for: " + clientAddr);
        }
    }

    private void handleCommand(String message) {
        if (message.startsWith("RUN_BATCH|")) {
            startServerBatch(message);
            return;
        }

        if (message.equalsIgnoreCase("STOP_BATCH")) {
            stopActiveBatch(true);
            return;
        }

        if (message.startsWith("RUN|")) {
            handleSingleRun(message);
            return;
        }

        if (message.equalsIgnoreCase("PING")) {
            sendMessage("PONG");
            return;
        }

        if (message.equalsIgnoreCase("QUIT") || message.equalsIgnoreCase("EXIT")) {
            sendMessage("BYE");
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
            return;
        }

        sendMessage("ERROR|Unknown command");
    }

    private synchronized void startServerBatch(String message) {
        if (activeBatchThread != null && activeBatchThread.isAlive()) {
            sendMessage("ERROR|A batch is already running for this client");
            return;
        }

        String[] parts = message.split("\\|", 3);
        if (parts.length < 3) {
            sendMessage("ERROR|Invalid format. Use RUN_BATCH|model|technique1,technique2");
            return;
        }

        String modelTag = parts[1].trim();
        List<String> techniques = Arrays.stream(parts[2].split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .collect(Collectors.toList());

        if (modelTag.isBlank()) {
            sendMessage("ERROR|Model is required");
            return;
        }

        if (techniques.isEmpty()) {
            sendMessage("ERROR|At least one prompt technique is required");
            return;
        }

        BatchExperimentRunner runner = new BatchExperimentRunner(
                modelTag,
                techniques,
                analyzer,
                db,
                promptMgr,
                this::sendMessage
        );

        Thread batchThread = new Thread(() -> {
            try {
                runner.run();
            } catch (Exception e) {
                System.err.println("[BATCH] Unexpected error: " + e.getMessage());
                e.printStackTrace();
                sendMessage("ERROR|Batch failed: " + safeMessage(e.getMessage()));
            } finally {
                synchronized (ClientHandler.this) {
                    if (activeBatch == runner) {
                        activeBatch = null;
                        activeBatchThread = null;
                    }
                }
            }
        }, "server-batch-" + clientSocket.getPort());

        activeBatch = runner;
        activeBatchThread = batchThread;
        batchThread.start();
    }

    private synchronized void stopActiveBatch(boolean notifyClient) {
        BatchExperimentRunner runner = activeBatch;

        if (runner == null) {
            if (notifyClient) {
                sendMessage("ERROR|No batch is currently running");
            }
            return;
        }

        runner.requestStop();
        if (notifyClient) {
            sendMessage("STOP_REQUESTED");
        }
    }

    private void handleSingleRun(String message) {
        String[] parts = message.split("\\|", 4);

        if (parts.length < 4) {
            sendMessage("ERROR: Invalid RUN format. Expected: RUN|model|transcriptId|technique");
            return;
        }

        String modelTag = parts[1].trim();
        String technique = parts[3].trim();

        int transcriptId;
        try {
            transcriptId = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            sendMessage("ERROR: transcriptId must be a number, got: " + parts[2]);
            return;
        }

        String transcriptText = db.getTranscriptText(transcriptId);
        if (transcriptText == null || transcriptText.isBlank()) {
            sendMessage("ERROR: Transcript #" + transcriptId + " not found or empty.");
            return;
        }

        try {
            String fullPrompt = promptMgr.buildFullPrompt(technique, transcriptText);
            long startTime = System.currentTimeMillis();
            String result = callLLMWithTimeout(fullPrompt, modelTag);
            long executionTimeMs = System.currentTimeMillis() - startTime;

            if (result == null || result.isBlank()) {
                sendMessage("ERROR: Empty response from LLM");
                return;
            }

            boolean saved = db.saveExperimentResult(modelTag, technique, result, transcriptId);
            if (!saved) {
                sendMessage("ERROR: DB save failed");
                return;
            }

            db.updateExecutionTime(modelTag, technique, transcriptId, executionTimeMs);

            String validatedResult = validateAndCleanResponse(result);
            if (validatedResult == null) {
                sendMessage("ERROR: Invalid JSON response from LLM");
                return;
            }

            String responseToSend = validatedResult.replace("\n", "\\n").replace("\r", "");
            if (responseToSend.length() > MAX_RESPONSE_LENGTH) {
                responseToSend = responseToSend.substring(0, MAX_RESPONSE_LENGTH) + "...";
            }

            sendMessage(responseToSend);

        } catch (SocketTimeoutException e) {
            String timeoutJson = "{\"error\":\"LLM call timed out after " + LLM_TIMEOUT_MS + "ms\"}";
            db.saveExperimentResult(modelTag, technique, timeoutJson, transcriptId);
            sendMessage("ERROR: LLM call timed out after " + LLM_TIMEOUT_MS + "ms");

        } catch (Exception e) {
            System.err.println("[TCP] Single run error: " + e.getMessage());
            e.printStackTrace();
            sendMessage("ERROR: LLM call failed — " + safeMessage(e.getMessage()));
        }
    }

    private synchronized void sendMessage(String message) {
        PrintWriter writer = out;
        if (writer != null) {
            writer.println(message);
            writer.flush();
        }
    }

    private String callLLMWithTimeout(String prompt, String modelTag) throws Exception {
        final String[] result = new String[1];
        final Exception[] exception = new Exception[1];

        Thread worker = new Thread(() -> {
            try {
                result[0] = analyzer.analyze(prompt, modelTag);
            } catch (Exception e) {
                exception[0] = e;
            }
        }, "single-llm-call");

        worker.setDaemon(true);
        worker.start();
        worker.join(LLM_TIMEOUT_MS);

        if (worker.isAlive()) {
            worker.interrupt();
            throw new SocketTimeoutException("LLM analysis timed out");
        }

        if (exception[0] != null) {
            throw exception[0];
        }

        return result[0];
    }

    private String validateAndCleanResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }

        String trimmed = response.trim();
        String extracted = extractJSONFromResponse(trimmed);

        if (extracted != null) {
            return extracted;
        }

        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed;
        }

        return null;
    }

    private String extractJSONFromResponse(String response) {
        int firstBrace = response.indexOf('{');
        int firstBracket = response.indexOf('[');
        int start;
        char open;
        char close;

        if (firstBrace >= 0 && (firstBracket < 0 || firstBrace < firstBracket)) {
            start = firstBrace;
            open = '{';
            close = '}';
        } else if (firstBracket >= 0) {
            start = firstBracket;
            open = '[';
            close = ']';
        } else {
            return null;
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;

        for (int i = start; i < response.length(); i++) {
            char c = response.charAt(i);

            if (escaped) {
                escaped = false;
                continue;
            }

            if (c == '\\') {
                escaped = true;
                continue;
            }

            if (c == '"') {
                inString = !inString;
                continue;
            }

            if (!inString) {
                if (c == open) {
                    depth++;
                } else if (c == close) {
                    depth--;
                    if (depth == 0) {
                        return response.substring(start, i + 1);
                    }
                }
            }
        }

        return null;
    }

    private String safeMessage(String message) {
        if (message == null || message.isBlank()) {
            return "Unknown error";
        }

        return message
                .replace('|', '/')
                .replace('\r', ' ')
                .replace('\n', ' ');
    }
}
