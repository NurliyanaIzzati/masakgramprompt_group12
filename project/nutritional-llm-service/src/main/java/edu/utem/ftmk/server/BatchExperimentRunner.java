package edu.utem.ftmk.server;

import edu.utem.ftmk.analyzer.LLMAnalyzer;
import edu.utem.ftmk.database.DatabaseManager;
import edu.utem.ftmk.prompt.PromptManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Runs the complete transcript batch on the server.
 *
 * The Swing client sends only the selected model and techniques. This class
 * retrieves every transcript from MySQL, loads its text on the server, calls
 * the LLM, stores the result, and streams progress messages back to the client.
 */
public final class BatchExperimentRunner implements Runnable {

    private static final long LLM_TIMEOUT_MS = 900_000L; // 15 minutes per run

    private final String modelTag;
    private final List<String> techniques;
    private final LLMAnalyzer analyzer;
    private final DatabaseManager databaseManager;
    private final PromptManager promptManager;
    private final Consumer<String> messageSender;
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    public BatchExperimentRunner(
            String modelTag,
            List<String> techniques,
            LLMAnalyzer analyzer,
            DatabaseManager databaseManager,
            PromptManager promptManager,
            Consumer<String> messageSender) {

        this.modelTag = modelTag;
        this.techniques = List.copyOf(techniques);
        this.analyzer = analyzer;
        this.databaseManager = databaseManager;
        this.promptManager = promptManager;
        this.messageSender = messageSender;
    }

    public void requestStop() {
        stopRequested.set(true);
    }

    @Override
    public void run() {
        JSONArray transcripts = databaseManager.getAllTranscriptsForBatch();
        int transcriptCount = transcripts.length();
        int totalRuns = transcriptCount * techniques.size();

        int completedRuns = 0;
        int successfulRuns = 0;
        int failedRuns = 0;

        send("BATCH_STARTED|" + totalRuns + "|" + transcriptCount + "|" + techniques.size());

        for (int transcriptIndex = 0; transcriptIndex < transcripts.length(); transcriptIndex++) {
            JSONObject transcript = transcripts.optJSONObject(transcriptIndex);
            if (transcript == null) {
                continue;
            }

            int transcriptId = transcript.optInt("transcript_id", -1);
            String transcriptText = transcript.optString("transcript_text", "");

            for (String technique : techniques) {
                if (stopRequested.get()) {
                    send("BATCH_STOPPED|" + completedRuns + "|" + totalRuns
                            + "|" + successfulRuns + "|" + failedRuns);
                    return;
                }

                send("BATCH_PROGRESS|" + completedRuns + "|" + totalRuns
                        + "|" + transcriptId + "|" + technique + "|RUNNING|");

                boolean success = false;
                String errorMessage = "";

                try {
                    if (transcriptId <= 0) {
                        throw new IllegalStateException("Invalid transcript ID");
                    }

                    if (transcriptText == null || transcriptText.isBlank()) {
                        throw new IllegalStateException("Transcript text is missing or empty");
                    }

                    String fullPrompt = promptManager.buildFullPrompt(technique, transcriptText);
                    long startTime = System.currentTimeMillis();
                    String result = callLLMWithTimeout(fullPrompt, modelTag);
                    long executionTimeMs = System.currentTimeMillis() - startTime;

                    if (result == null || result.isBlank()) {
                        throw new IllegalStateException("Empty response from LLM");
                    }

                    success = databaseManager.saveExperimentResult(
                            modelTag,
                            technique,
                            result,
                            transcriptId
                    );

                    if (!success) {
                        throw new IllegalStateException("Database save failed");
                    }

                    databaseManager.updateExecutionTime(
                            modelTag,
                            technique,
                            transcriptId,
                            executionTimeMs
                    );

                    successfulRuns++;

                } catch (Exception e) {
                    failedRuns++;
                    errorMessage = safeMessage(e.getMessage());
                    System.err.printf(
                            "[BATCH] Failed: transcript=%d model=%s technique=%s error=%s%n",
                            transcriptId,
                            modelTag,
                            technique,
                            errorMessage
                    );
                }

                completedRuns++;

                send("BATCH_PROGRESS|" + completedRuns + "|" + totalRuns
                        + "|" + transcriptId + "|" + technique + "|"
                        + (success ? "COMPLETED" : "FAILED") + "|" + errorMessage);
            }
        }

        send("BATCH_FINISHED|" + successfulRuns + "|" + failedRuns
                + "|" + completedRuns + "|" + totalRuns);
    }

    private String callLLMWithTimeout(String prompt, String selectedModel) throws Exception {
        final String[] result = new String[1];
        final Exception[] error = new Exception[1];

        Thread worker = new Thread(() -> {
            try {
                result[0] = analyzer.analyze(prompt, selectedModel);
            } catch (Exception e) {
                error[0] = e;
            }
        }, "batch-llm-call");

        worker.setDaemon(true);
        worker.start();
        worker.join(LLM_TIMEOUT_MS);

        if (worker.isAlive()) {
            worker.interrupt();
            throw new SocketTimeoutException("LLM call timed out after " + LLM_TIMEOUT_MS + " ms");
        }

        if (error[0] != null) {
            throw error[0];
        }

        return result[0];
    }

    private void send(String message) {
        messageSender.accept(message);
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
