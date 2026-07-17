package edu.utem.ftmk.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.utem.ftmk.analyzer.LLMAnalyzer;
import edu.utem.ftmk.database.DatabaseManager;
import edu.utem.ftmk.prompt.PromptManager;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;
import java.util.regex.Pattern;

public class HttpApiServer {

    private static final int HTTP_PORT = 8080;
    private static final int LLM_TIMEOUT_SECONDS = 900; // 15 minutes
    private static final int MAX_RESPONSE_LENGTH = 100000; // 100KB
    private static final int MAX_RETRIES = 3;
    
    private static final ExecutorService asyncExecutor = Executors.newCachedThreadPool();

    public static void start() {
        try {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress(HTTP_PORT), 50);

            DatabaseManager db = new DatabaseManager();
            
            // --- Application statistics API ---
            server.createContext("/api/stats", exchange -> {
                addCorsHeaders(exchange);
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1); return;
                }
                try {
                    JSONObject stats = db.getDashboardStats();
                    sendJson(exchange, 200, stats.toString());
                } catch (Exception e) {
                    sendError(exchange, 500, "Failed to get stats: " + e.getMessage());
                }
            });

            // --- Transcript dropdown ---
            server.createContext("/api/transcripts", exchange -> {
                addCorsHeaders(exchange);
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1); return;
                }
                try {
                    JSONArray list = db.getTranscriptList();
                    sendJson(exchange, 200, list.toString());
                } catch (Exception e) {
                    sendError(exchange, 500, "Failed to get transcripts: " + e.getMessage());
                }
            });

            // --- Experiments for transcript ---
            server.createContext("/api/experiments", exchange -> {
                addCorsHeaders(exchange);
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1); return;
                }
                try {
                    int id = parseQueryParam(exchange, "id", 1);
                    JSONArray rows = db.getExperimentsForTranscript(id);
                    sendJson(exchange, 200, rows.toString());
                } catch (Exception e) {
                    sendError(exchange, 500, "Failed to get experiments: " + e.getMessage());
                }
            });

            // --- Recent experiments ---
            server.createContext("/api/recent-experiments", exchange -> {
                addCorsHeaders(exchange);
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1); return;
                }
                try {
                    JSONArray rows = db.getRecentExperiments();
                    sendJson(exchange, 200, rows.toString());
                } catch (Exception e) {
                    sendError(exchange, 500, "Failed to get recent experiments: " + e.getMessage());
                }
            });

            // --- Experiment detail ---
            server.createContext("/api/experiment-detail", exchange -> {
                addCorsHeaders(exchange);
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1); return;
                }
                try {
                    int id = parseQueryParam(exchange, "id", 0);
                    if (id <= 0) {
                        sendError(exchange, 400, "Invalid experiment ID");
                        return;
                    }
                    JSONObject detail = db.getExperimentDetail(id);
                    sendJson(exchange, 200, detail.toString());
                } catch (Exception e) {
                    sendError(exchange, 500, "Failed to get experiment detail: " + e.getMessage());
                }
            });

            // --- Run experiment (ENHANCED) ---
            server.createContext("/api/run", exchange -> {
                addCorsHeaders(exchange);
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1); return;
                }
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1); return;
                }
                
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    JSONObject req = new JSONObject(body);

                    String model = req.getString("model");
                    String technique = req.getString("technique");
                    int transcriptId = req.getInt("transcriptId");
                    String transcript = req.optString("transcript", "");

                    // Validate required fields
                    if (model == null || model.trim().isEmpty()) {
                        sendError(exchange, 400, "Model is required");
                        return;
                    }
                    if (technique == null || technique.trim().isEmpty()) {
                        sendError(exchange, 400, "Technique is required");
                        return;
                    }
                    if (transcript == null || transcript.trim().isEmpty()) {
                        sendError(exchange, 400, "Transcript is required");
                        return;
                    }

                    // Submit async task with proper error handling
                    CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                        runExperimentAsync(model, technique, transcriptId, transcript);
                    }, asyncExecutor);

                    // Add timeout handling
                    future.orTimeout(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                          .exceptionally(throwable -> {
                              System.err.println("[HTTP] Async experiment failed: " + throwable.getMessage());
                              return null;
                          });

                    JSONObject resp = new JSONObject();
                    resp.put("status", "started");
                    resp.put("message", "Experiment started. This may take up to " + 
                             LLM_TIMEOUT_SECONDS + " seconds.");
                    resp.put("transcript_id", transcriptId);
                    resp.put("model", model);
                    resp.put("technique", technique);
                    sendJson(exchange, 200, resp.toString());

                } catch (Exception e) {
                    System.err.println("[HTTP] Run experiment error: " + e.getMessage());
                    e.printStackTrace();
                    sendError(exchange, 500, "Failed to start experiment: " + e.getMessage());
                }
            });

            // --- Run experiment with sync response (NEW) ---
            server.createContext("/api/run-sync", exchange -> {
                addCorsHeaders(exchange);
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1); return;
                }
                if (!"POST".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(405, -1); return;
                }
                
                try {
                    String body = new String(exchange.getRequestBody().readAllBytes());
                    JSONObject req = new JSONObject(body);

                    String model = req.getString("model");
                    String technique = req.getString("technique");
                    int transcriptId = req.getInt("transcriptId");
                    String transcript = req.optString("transcript", "");

                    // Validate
                    if (model == null || model.trim().isEmpty() ||
                        technique == null || technique.trim().isEmpty() ||
                        transcript == null || transcript.trim().isEmpty()) {
                        sendError(exchange, 400, "Missing required fields");
                        return;
                    }

                    // Run synchronously with timeout
                    String result = runExperimentSync(model, technique, transcriptId, transcript);
                    
                    if (result != null) {
                        // Validate and clean response
                        String validated = validateAndCleanResponse(result);
                        if (validated != null) {
                            // Send success with result
                            JSONObject resp = new JSONObject();
                            resp.put("status", "success");
                            resp.put("result", validated);
                            resp.put("transcript_id", transcriptId);
                            resp.put("model", model);
                            resp.put("technique", technique);
                            sendJson(exchange, 200, resp.toString());
                        } else {
                            sendError(exchange, 500, "Invalid JSON response from LLM");
                        }
                    } else {
                        sendError(exchange, 500, "Failed to get response from LLM");
                    }

                } catch (TimeoutException e) {
                    sendError(exchange, 408, "Experiment timed out after " + LLM_TIMEOUT_SECONDS + " seconds");
                } catch (Exception e) {
                    System.err.println("[HTTP] Sync experiment error: " + e.getMessage());
                    e.printStackTrace();
                    sendError(exchange, 500, "Failed to run experiment: " + e.getMessage());
                }
            });

            // --- CSV export ---
            server.createContext("/api/export", exchange -> {
                addCorsHeaders(exchange);
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1); return;
                }
                if (!"GET".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().set("Allow", "GET, OPTIONS");
                    sendError(exchange, 405, "Only GET is supported for CSV export");
                    return;
                }

                Path tempFile = null;
                String layer = "";
                try {
                    String query = exchange.getRequestURI().getRawQuery();

                    if (query != null) {
                        for (String part : query.split("&")) {
                            if (part.startsWith("layer=")) {
                                layer = java.net.URLDecoder.decode(
                                        part.substring(6), "UTF-8").toUpperCase();
                            }
                        }
                    }

                    if (layer.isEmpty()) {
                        sendError(exchange, 400, "Layer parameter is required");
                        return;
                    }

                    String fileName = exportFileName(layer);
                    tempFile = Files.createTempFile("masakgram-export-", ".csv");

                    db.exportCsv(layer, tempFile.toString());

                    byte[] bytes = Files.readAllBytes(tempFile);
                    exchange.getResponseHeaders().set("Content-Type", "text/csv; charset=UTF-8");
                    exchange.getResponseHeaders().set("Content-Disposition",
                            "attachment; filename=\"" + fileName + "\"");
                    exchange.getResponseHeaders().set("Cache-Control", "no-store");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream body = exchange.getResponseBody()) {
                        body.write(bytes);
                    }
                } catch (Exception e) {
                    System.err.println("[HTTP] CSV export error for " + layer + ": " + e.getMessage());
                    e.printStackTrace();
                    sendError(exchange, 500,
                            "Export failed for " + layer + ": " + rootCauseMessage(e));
                } finally {
                    if (tempFile != null) {
                        try { Files.deleteIfExists(tempFile); }
                        catch (IOException ignored) {}
                    }
                }
            });

            // --- Execution Time Summary ---
            server.createContext("/api/execution-summary", exchange -> {
                addCorsHeaders(exchange);
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                try {
                    JSONArray summary = db.getExecutionTimeSummary();
                    sendJson(exchange, 200, summary.toString());
                } catch (Exception e) {
                    sendError(exchange, 500, "Failed to get execution summary: " + e.getMessage());
                }
            });

            // --- Health check ---
            server.createContext("/api/health", exchange -> {
                addCorsHeaders(exchange);
                if ("OPTIONS".equals(exchange.getRequestMethod())) {
                    exchange.sendResponseHeaders(204, -1);
                    return;
                }
                JSONObject health = new JSONObject();
                health.put("status", "ok");
                health.put("timestamp", System.currentTimeMillis());
                health.put("timeout_seconds", LLM_TIMEOUT_SECONDS);
                sendJson(exchange, 200, health.toString());
            });

            server.setExecutor(Executors.newFixedThreadPool(20));
            server.start();

            System.out.println("=========================================");
            System.out.println("  Internal HTTP API : port " + HTTP_PORT);
            System.out.println("  Timeout: " + LLM_TIMEOUT_SECONDS + " seconds");
            System.out.println("  Max Retries: " + MAX_RETRIES);
            System.out.println("=========================================");

        } catch (IOException e) {
        	System.err.println("[HTTP] Failed to start internal API: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // ================================================================
    // EXPERIMENT RUNNER METHODS
    // ================================================================

    /**
     * Run experiment asynchronously with proper error handling
     */
    private static void runExperimentAsync(String model, String technique, 
                                           int transcriptId, String transcript) {
        try {
            System.out.println("[HTTP] Async experiment starting: " + model + "/" + 
                             technique + " transcript=" + transcriptId);
            
            long startTime = System.currentTimeMillis();
            
            // Build prompt
            PromptManager pm = new PromptManager();
            String fullPrompt = pm.buildFullPrompt(technique, transcript);
            
            // Call LLM with timeout
            LLMAnalyzer analyzer = new LLMAnalyzer();
            String result = callLLMWithTimeout(analyzer, fullPrompt, model);
            
            long executionTimeMs = System.currentTimeMillis() - startTime;
            
            if (result == null || result.trim().isEmpty()) {
                System.err.println("[HTTP] Empty response from LLM for transcript " + transcriptId);
                return;
            }
            
            // Validate response contains JSON
            String validatedResult = validateAndCleanResponse(result);
            if (validatedResult == null) {
                System.err.println("[HTTP] Response contains no JSON for transcript " + transcriptId);
                // Still try to save it so we have a record of the failure
                DatabaseManager db = new DatabaseManager();
                db.saveExperimentResult(model, technique, result, transcriptId);
                return;
            }
            
            // Save with retry
            DatabaseManager db = new DatabaseManager();
            boolean saved = false;
            for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
                try {
                    if (attempt > 0) {
                        System.out.println("[HTTP] Retry " + attempt + " for transcript " + transcriptId);
                        Thread.sleep(1000 * attempt);
                    }
                    saved = db.saveExperimentResult(model, technique, result, transcriptId);
                    if (saved) break;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    System.err.println("[HTTP] Save attempt " + attempt + " failed: " + e.getMessage());
                }
            }
            
            if (saved) {
                db.updateExecutionTime(model, technique, transcriptId, executionTimeMs);
                System.out.println("[HTTP] ✅ Async experiment completed: " + model + "/" + technique +
                                 " transcript=" + transcriptId + " time=" + executionTimeMs + "ms");
            } else {
                System.err.println("[HTTP] ❌ Failed to save experiment after " + MAX_RETRIES + " attempts");
            }

        } catch (TimeoutException e) {
            System.err.println("[HTTP] ⏰ Async experiment timed out: " + model + "/" + 
                             technique + " transcript=" + transcriptId);
            // Try to save as failed with timeout error
            try {
                DatabaseManager db = new DatabaseManager();
                JSONObject errorObj = new JSONObject();
                errorObj.put("error", "Timeout after " + LLM_TIMEOUT_SECONDS + " seconds");
                db.saveExperimentResult(model, technique, errorObj.toString(), transcriptId);
            } catch (Exception ex) {
                System.err.println("[HTTP] Failed to save timeout error: " + ex.getMessage());
            }
            
        } catch (Exception e) {
            System.err.println("[HTTP] ❌ Async experiment error: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * Run experiment synchronously with timeout
     */
    private static String runExperimentSync(String model, String technique, 
                                           int transcriptId, String transcript) 
                                           throws Exception {
        System.out.println("[HTTP] Sync experiment starting: " + model + "/" + 
                         technique + " transcript=" + transcriptId);
        
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            try {
                PromptManager pm = new PromptManager();
                String fullPrompt = pm.buildFullPrompt(technique, transcript);
                
                LLMAnalyzer analyzer = new LLMAnalyzer();
                return analyzer.analyze(fullPrompt, model);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        
        try {
            String result = future.get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            
            if (result == null || result.trim().isEmpty()) {
                throw new Exception("Empty response from LLM");
            }
            
            // Validate JSON structure
            String validated = validateAndCleanResponse(result);
            if (validated == null) {
                throw new Exception("Invalid JSON response from LLM");
            }
            
            // Save to database
            DatabaseManager db = new DatabaseManager();
            boolean saved = db.saveExperimentResult(model, technique, result, transcriptId);
            
            if (!saved) {
                throw new Exception("Failed to save to database");
            }
            
            System.out.println("[HTTP] ✅ Sync experiment completed: " + model + "/" + 
                             technique + " transcript=" + transcriptId);
            
            return validated;
            
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("Experiment timed out after " + LLM_TIMEOUT_SECONDS + " seconds");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new Exception("LLM error: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * Call LLM with timeout protection
     */
    private static String callLLMWithTimeout(LLMAnalyzer analyzer, String prompt, String model) 
            throws TimeoutException, Exception {
        
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<String> future = executor.submit(() -> {
            try {
                return analyzer.analyze(prompt, model);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        
        try {
            return future.get(LLM_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("LLM call timed out after " + LLM_TIMEOUT_SECONDS + " seconds");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            throw new Exception("LLM error: " + (cause != null ? cause.getMessage() : e.getMessage()));
        } finally {
            executor.shutdownNow();
        }
    }

    // ================================================================
    // RESPONSE VALIDATION
    // ================================================================

    /**
     * Validate and clean response to ensure it contains JSON
     */
    private static String validateAndCleanResponse(String response) {
        if (response == null || response.trim().isEmpty()) {
            return null;
        }
        
        String trimmed = response.trim();
        
        // Check if it contains JSON structure
        boolean hasOpenBrace = trimmed.contains("{");
        boolean hasOpenBracket = trimmed.contains("[");
        
        if (!hasOpenBrace && !hasOpenBracket) {
            System.err.println("[HTTP] Response contains no JSON structure");
            return null;
        }
        
        // Try to extract JSON if it's embedded in text
        String extracted = extractJSONFromResponse(trimmed);
        
        if (extracted != null) {
            // Verify it's valid JSON (or at least looks like it)
            if (extracted.startsWith("{") || extracted.startsWith("[")) {
                return extracted;
            }
        }
        
        // If the response itself starts with JSON, use it
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            // Truncate if too long
            if (trimmed.length() > MAX_RESPONSE_LENGTH) {
                return trimmed.substring(0, MAX_RESPONSE_LENGTH);
            }
            return trimmed;
        }
        
        // Try to find JSON-like pattern
        Pattern jsonPattern = Pattern.compile("\\{[^}]*\\}|\\[[^\\]]*\\]");
        java.util.regex.Matcher matcher = jsonPattern.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group();
        }
        
        return null;
    }

    /**
     * Extract JSON from response using simple pattern matching
     */
    private static String extractJSONFromResponse(String response) {
        // Find first occurrence of { or [
        int start = -1;
        int end = -1;
        
        int firstBrace = response.indexOf('{');
        int firstBracket = response.indexOf('[');
        
        if (firstBrace >= 0 && (firstBracket < 0 || firstBrace < firstBracket)) {
            start = firstBrace;
            // Find matching closing brace
            int braceCount = 0;
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
                if (c == '"' && !escaped) {
                    inString = !inString;
                    continue;
                }
                if (!inString) {
                    if (c == '{') braceCount++;
                    else if (c == '}') {
                        braceCount--;
                        if (braceCount == 0) {
                            end = i;
                            break;
                        }
                    }
                }
            }
        } else if (firstBracket >= 0) {
            start = firstBracket;
            // Find matching closing bracket
            int bracketCount = 0;
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
                if (c == '"' && !escaped) {
                    inString = !inString;
                    continue;
                }
                if (!inString) {
                    if (c == '[') bracketCount++;
                    else if (c == ']') {
                        bracketCount--;
                        if (bracketCount == 0) {
                            end = i;
                            break;
                        }
                    }
                }
            }
        }
        
        if (start >= 0 && end > start) {
            String extracted = response.substring(start, end + 1);
            // Truncate if too long
            if (extracted.length() > MAX_RESPONSE_LENGTH) {
                return extracted.substring(0, MAX_RESPONSE_LENGTH);
            }
            return extracted;
        }
        
        return null;
    }

    // ================================================================
    // HELPERS
    // ================================================================

    private static void sendJson(HttpExchange ex, int statusCode, String json)
            throws IOException {
        byte[] bytes = json.getBytes("UTF-8");
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(statusCode, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.getResponseBody().close();
    }

    private static void sendError(HttpExchange ex, int statusCode, String message)
            throws IOException {
        JSONObject error = new JSONObject();
        error.put("error", message);
        error.put("status", statusCode);
        error.put("timestamp", System.currentTimeMillis());
        sendJson(ex, statusCode, error.toString());
    }

    private static String rootCauseMessage(Throwable error) {
        Throwable root = error;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        String message = root.getMessage();
        return (message == null || message.trim().isEmpty())
                ? root.getClass().getSimpleName()
                : message;
    }

    private static String exportFileName(String layer) {
        switch (layer.toUpperCase()) {
            case "LAYER 1A": return "layer1a_exact_match.csv";
            case "LAYER 1B": return "layer1b_text_similarity.csv";
            case "LAYER 2A": return "layer2a_numeric_quantity.csv";
            case "LAYER 2B": return "layer2b_numeric_nutrition.csv";
            case "LAYER 2C": return "layer2c_nutrition_totals.csv";
            case "LAYER 3A": return "layer3a_json_validity.csv";
            case "LAYER 3B": return "layer3b_hallucination.csv";
            case "LAYER 3C": return "layer3c_ingredient_detection.csv";
            case "LAYER 4": return "layer4_human_evaluation.csv";
            case "LAYER 5": return "layer5_condition_scores.csv";
            case "EXECUTION TIME": return "execution_time_summary.csv";
            default: return "export.csv";
        }
    }

    private static void addCorsHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type, Authorization");
        ex.getResponseHeaders().set("Access-Control-Max-Age", "86400");
    }

    private static int parseQueryParam(HttpExchange ex, String key, int defaultVal) {
        String query = ex.getRequestURI().getRawQuery();
        if (query == null) return defaultVal;
        for (String part : query.split("&")) {
            if (part.startsWith(key + "=")) {
                try { 
                    return Integer.parseInt(part.split("=")[1]); 
                } catch (NumberFormatException ignored) {}
            }
        }
        return defaultVal;
    }
}