package edu.utem.ftmk.database;

import edu.utem.ftmk.evaluation.IngredientComparisonService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;

public class DatabaseManager {

    // ================================================================
    // EXECUTION TIME METHODS
    // ================================================================
    
    public void updateExecutionTime(String modelTag, String technique, int transcriptId, long executionTimeMs) {
        String sql = "UPDATE experiment e " +
                     "JOIN llm_model m ON e.model_id = m.model_id " +
                     "JOIN prompt_technique pt ON e.technique_id = pt.technique_id " +
                     "SET e.execution_time_ms = ? " +
                     "WHERE m.model_tag = ? AND pt.technique_name = ? AND e.transcript_id = ? " +
                     "AND e.status = 'completed'";
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setLong(1, executionTimeMs);
            ps.setString(2, modelTag);
            ps.setString(3, technique);
            ps.setInt(4, transcriptId);
            
            int updated = ps.executeUpdate();
            
            if (updated > 0) {
                System.out.println("[DB] ✅ Updated execution time: " + executionTimeMs + "ms");
            } else {
                System.err.println("[DB] ⚠️ No completed experiment found to update execution time");
            }
            
        } catch (Exception e) {
            System.err.println("[DB] updateExecutionTime error: " + e.getMessage());
        }
    }

    public JSONArray getExecutionTimeSummary() {
        JSONArray results = new JSONArray();
        
        String sql = "SELECT " +
                     "m.model_name, " +
                     "m.model_tag, " +
                     "pt.technique_name, " +
                     "COUNT(*) as runs, " +
                     "ROUND(AVG(e.execution_time_ms) / 1000, 2) as avg_seconds, " +
                     "ROUND(MIN(e.execution_time_ms) / 1000, 2) as min_seconds, " +
                     "ROUND(MAX(e.execution_time_ms) / 1000, 2) as max_seconds " +
                     "FROM experiment e " +
                     "JOIN llm_model m ON e.model_id = m.model_id " +
                     "JOIN prompt_technique pt ON e.technique_id = pt.technique_id " +
                     "WHERE e.status = 'completed' " +
                     "AND e.execution_time_ms IS NOT NULL " +
                     "GROUP BY m.model_id, m.model_name, m.model_tag, pt.technique_id, pt.technique_name " +
                     "ORDER BY m.model_name, pt.technique_name";
        
        try (Connection conn = DatabaseConnection.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("model_name", rs.getString("model_name"));
                row.put("model_tag", rs.getString("model_tag"));
                row.put("technique_name", rs.getString("technique_name"));
                row.put("runs", rs.getInt("runs"));
                row.put("avg_seconds", rs.getDouble("avg_seconds"));
                row.put("min_seconds", rs.getDouble("min_seconds"));
                row.put("max_seconds", rs.getDouble("max_seconds"));
                results.put(row);
            }
            
            System.out.println("[DB] Retrieved " + results.length() + " execution time summaries");
            
        } catch (Exception e) {
            System.err.println("[DB] getExecutionTimeSummary error: " + e.getMessage());
        }
        
        return results;
    }

    // ================================================================
    // ENHANCED JSON EXTRACTION METHODS (NO REGEX!)
    // ================================================================
    
    private String extractJSON(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            System.err.println("[DB] ⚠️ Raw input is null or empty");
            return null;
        }

        // Strategy 1: Balanced braces (most reliable)
        String result = extractWithBalancedBraces(raw);
        if (result != null) {
            System.out.println("[DB] ✅ Extracted JSON using balanced braces");
            return result;
        }

        // Strategy 2: Aggressive cleaning
        result = extractAggressive(raw);
        if (result != null) {
            System.out.println("[DB] ✅ Extracted JSON using aggressive cleaning");
            return result;
        }

        // Strategy 3: Fuzzy matching (last resort)
        result = extractFuzzyJSON(raw);
        if (result != null) {
            System.out.println("[DB] ✅ Extracted JSON using fuzzy matching");
            return result;
        }

        System.err.println("[DB] ❌ All extraction strategies failed");
        return null;
    }

    /**
     * Extract JSON using balanced brace counting - O(n) complexity, no regex!
     */
    private String extractWithBalancedBraces(String raw) {
        String cleaned = raw.trim();
        
        // Remove markdown code blocks
        cleaned = cleaned.replaceAll("(?s)```json\\s*", "")
                         .replaceAll("(?s)```\\s*", "")
                         .replaceAll("(?s)```\\s*$", "")
                         .trim();
        
        // Find first opening brace or bracket
        int firstBrace = cleaned.indexOf('{');
        if (firstBrace < 0) {
            int firstBracket = cleaned.indexOf('[');
            if (firstBracket >= 0) {
                return extractArrayBalanced(cleaned, firstBracket);
            }
            System.err.println("[DB] No opening brace or bracket found");
            return null;
        }
        
        // Start from first brace
        cleaned = cleaned.substring(firstBrace);
        
        // Simple counter-based extraction - O(n), safe for large inputs
        int braceCount = 0;
        boolean inString = false;
        boolean escape = false;
        int lastBrace = -1;
        
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            
            if (escape) {
                escape = false;
                continue;
            }
            
            if (c == '\\') {
                escape = true;
                continue;
            }
            
            if (c == '"' && !escape) {
                inString = !inString;
                continue;
            }
            
            if (!inString) {
                if (c == '{') {
                    braceCount++;
                } else if (c == '}') {
                    braceCount--;
                    if (braceCount == 0) {
                        lastBrace = i;
                        break;
                    }
                }
            }
        }
        
        if (lastBrace > 0) {
            String extracted = cleaned.substring(0, lastBrace + 1);
            if (extracted.length() > 2 && extracted.contains(":")) {
                return extracted;
            }
        }
        
        return null;
    }

    /**
     * Extract array using balanced bracket counting - O(n) complexity, no regex!
     */
    private String extractArrayBalanced(String cleaned, int start) {
        int braceCount = 0;
        boolean inString = false;
        boolean escape = false;
        int lastBracket = -1;
        
        for (int i = start; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            
            if (escape) {
                escape = false;
                continue;
            }
            
            if (c == '\\') {
                escape = true;
                continue;
            }
            
            if (c == '"' && !escape) {
                inString = !inString;
                continue;
            }
            
            if (!inString) {
                if (c == '[') {
                    braceCount++;
                } else if (c == ']') {
                    braceCount--;
                    if (braceCount == 0) {
                        lastBracket = i;
                        break;
                    }
                }
            }
        }
        
        if (lastBracket > 0) {
            return cleaned.substring(start, lastBracket + 1);
        }
        return null;
    }

    /**
     * Aggressive extraction - tries to find JSON by removing markdown and code blocks
     */
    private String extractAggressive(String raw) {
        int start = raw.indexOf('{');
        if (start < 0) {
            start = raw.indexOf('[');
            if (start < 0) return null;
        }
        
        String candidate = raw.substring(start);
        
        int lastBrace = candidate.lastIndexOf('}');
        int lastBracket = candidate.lastIndexOf(']');
        int end = Math.max(lastBrace, lastBracket);
        
        if (end > 0) {
            candidate = candidate.substring(0, end + 1);
        }
        
        // Remove markdown and HTML tags
        candidate = candidate.replaceAll("(?s)<[^>]+>", "")
                            .replaceAll("(?s)```[a-z]*", "")
                            .replaceAll("(?s)```", "");
        
        // Remove "json:" or "JSON:" prefixes
        candidate = candidate.replaceAll("(?i)^\\s*(?:json|JSON)\\s*[:=]\\s*", "");
        
        return candidate.trim().length() > 5 ? candidate.trim() : null;
    }

    /**
     * Fuzzy extraction - last resort for malformed JSON
     */
    private String extractFuzzyJSON(String raw) {
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        
        if (start >= 0 && end > start) {
            String candidate = raw.substring(start, end + 1);
            
            // Count braces and fix missing closing braces
            int openBraces = 0;
            int closeBraces = 0;
            for (char c : candidate.toCharArray()) {
                if (c == '{') openBraces++;
                else if (c == '}') closeBraces++;
            }
            
            if (openBraces > closeBraces) {
                for (int i = 0; i < openBraces - closeBraces; i++) {
                    candidate += "}";
                }
            }
            
            // Quick validation - check if it has key-value pairs
            if (candidate.length() > 3 && candidate.contains(":")) {
                return candidate;
            }
        }
        
        return null;
    }

    // ================================================================
    // FIX COMMON JSON ISSUES
    // ================================================================
    
    private String fixCommonJSONIssues(String json) {
        if (json == null) return null;
        
        String fixed = json;
        
        // Remove trailing commas
        fixed = fixed.replaceAll(",\\s*}", "}")
                     .replaceAll(",\\s*]", "]");
        
        // Fix single quotes for property names
        fixed = fixed.replaceAll("'(\\w+)'\\s*:", "\"$1\":");
        
        // Fix single quotes for values
        fixed = fixed.replaceAll(":\\s*'([^']*)'", ": \"$1\"");
        
        // Add quotes around unquoted property names
        fixed = fixed.replaceAll("(\\{|,)\\s*(\\w+)\\s*:", "$1\"$2\":");
        
        // Remove common prefixes
        fixed = fixed.replaceAll("(?i)\"json\"\\s*:\\s*", "");
        fixed = fixed.replaceAll("(?i)\"response\"\\s*:\\s*", "");
        
        // Quote unquoted values (like null, true, false, numbers are fine)
        fixed = fixed.replaceAll(":\\s*([A-Za-z][A-Za-z0-9_]*)\\s*(,|})", ": \"$1\"$2");
        
        // Fix newlines in strings
        fixed = fixNewlinesInStrings(fixed);
        
        // Remove BOM if present
        fixed = fixed.replace("\uFEFF", "").trim();
        
        return fixed;
    }

    private String fixNewlinesInStrings(String json) {
        if (json == null || json.isEmpty()) {
            return json;
        }
        
        StringBuilder result = new StringBuilder(json.length());
        boolean inString = false;
        boolean escape = false;
        
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            
            if (escape) {
                escape = false;
                result.append(c);
                continue;
            }
            
            if (c == '\\') {
                escape = true;
                result.append(c);
                continue;
            }
            
            if (c == '"') {
                inString = !inString;
                result.append(c);
                continue;
            }
            
            if (inString) {
                switch (c) {
                    case '\n': result.append("\\n"); break;
                    case '\r': result.append("\\r"); break;
                    case '\t': result.append("\\t"); break;
                    default: result.append(c); break;
                }
            } else {
                result.append(c);
            }
        }
        
        return result.toString();
    }

    // ================================================================
    // VALIDATE JSON STRUCTURE
    // ================================================================
    
    private boolean isValidJSONStructure(String json) {
        if (json == null || json.trim().isEmpty()) return false;
        
        try {
            new JSONObject(json);
            return true;
        } catch (Exception e1) {
            try {
                new JSONArray(json);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    private JSONObject parseJSONWithFallback(String json) {
        if (json == null) return null;
        
        try {
            return new JSONObject(json);
        } catch (Exception e) {
            System.out.println("[DB] Direct parse failed: " + e.getMessage());
        }
        
        String fixed = fixCommonJSONIssues(json);
        try {
            return new JSONObject(fixed);
        } catch (Exception e) {
            System.out.println("[DB] Parse after fixes still failed: " + e.getMessage());
        }
        
        try {
            String extracted = extractJSON(fixed);
            if (extracted != null && !extracted.equals(json)) {
                try {
                    return new JSONObject(extracted);
                } catch (Exception e2) {
                    // Ignore
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        
        try {
            String closed = closeIncompleteJSON(json);
            if (closed != null) {
                try {
                    return new JSONObject(closed);
                } catch (Exception e) {
                    // Ignore
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        
        return null;
    }

    private String closeIncompleteJSON(String json) {
        if (json == null) return null;
        
        int openBraces = 0;
        int closeBraces = 0;
        for (char c : json.toCharArray()) {
            if (c == '{') openBraces++;
            else if (c == '}') closeBraces++;
        }
        
        if (openBraces > closeBraces) {
            StringBuilder closed = new StringBuilder(json);
            for (int i = 0; i < openBraces - closeBraces; i++) {
                closed.append("}");
            }
            return closed.toString();
        }
        return null;
    }

    // ================================================================
    // SAVE EXPERIMENT RESULT WITH IDEMPOTENT RETRY + JSON VALIDATION
    // ================================================================

    private static final Object SAVE_EXPERIMENT_LOCK = new Object();

    public boolean saveExperimentResult(String modelTag, String techniqueName,
                                       String rawJson, int transcriptId) {
        synchronized (SAVE_EXPERIMENT_LOCK) {
            if (rawJson == null || rawJson.trim().isEmpty()) {
                System.err.println("[DB] Raw JSON is null or empty");
                return false;
            }

            System.out.println("[DB] Processing response (first 300 chars): " +
                    rawJson.substring(0, Math.min(300, rawJson.length())));

            try (Connection conn = DatabaseConnection.getConnection()) {
                conn.setAutoCommit(false);

                try {
                    int modelId = getId(conn,
                            "SELECT model_id FROM llm_model WHERE model_tag = ?",
                            modelTag);

                    int techniqueId = getId(conn,
                            "SELECT technique_id FROM prompt_technique WHERE technique_name = ?",
                            techniqueName);

                    ExperimentStatus existing =
                            checkExistingExperiment(conn, transcriptId, modelId, techniqueId);

                    if (existing != null) {
                        String status = normalizeStatus(existing.status);

                        if ("completed".equals(status)) {
                            System.out.println("[DB] Experiment already completed. Skipping ID: "
                                    + existing.experimentId);
                            conn.commit();
                            return true;
                        }

                        if ("running".equals(status)) {
                            System.out.println("[DB] Experiment is running. Skipping ID: "
                                    + existing.experimentId);
                            conn.rollback();
                            return false;
                        }

                        if ("failed".equals(status)) {
                            System.out.println("[DB] Retrying failed experiment ID: "
                                    + existing.experimentId);

                            JSONObject json = parseAndValidateNutritionJson(rawJson);

                            if (json == null) {
                                updateFailedExperiment(conn, existing.experimentId, rawJson,
                                        "Invalid or non-nutrition JSON response");
                                conn.commit();
                                return false;
                            }

                            boolean success = overwriteExistingExperiment(
                                    conn,
                                    existing.experimentId,
                                    json,
                                    json.toString()
                            );

                            if (!success) {
                                updateFailedExperiment(conn, existing.experimentId, rawJson,
                                        "Failed to overwrite existing failed experiment");
                                conn.commit();
                                return false;
                            }

                            conn.commit();
                            return true;
                        }
                    }

                    JSONObject json = parseAndValidateNutritionJson(rawJson);

                    if (json == null) {
                        System.err.println("[DB] Invalid JSON. Saving new experiment as failed.");
                        saveAsInvalid(conn, rawJson, transcriptId, modelId, techniqueId,
                                "Invalid or non-nutrition JSON response");
                        conn.commit();
                        return false;
                    }

                    boolean success = saveToDatabase(
                            conn,
                            json,
                            json.toString(),
                            transcriptId,
                            modelId,
                            techniqueId
                    );

                    conn.commit();
                    return success;

                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                }

            } catch (Exception e) {
                System.err.println("[DB] saveExperimentResult error: " + e.getMessage());
                e.printStackTrace();
                return false;
            }
        }
    }

    // ================================================================
    // EXPERIMENT STATUS CHECK BY REEL_ID USING JOIN
    // ================================================================

    private static class ExperimentStatus {
        int experimentId;
        String status;

        ExperimentStatus(int experimentId, String status) {
            this.experimentId = experimentId;
            this.status = status;
        }
    }

    private ExperimentStatus checkExistingExperiment(Connection conn, int transcriptId,
                                                    int modelId, int techniqueId)
            throws SQLException {

        String sql =
                "SELECT e.experiment_id, e.status " +
                "FROM experiment e " +
                "JOIN transcript existing_t ON e.transcript_id = existing_t.transcript_id " +
                "JOIN transcript current_t ON current_t.reel_id = existing_t.reel_id " +
                "WHERE current_t.transcript_id = ? " +
                "AND e.model_id = ? " +
                "AND e.technique_id = ? " +
                "ORDER BY " +
                "CASE LOWER(e.status) " +
                "WHEN 'completed' THEN 1 " +
                "WHEN 'failed' THEN 2 " +
                "WHEN 'running' THEN 3 " +
                "ELSE 4 END, " +
                "e.experiment_id DESC " +
                "LIMIT 1 " +
                "FOR UPDATE";

        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, transcriptId);
            ps.setInt(2, modelId);
            ps.setInt(3, techniqueId);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new ExperimentStatus(
                            rs.getInt("experiment_id"),
                            normalizeStatus(rs.getString("status"))
                    );
                }
            }
        }

        return null;
    }

    private String normalizeStatus(String status) {
        return status == null ? "" : status.trim().toLowerCase();
    }

    // ================================================================
    // JSON PARSING + NUTRITION SCHEMA VALIDATION
    // ================================================================

    private JSONObject parseAndValidateNutritionJson(String rawJson) {
        String extractedJson = extractJSON(rawJson);

        if (extractedJson == null) {
            System.err.println("[DB] Cannot extract JSON. No braces found.");
            return null;
        }

        String fixedJson = fixCommonJSONIssues(extractedJson);
        JSONObject json = parseJSONWithFallback(fixedJson);

        if (json == null) {
            System.err.println("[DB] JSON parse failed after extraction and repair.");
            return null;
        }

        injectNutritionDefaults(json);

        if (!isValidNutritionSchema(json)) {
            System.err.println("[DB] JSON is valid, but does not match nutrition schema.");
            return null;
        }

        return json;
    }

    private void injectNutritionDefaults(JSONObject json) {
        if (!json.has("recipe_name") || json.isNull("recipe_name")) {
            json.put("recipe_name", "Unknown Recipe");
        }

        if (!json.has("servings_estimated")) {
            json.put("servings_estimated", 1);
        }

        if (json.optJSONObject("amount_per_serving") == null) {
            json.put("amount_per_serving", new JSONObject());
        }

        if (json.optJSONObject("nutrition_total") == null) {
            json.put("nutrition_total", new JSONObject());
        }

        if (json.optJSONArray("ingredients") == null) {
            json.put("ingredients", new JSONArray());
        }
    }

    private boolean isValidNutritionSchema(JSONObject json) {
        if (json == null) return false;

        if (!json.has("recipe_name")) return false;
        if (!json.has("servings_estimated")) return false;
        if (json.optJSONObject("amount_per_serving") == null) return false;
        if (json.optJSONObject("nutrition_total") == null) return false;
        if (json.optJSONArray("ingredients") == null) return false;

        return true;
    }

    // ================================================================
    // OVERWRITE EXISTING FAILED EXPERIMENT
    // ================================================================

    private boolean overwriteExistingExperiment(Connection conn, int experimentId,
                                               JSONObject json, String cleanJson) {
        try {
            deleteExistingNutritionData(conn, experimentId);

            int resultId = insertValidNutritionResult(conn, experimentId, json, cleanJson);

            JSONArray ingredients = json.optJSONArray("ingredients");
            if (ingredients != null && ingredients.length() > 0) {
                insertIngredientResults(conn, resultId, ingredients);
                System.out.println("[DB] Saved " + ingredients.length() + " ingredients");
            } else {
                System.out.println("[DB] No ingredients found in JSON");
            }

            updateExperimentStatus(conn, experimentId, "completed");

            String recipeName = json.optString("recipe_name", "Unknown");
            JSONObject total = json.optJSONObject("nutrition_total");
            double calories = total != null ? total.optDouble("calories", 0) : 0;
            double protein = total != null ? total.optDouble("protein_g", 0) : 0;

            System.out.println("[DB] Successfully overwrote failed experiment: " + recipeName);
            System.out.println("[DB] Calories: " + calories + " | Protein: " + protein + "g");

            return true;

        } catch (Exception e) {
            System.err.println("[DB] Error overwriting experiment: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void deleteExistingNutritionData(Connection conn, int experimentId)
            throws SQLException {

        String deleteIngredients =
                "DELETE ir FROM ingredient_result ir " +
                "JOIN nutrition_result nr ON ir.result_id = nr.result_id " +
                "WHERE nr.experiment_id = ?";

        try (PreparedStatement ps = conn.prepareStatement(deleteIngredients)) {
            ps.setInt(1, experimentId);
            ps.executeUpdate();
        }

        String deleteNutrition =
                "DELETE FROM nutrition_result WHERE experiment_id = ?";

        try (PreparedStatement ps = conn.prepareStatement(deleteNutrition)) {
            ps.setInt(1, experimentId);
            ps.executeUpdate();
        }
    }

    private void updateFailedExperiment(Connection conn, int experimentId,
                                       String rawJson, String error)
            throws SQLException {

        updateExperimentStatus(conn, experimentId, "failed");

        String checkSql =
                "SELECT COUNT(*) FROM nutrition_result WHERE experiment_id = ?";

        try (PreparedStatement checkPs = conn.prepareStatement(checkSql)) {
            checkPs.setInt(1, experimentId);

            try (ResultSet rs = checkPs.executeQuery()) {
                rs.next();
                int count = rs.getInt(1);

                if (count == 0) {
                    insertInvalidNutritionResult(conn, experimentId, rawJson, error);
                } else {
                    String updateSql =
                            "UPDATE nutrition_result " +
                            "SET raw_json_output = ?, error_note = ?, json_valid = FALSE " +
                            "WHERE experiment_id = ?";

                    try (PreparedStatement ps = conn.prepareStatement(updateSql)) {
                        String raw = rawJson.length() > 10000
                                ? rawJson.substring(0, 10000) + "..."
                                : rawJson;

                        String errorMsg = error != null ? error : "Unknown parse error";
                        if (errorMsg.length() > 500) {
                            errorMsg = errorMsg.substring(0, 500) + "...";
                        }

                        ps.setString(1, raw);
                        ps.setString(2, errorMsg);
                        ps.setInt(3, experimentId);
                        ps.executeUpdate();
                    }
                }
            }
        }

        System.err.println("[DB] Updated failed experiment ID: " + experimentId);
    }

    // ================================================================
    // SAVE AS INVALID NEW EXPERIMENT
    // ================================================================

    private void saveAsInvalid(Connection conn, String rawJson, int transcriptId,
                              int modelId, int techniqueId, String error)
            throws SQLException {

        int experimentId = insertExperiment(conn, transcriptId, modelId, techniqueId);
        insertInvalidNutritionResult(conn, experimentId, rawJson, error);
        updateExperimentStatus(conn, experimentId, "failed");

        System.err.println("[DB] Saved invalid result for experiment ID: " + experimentId);
    }

    // ================================================================
    // SAVE VALID NEW EXPERIMENT
    // ================================================================

    private boolean saveToDatabase(Connection conn, JSONObject json, String extractedJson,
                                  int transcriptId, int modelId, int techniqueId) {
        try {
            int experimentId = insertExperiment(conn, transcriptId, modelId, techniqueId);

            int resultId = insertValidNutritionResult(conn, experimentId, json, extractedJson);

            JSONArray ingredients = json.optJSONArray("ingredients");
            if (ingredients != null && ingredients.length() > 0) {
                insertIngredientResults(conn, resultId, ingredients);
                System.out.println("[DB] Saved " + ingredients.length() + " ingredients");
            } else {
                System.out.println("[DB] No ingredients found in JSON");
            }

            updateExperimentStatus(conn, experimentId, "completed");

            String recipeName = json.optString("recipe_name", "Unknown");
            JSONObject total = json.optJSONObject("nutrition_total");
            double calories = total != null ? total.optDouble("calories", 0) : 0;
            double protein = total != null ? total.optDouble("protein_g", 0) : 0;

            System.out.println("[DB] Successfully saved: " + recipeName);
            System.out.println("[DB] Calories: " + calories + " | Protein: " + protein + "g");

            return true;

        } catch (Exception e) {
            System.err.println("[DB] Error saving to database: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    // ================================================================
    // TRANSCRIPT METHODS
    // ================================================================
    
    public JSONArray getTranscriptList() {
        JSONArray list = new JSONArray();
        String sql = "SELECT t.transcript_id, t.reel_id, t.file_name, " +
                "r.reel_id_instagram, i.name, i.instagram_account " +
                "FROM transcript t " +
                "LEFT JOIN reel r ON t.reel_id = r.reel_id " +
                "LEFT JOIN influencer i ON r.influencer_id = i.influencer_id " +
                "ORDER BY t.transcript_id";

        try (Connection conn = DatabaseConnection.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                JSONObject row = new JSONObject();
                int id = rs.getInt("transcript_id");
                row.put("transcript_id", id);
                row.put("reel_id", rs.getInt("reel_id"));
                row.put("file_name", rs.getString("file_name"));
                row.put("reel_id_instagram", safe(rs.getString("reel_id_instagram")));
                row.put("influencer_name", safe(rs.getString("name")));
                row.put("instagram_account", safe(rs.getString("instagram_account")));
                row.put("label", "#" + id + " - " + safe(rs.getString("reel_id_instagram")) + " - " + safe(rs.getString("instagram_account")));
                list.put(row);
            }
        } catch (Exception e) {
            System.err.println("[DB] getTranscriptList error: " + e.getMessage());
        }
        return list;
    }

    public JSONArray getAllTranscriptsForBatch() {
        JSONArray list = new JSONArray();
        String sql = "SELECT transcript_id, reel_id, audio_id, file_name, file_path FROM transcript ORDER BY transcript_id";
        try (Connection conn = DatabaseConnection.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("transcript_id", rs.getInt("transcript_id"));
                row.put("reel_id", rs.getInt("reel_id"));
                row.put("audio_id", rs.getInt("audio_id"));
                row.put("file_name", rs.getString("file_name"));
                row.put("file_path", rs.getString("file_path"));
                row.put("transcript_text", readTranscriptText(rs.getString("file_path"), rs.getString("file_name")));
                list.put(row);
            }
        } catch (Exception e) {
            System.err.println("[DB] getAllTranscriptsForBatch error: " + e.getMessage());
        }
        return list;
    }

    public String getTranscriptText(int transcriptId) {
        String sql = "SELECT file_path, file_name FROM transcript WHERE transcript_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, transcriptId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return readTranscriptText(rs.getString("file_path"), rs.getString("file_name"));
        } catch (Exception e) {
            System.err.println("[DB] getTranscriptText error: " + e.getMessage());
        }
        return "";
    }

    // ================================================================
    // DASHBOARD METHODS
    // ================================================================
    
    public JSONObject getDashboardStats() {
        JSONObject stats = new JSONObject();
        try (Connection conn = DatabaseConnection.getConnection()) {
            stats.put("transcripts", count(conn, "SELECT COUNT(*) FROM transcript"));
            stats.put("reels", count(conn, "SELECT COUNT(*) FROM reel"));
            stats.put("experiments", count(conn, "SELECT COUNT(*) FROM experiment"));
            stats.put("completed", count(conn, "SELECT COUNT(*) FROM experiment WHERE status='completed'"));
            stats.put("json_valid", count(conn, "SELECT COUNT(*) FROM nutrition_result WHERE json_valid=TRUE"));
        } catch (Exception e) {
            System.err.println("[DB] getDashboardStats error: " + e.getMessage());
        }
        return stats;
    }

    public JSONArray getRecentExperiments() {
        JSONArray rows = new JSONArray();

        String sql =
            "SELECT " +
            "e.experiment_id, " +
            "e.transcript_id, " +
            "r.reel_id_instagram, " +
            "m.model_name, " +
            "m.model_tag, " +
            "pt.technique_name, " +
            "e.status, " +
            "e.executed_at, " +
            "nr.json_valid, " +
            "nr.recipe_name, " +

            // Model nutrition totals
            "nr.total_calories AS model_total_calories, " +
            "nr.total_protein_g AS model_total_protein_g, " +
            "nr.total_fat_g AS model_total_fat_g, " +
            "nr.total_carbohydrate_g AS model_total_carbohydrate_g, " +

            // Annotator nutrition totals
            "gt.gt_total_calories, " +
            "gt.gt_total_protein_g, " +
            "gt.gt_total_fat_g, " +
            "gt.gt_total_carbohydrate_g " +

            "FROM experiment e " +

            "JOIN transcript t " +
            "ON e.transcript_id = t.transcript_id " +

            "LEFT JOIN reel r " +
            "ON t.reel_id = r.reel_id " +

            "JOIN llm_model m " +
            "ON e.model_id = m.model_id " +

            "JOIN prompt_technique pt " +
            "ON e.technique_id = pt.technique_id " +

            "LEFT JOIN nutrition_result nr " +
            "ON e.experiment_id = nr.experiment_id " +

            // Sum nutrition values entered by annotators
            "LEFT JOIN (" +
            "   SELECT " +
            "       gtr.transcript_id, " +
            "       SUM(gti.calories) AS gt_total_calories, " +
            "       SUM(gti.protein_g) AS gt_total_protein_g, " +
            "       SUM(gti.total_fat_g) AS gt_total_fat_g, " +
            "       SUM(gti.total_carbohydrate_g) " +
            "           AS gt_total_carbohydrate_g " +
            "   FROM ground_truth_reel gtr " +
            "   JOIN ground_truth_ingredient gti " +
            "       ON gtr.gt_reel_id = gti.gt_reel_id " +
            "   WHERE LOWER(TRIM(gti.annotation_layer)) = 'layer1' " +
            "   GROUP BY gtr.transcript_id " +
            ") gt ON gt.transcript_id = e.transcript_id " +

            "ORDER BY e.experiment_id DESC";

        try (
            Connection conn = DatabaseConnection.getConnection();
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery(sql)
        ) {
            IngredientComparisonService comparisonService =
                    new IngredientComparisonService();

            while (rs.next()) {
                JSONObject row = new JSONObject();

                int experimentId =
                        rs.getInt("experiment_id");

                String status =
                        safe(rs.getString("status"));

                row.put("experiment_id", experimentId);
                row.put(
                    "transcript_id",
                    rs.getInt("transcript_id")
                );

                row.put(
                    "reel_id_instagram",
                    safe(rs.getString("reel_id_instagram"))
                );

                row.put(
                    "model_name",
                    safe(rs.getString("model_name"))
                );

                row.put(
                    "model_tag",
                    safe(rs.getString("model_tag"))
                );

                row.put(
                    "technique_name",
                    safe(rs.getString("technique_name"))
                );

                row.put("status", status);

                row.put(
                    "executed_at",
                    safe(rs.getString("executed_at"))
                );

                row.put(
                    "json_valid",
                    rs.getBoolean("json_valid")
                );

                row.put(
                    "recipe_name",
                    safe(rs.getString("recipe_name"))
                );

                row.put(
                    "model_total_calories",
                    nullableNumber(
                        rs,
                        "model_total_calories"
                    )
                );

                row.put(
                    "model_total_protein_g",
                    nullableNumber(
                        rs,
                        "model_total_protein_g"
                    )
                );

                row.put(
                    "model_total_fat_g",
                    nullableNumber(
                        rs,
                        "model_total_fat_g"
                    )
                );

                row.put(
                    "model_total_carbohydrate_g",
                    nullableNumber(
                        rs,
                        "model_total_carbohydrate_g"
                    )
                );

                row.put(
                    "gt_total_calories",
                    nullableNumber(
                        rs,
                        "gt_total_calories"
                    )
                );

                row.put(
                    "gt_total_protein_g",
                    nullableNumber(
                        rs,
                        "gt_total_protein_g"
                    )
                );

                row.put(
                    "gt_total_fat_g",
                    nullableNumber(
                        rs,
                        "gt_total_fat_g"
                    )
                );

                row.put(
                    "gt_total_carbohydrate_g",
                    nullableNumber(
                        rs,
                        "gt_total_carbohydrate_g"
                    )
                );

                /*
                 * Use the same ingredient hallucination
                 * calculation as the detail window.
                 */
                if ("completed".equalsIgnoreCase(status)) {
                    try {
                        JSONArray modelIngredients =
                            getModelIngredientsForExperiment(
                                experimentId
                            );

                        JSONArray groundTruthIngredients =
                            getGroundTruthIngredientsForExperiment(
                                experimentId
                            );

                        if (modelIngredients.length() > 0
                                && groundTruthIngredients.length() > 0) {

                            JSONObject comparison =
                                comparisonService.compare(
                                    modelIngredients,
                                    groundTruthIngredients
                                );

                            row.put(
                                "ingredient_hallucination_rate",
                                comparison.getDouble(
                                    "hallucination_rate"
                                )
                            );

                        } else {
                            row.put(
                                "ingredient_hallucination_rate",
                                JSONObject.NULL
                            );
                        }

                    } catch (Exception evaluationError) {
                        row.put(
                            "ingredient_hallucination_rate",
                            JSONObject.NULL
                        );

                        System.err.println(
                            "[DB] Hallucination evaluation error "
                            + "for experiment "
                            + experimentId
                            + ": "
                            + evaluationError.getMessage()
                        );
                    }

                } else {
                    row.put(
                        "ingredient_hallucination_rate",
                        JSONObject.NULL
                    );
                }

                rows.put(row);
            }

        } catch (Exception e) {
            System.err.println(
                "[DB] getRecentExperiments error: "
                + e.getMessage()
            );
            e.printStackTrace();
        }

        return rows;
    }

    public JSONObject getBatchStatus(String modelTag) {
        JSONObject obj = new JSONObject();
        try (Connection conn = DatabaseConnection.getConnection()) {
            int transcripts = count(conn, "SELECT COUNT(*) FROM transcript");
            int total = transcripts * 4;
            int done;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM experiment e JOIN llm_model m ON e.model_id=m.model_id WHERE m.model_tag=? AND e.status='completed'")) {
                ps.setString(1, modelTag);
                ResultSet rs = ps.executeQuery();
                rs.next();
                done = rs.getInt(1);
            }
            obj.put("model", modelTag);
            obj.put("total", total);
            obj.put("done", done);
            obj.put("pending", Math.max(0, total - done));
            obj.put("pct", total == 0 ? 0 : Math.round(done * 100.0 / total));
        } catch (Exception e) {
            System.err.println("[DB] getBatchStatus error: " + e.getMessage());
        }
        return obj;
    }

    // ================================================================
    // EXPERIMENT DETAIL METHODS
    // ================================================================
    
    public JSONArray getExperimentsForTranscript(int transcriptId) {
        JSONArray rows = new JSONArray();
        String sql = "SELECT e.experiment_id, e.transcript_id, " +
                "m.model_name, m.model_tag, pt.technique_name, e.status, e.executed_at, " +
                "nr.json_valid, nr.recipe_name, nr.total_calories, nr.total_protein_g " +
                "FROM experiment e " +
                "JOIN llm_model m ON e.model_id = m.model_id " +
                "JOIN prompt_technique pt ON e.technique_id = pt.technique_id " +
                "LEFT JOIN nutrition_result nr ON e.experiment_id = nr.experiment_id " +
                "WHERE e.transcript_id = ? " +
                "ORDER BY e.experiment_id DESC";
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, transcriptId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("experiment_id", rs.getInt("experiment_id"));
                row.put("transcript_id", rs.getInt("transcript_id"));
                row.put("model_name", safe(rs.getString("model_name")));
                row.put("model_tag", safe(rs.getString("model_tag")));
                row.put("technique_name", safe(rs.getString("technique_name")));
                row.put("status", safe(rs.getString("status")));
                row.put("executed_at", safe(rs.getString("executed_at")));
                row.put("json_valid", rs.getBoolean("json_valid"));
                row.put("recipe_name", safe(rs.getString("recipe_name")));
                row.put("total_calories", rs.getDouble("total_calories"));
                row.put("total_protein_g", rs.getDouble("total_protein_g"));
                rows.put(row);
            }
        } catch (Exception e) {
            System.err.println("[DB] getExperimentsForTranscript error: " + e.getMessage());
        }
        return rows;
    }

    public JSONObject getExperimentDetail(int experimentId) {
        JSONObject detail = new JSONObject();
        String sql = "SELECT e.*, m.model_name, m.model_tag, pt.technique_name, " +
                "nr.*, t.file_name " +
                "FROM experiment e " +
                "JOIN llm_model m ON e.model_id = m.model_id " +
                "JOIN prompt_technique pt ON e.technique_id = pt.technique_id " +
                "LEFT JOIN nutrition_result nr ON e.experiment_id = nr.experiment_id " +
                "LEFT JOIN transcript t ON e.transcript_id = t.transcript_id " +
                "WHERE e.experiment_id = ?";
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, experimentId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                detail.put("experiment_id", rs.getInt("experiment_id"));
                detail.put("transcript_id", rs.getInt("transcript_id"));
                detail.put("model_name", safe(rs.getString("model_name")));
                detail.put("model_tag", safe(rs.getString("model_tag")));
                detail.put("technique_name", safe(rs.getString("technique_name")));
                detail.put("status", safe(rs.getString("status")));
                detail.put("executed_at", safe(rs.getString("executed_at")));
                detail.put("execution_time_ms", rs.getLong("execution_time_ms"));
                detail.put("recipe_name", safe(rs.getString("recipe_name")));
                detail.put("total_calories", rs.getDouble("total_calories"));
                detail.put("total_protein_g", rs.getDouble("total_protein_g"));
                detail.put("servings_estimated", rs.getInt("servings_estimated"));

                	detail.put(
                	    "total_fat_g",
                	    rs.getDouble("total_fat_g")
                	);

                	detail.put(
                	    "total_carbohydrate_g",
                	    rs.getDouble("total_carbohydrate_g")
                	);

                	detail.put(
                	    "total_sodium_mg",
                	    rs.getDouble("total_sodium_mg")
                	);

                	detail.put(
                	    "total_fiber_g",
                	    rs.getDouble("total_fiber_g")
                	);

                	detail.put(
                	    "total_sugars_g",
                	    rs.getDouble("total_sugars_g")
                	);

                	detail.put(
                	    "total_cholesterol_mg",
                	    rs.getDouble("total_cholesterol_mg")
                	);
                detail.put("json_valid", rs.getBoolean("json_valid"));
                detail.put("raw_json_output", safe(rs.getString("raw_json_output")));
                detail.put("file_name", safe(rs.getString("file_name")));
                
                JSONArray modelIngredients =
                        getModelIngredientsForExperiment(experimentId);

                JSONArray groundTruthIngredients =
                        getGroundTruthIngredientsForExperiment(experimentId);

                if (modelIngredients.length() == 0
                        || groundTruthIngredients.length() == 0) {

                    detail.put("ingredients", modelIngredients);

                    detail.put(
                        "ground_truth_ingredients",
                        groundTruthIngredients
                    );

                    detail.put(
                        "omitted_ground_truth_ingredients",
                        groundTruthIngredients
                    );

                    detail.put("matched_count", 0);
                    detail.put("hallucinated_count", 0);

                    detail.put(
                        "omitted_count",
                        groundTruthIngredients.length()
                    );

                    detail.put("hallucination_evaluated", false);
                    detail.put("hallucination_rate", JSONObject.NULL);
                    detail.put("ingredient_recall", JSONObject.NULL);

                } else {
                    IngredientComparisonService comparisonService =
                            new IngredientComparisonService();

                    JSONObject comparison =
                            comparisonService.compare(
                                modelIngredients,
                                groundTruthIngredients
                            );

                    detail.put(
                        "ingredients",
                        comparison.getJSONArray("ingredients")
                    );

                    detail.put(
                        "ground_truth_ingredients",
                        comparison.getJSONArray(
                            "ground_truth_ingredients"
                        )
                    );

                    detail.put(
                        "omitted_ground_truth_ingredients",
                        comparison.getJSONArray(
                            "omitted_ground_truth_ingredients"
                        )
                    );

                    detail.put(
                        "matched_count",
                        comparison.getInt("matched_count")
                    );

                    detail.put(
                        "hallucinated_count",
                        comparison.getInt("hallucinated_count")
                    );

                    detail.put(
                        "omitted_count",
                        comparison.getInt("omitted_count")
                    );

                    detail.put("hallucination_evaluated", true);

                    detail.put(
                        "hallucination_rate",
                        comparison.getDouble("hallucination_rate")
                    );

                    detail.put(
                        "ingredient_recall",
                        comparison.getDouble("ingredient_recall")
                    );
                }
            }
        } catch (Exception e) {
            System.err.println(
                "[DB] getExperimentDetail error: " + e.getMessage()
            );
            e.printStackTrace();
        }
        return detail;
    }

    private JSONArray getIngredientsForResult(int resultId) {
        JSONArray ingredients = new JSONArray();
        String sql = "SELECT * FROM ingredient_result WHERE result_id = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, resultId);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                JSONObject ing = new JSONObject();
                ing.put("name_original", safe(rs.getString("name_original")));
                ing.put("name_en", safe(rs.getString("name_en")));
                ing.put("quantity_value", rs.getDouble("quantity_value"));
                ing.put("unit_original", safe(rs.getString("unit_original")));
                ing.put("unit_en", safe(rs.getString("unit_en")));
                ing.put("estimated_weight_g", rs.getDouble("estimated_weight_g"));
                ing.put("calories", rs.getDouble("calories"));
                ing.put("protein_g", rs.getDouble("protein_g"));
                ing.put("total_fat_g", rs.getDouble("total_fat_g"));
                ing.put("total_carbohydrate_g", rs.getDouble("total_carbohydrate_g"));
                ingredients.put(ing);
            }
        } catch (Exception e) {
            System.err.println("[DB] getIngredientsForResult error: " + e.getMessage());
        }
        return ingredients;
    }

    // ================================================================
    // CSV EXPORT METHODS
    // ================================================================

    /**
     * Exports the evaluation CSVs using the exact column order defined in
     * metrics_evaluation_queries.sql. Ingredient-based layers are generated
     * in Java so model ingredients are paired with the same ground-truth
     * ingredients used by IngredientComparisonService (avoids a SQL cartesian
     * product between all predicted and all ground-truth ingredients).
     */
    public void exportCsv(String layer, String tempPath) throws Exception {
        String normalized = layer == null ? "" : layer.trim().toUpperCase(Locale.ROOT);

        switch (normalized) {
            case "LAYER 1A":
            case "LAYER 1B":
            case "LAYER 2A":
            case "LAYER 2B":
            case "LAYER 3B":
            case "LAYER 3C":
            case "LAYER 5":
                exportEvaluationRows(normalized, tempPath);
                return;

            case "LAYER 4":
                // The supplied SQL specification marks Layer 4 as a placeholder.
                writeCsvRows(tempPath,
                        List.of("evaluation_id", "result_id", "experiment_id", "video_id",
                                "model_name", "technique_name", "annotator_id",
                                "fluency_score", "completeness_score", "plausibility_score",
                                "evaluated_at"),
                        Collections.emptyList());
                return;

            default:
                String sql = buildExportQuery(normalized);
                if (sql == null) {
                    throw new IllegalArgumentException("Unknown export layer: " + layer);
                }
                exportSqlCsv(sql, tempPath, normalized);
        }
    }

    private void exportSqlCsv(String sql, String tempPath, String layer) throws Exception {
        try (Connection conn = DatabaseConnection.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql);
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                     new FileOutputStream(tempPath), java.nio.charset.StandardCharsets.UTF_8))) {

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            writer.write('\uFEFF');
            for (int i = 1; i <= colCount; i++) {
                writer.write(escapeCsv(meta.getColumnLabel(i)));
                if (i < colCount) writer.write(',');
            }
            writer.newLine();

            while (rs.next()) {
                for (int i = 1; i <= colCount; i++) {
                    writer.write(escapeCsv(rs.getString(i)));
                    if (i < colCount) writer.write(',');
                }
                writer.newLine();
            }

            writer.flush();
            System.out.println("[DB] CSV exported successfully: " + tempPath);
        } catch (SQLException e) {
            System.err.println("[DB] exportCsv SQL error for " + layer + ": " + e.getMessage());
            throw e;
        }
    }

    private void exportEvaluationRows(String layer, String tempPath) throws Exception {
        List<JSONObject> experiments = getCompletedExperimentsForExport();
        List<List<Object>> rows = new ArrayList<>();
        List<String> headers;

        switch (layer) {
            case "LAYER 1A":
                headers = List.of(
                        "experiment_id", "transcript_id", "video_id", "model_name",
                        "technique_name", "rag_enabled", "gt_name_original", "gt_name_en",
                        "gt_unit_original", "gt_unit_en", "pred_name_original", "pred_name_en",
                        "pred_unit_original", "pred_unit_en");
                break;
            case "LAYER 1B":
                headers = List.of(
                        "experiment_id", "video_id", "model_name", "technique_name",
                        "rag_enabled", "gt_name_original", "gt_name_en",
                        "pred_name_original", "pred_name_en");
                break;
            case "LAYER 2A":
                headers = List.of(
                        "experiment_id", "video_id", "model_name", "technique_name",
                        "rag_enabled", "gt_quantity_value", "gt_weight_g",
                        "pred_quantity_value", "pred_weight_g");
                break;
            case "LAYER 2B":
                headers = List.of(
                        "experiment_id", "video_id", "model_name", "technique_name",
                        "rag_enabled", "gt_energy_kcal", "gt_protein_g", "gt_fat_g",
                        "gt_carbohydrate_g", "pred_energy_kcal", "pred_protein_g",
                        "pred_fat_g", "pred_carbohydrate_g");
                break;
            case "LAYER 3B":
                headers = List.of(
                        "experiment_id", "video_id", "model_name", "technique_name",
                        "rag_enabled", "pred_name_original", "pred_name_en", "is_hallucinated");
                break;
            case "LAYER 3C":
                headers = List.of(
                        "experiment_id", "video_id", "model_name", "technique_name",
                        "rag_enabled", "gt_ingredient_count", "pred_ingredient_count",
                        "true_positives", "false_positives");
                break;
            case "LAYER 5":
                headers = List.of(
                        "video_id", "model_name", "technique_name", "rag_enabled",
                        "pred_count", "true_positives", "false_positives", "gt_count",
                        "json_valid", "pred_total_kcal", "gt_total_kcal");
                break;
            default:
                throw new IllegalArgumentException("Unsupported evaluation layer: " + layer);
        }

        IngredientComparisonService comparisonService = new IngredientComparisonService();
        boolean useLayer2 = layer.equals("LAYER 2A") || layer.equals("LAYER 2B") || layer.equals("LAYER 5");
        String annotationLayer = useLayer2 ? "layer2" : "layer1";

        for (JSONObject experiment : experiments) {
            int experimentId = experiment.getInt("experiment_id");
            JSONArray predicted = getModelIngredientsForExperiment(experimentId);
            JSONArray groundTruth = getGroundTruthIngredientsForExperiment(experimentId, annotationLayer);
            JSONObject comparison = comparisonService.compare(predicted, groundTruth);
            JSONArray evaluated = comparison.getJSONArray("ingredients");

            if (layer.equals("LAYER 3C")) {
                rows.add(List.of(
                        experimentId,
                        csvValue(experiment, "video_id"),
                        csvValue(experiment, "model_name"),
                        csvValue(experiment, "technique_name"),
                        csvValue(experiment, "rag_enabled"),
                        comparison.getInt("total_ground_truth_ingredients"),
                        comparison.getInt("total_model_ingredients"),
                        comparison.getInt("matched_count"),
                        comparison.getInt("hallucinated_count")));
                continue;
            }

            if (layer.equals("LAYER 5")) {
                rows.add(List.of(
                        csvValue(experiment, "video_id"),
                        csvValue(experiment, "model_name"),
                        csvValue(experiment, "technique_name"),
                        csvValue(experiment, "rag_enabled"),
                        comparison.getInt("total_model_ingredients"),
                        comparison.getInt("matched_count"),
                        comparison.getInt("hallucinated_count"),
                        comparison.getInt("total_ground_truth_ingredients"),
                        csvValue(experiment, "json_valid"),
                        csvValue(experiment, "pred_total_kcal"),
                        sumJsonNumbers(groundTruth, "calories")));
                continue;
            }

            for (int i = 0; i < evaluated.length(); i++) {
                JSONObject pred = evaluated.getJSONObject(i);
                JSONObject gt = findGroundTruthById(
                        groundTruth,
                        pred.has("matched_gt_ingredient_id") && !pred.isNull("matched_gt_ingredient_id")
                                ? pred.optInt("matched_gt_ingredient_id", -1)
                                : -1);

                switch (layer) {
                    case "LAYER 1A":
                        rows.add(List.of(
                                experimentId,
                                experiment.getInt("transcript_id"),
                                csvValue(experiment, "video_id"),
                                csvValue(experiment, "model_name"),
                                csvValue(experiment, "technique_name"),
                                csvValue(experiment, "rag_enabled"),
                                csvValue(gt, "name_original"),
                                csvValue(gt, "name_en"),
                                csvValue(gt, "quantity_expression"),
                                csvValue(gt, "quantity_unit_culinary"),
                                csvValue(pred, "name_original"),
                                csvValue(pred, "name_en"),
                                csvValue(pred, "unit_original"),
                                csvValue(pred, "unit_en")));
                        break;
                    case "LAYER 1B":
                        rows.add(List.of(
                                experimentId,
                                csvValue(experiment, "video_id"),
                                csvValue(experiment, "model_name"),
                                csvValue(experiment, "technique_name"),
                                csvValue(experiment, "rag_enabled"),
                                csvValue(gt, "name_original"),
                                csvValue(gt, "name_en"),
                                csvValue(pred, "name_original"),
                                csvValue(pred, "name_en")));
                        break;
                    case "LAYER 2A":
                        rows.add(List.of(
                                experimentId,
                                csvValue(experiment, "video_id"),
                                csvValue(experiment, "model_name"),
                                csvValue(experiment, "technique_name"),
                                csvValue(experiment, "rag_enabled"),
                                csvValue(gt, "quantity_value"),
                                csvValue(gt, "estimated_weight_g"),
                                csvValue(pred, "quantity_value"),
                                csvValue(pred, "estimated_weight_g")));
                        break;
                    case "LAYER 2B":
                        rows.add(List.of(
                                experimentId,
                                csvValue(experiment, "video_id"),
                                csvValue(experiment, "model_name"),
                                csvValue(experiment, "technique_name"),
                                csvValue(experiment, "rag_enabled"),
                                csvValue(gt, "calories"),
                                csvValue(gt, "protein_g"),
                                csvValue(gt, "total_fat_g"),
                                csvValue(gt, "total_carbohydrate_g"),
                                csvValue(pred, "calories"),
                                csvValue(pred, "protein_g"),
                                csvValue(pred, "total_fat_g"),
                                csvValue(pred, "total_carbohydrate_g")));
                        break;
                    case "LAYER 3B":
                        rows.add(List.of(
                                experimentId,
                                csvValue(experiment, "video_id"),
                                csvValue(experiment, "model_name"),
                                csvValue(experiment, "technique_name"),
                                csvValue(experiment, "rag_enabled"),
                                csvValue(pred, "name_original"),
                                csvValue(pred, "name_en"),
                                pred.optBoolean("hallucinated", true)));
                        break;
                    default:
                        break;
                }
            }
        }

        writeCsvRows(tempPath, headers, rows);
        System.out.println("[DB] CSV exported successfully: " + tempPath);
    }

    private List<JSONObject> getCompletedExperimentsForExport() throws Exception {
        List<JSONObject> experiments = new ArrayList<>();
        String sql =
                "SELECT e.experiment_id, e.transcript_id, " +
                "r.reel_id_instagram AS video_id, m.model_name, pt.technique_name, " +
                "e.rag_enabled, nr.json_valid, nr.total_calories AS pred_total_kcal " +
                "FROM experiment e " +
                "JOIN transcript t ON e.transcript_id = t.transcript_id " +
                "JOIN reel r ON t.reel_id = r.reel_id " +
                "JOIN llm_model m ON e.model_id = m.model_id " +
                "JOIN prompt_technique pt ON e.technique_id = pt.technique_id " +
                "JOIN nutrition_result nr ON e.experiment_id = nr.experiment_id " +
                "WHERE e.status = 'completed' " +
                "ORDER BY e.experiment_id";

        try (Connection conn = DatabaseConnection.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                JSONObject row = new JSONObject();
                row.put("experiment_id", rs.getInt("experiment_id"));
                row.put("transcript_id", rs.getInt("transcript_id"));
                row.put("video_id", nullableString(rs, "video_id"));
                row.put("model_name", nullableString(rs, "model_name"));
                row.put("technique_name", nullableString(rs, "technique_name"));
                row.put("rag_enabled", rs.getBoolean("rag_enabled"));
                Object jsonValid = rs.getObject("json_valid");
                row.put("json_valid", jsonValid == null ? JSONObject.NULL : jsonValid);
                row.put("pred_total_kcal", nullableNumber(rs, "pred_total_kcal"));
                experiments.add(row);
            }
        }
        return experiments;
    }

    private JSONObject findGroundTruthById(JSONArray groundTruth, int id) {
        if (id < 0) return null;
        for (int i = 0; i < groundTruth.length(); i++) {
            JSONObject item = groundTruth.optJSONObject(i);
            if (item != null && item.optInt("gt_ingredient_id", -1) == id) return item;
        }
        return null;
    }

    private Object csvValue(JSONObject object, String key) {
        if (object == null || !object.has(key) || object.isNull(key)) return "";
        Object value = object.opt(key);
        return value == JSONObject.NULL ? "" : value;
    }

    private double sumJsonNumbers(JSONArray array, String key) {
        double total = 0.0;
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item != null && item.has(key) && !item.isNull(key)) {
                total += item.optDouble(key, 0.0);
            }
        }
        return total;
    }

    private void writeCsvRows(String tempPath, List<String> headers, List<List<Object>> rows)
            throws IOException {
        try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(tempPath), java.nio.charset.StandardCharsets.UTF_8))) {
            writer.write('\uFEFF');
            writeCsvLine(writer, new ArrayList<>(headers));
            for (List<Object> row : rows) writeCsvLine(writer, row);
        }
    }

    private void writeCsvLine(BufferedWriter writer, List<?> values) throws IOException {
        for (int i = 0; i < values.size(); i++) {
            Object value = values.get(i);
            writer.write(escapeCsv(value == null ? "" : String.valueOf(value)));
            if (i < values.size() - 1) writer.write(',');
        }
        writer.newLine();
    }

    private String escapeCsv(String value) {
        if (value == null) return "";
        boolean quote = value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
        String escaped = value.replace("\"", "\"\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }

    /** SQL-only layers whose rows do not require fuzzy ingredient pairing. */
    private String buildExportQuery(String layer) {
        switch (layer) {
            case "LAYER 2C":
                return "SELECT e.experiment_id, r.reel_id_instagram AS video_id, " +
                       "m.model_name, pt.technique_name, e.rag_enabled, " +
                       "SUM(gti.calories) AS gt_total_energy_kcal, " +
                       "SUM(gti.protein_g) AS gt_total_protein_g, " +
                       "SUM(gti.total_fat_g) AS gt_total_fat_g, " +
                       "SUM(gti.total_carbohydrate_g) AS gt_total_carbohydrate_g, " +
                       "nr.total_calories AS pred_total_energy_kcal, " +
                       "nr.total_protein_g AS pred_total_protein_g, " +
                       "nr.total_fat_g AS pred_total_fat_g, " +
                       "nr.total_carbohydrate_g AS pred_total_carbohydrate_g " +
                       "FROM experiment e " +
                       "JOIN transcript t ON e.transcript_id = t.transcript_id " +
                       "JOIN reel r ON t.reel_id = r.reel_id " +
                       "JOIN llm_model m ON e.model_id = m.model_id " +
                       "JOIN prompt_technique pt ON e.technique_id = pt.technique_id " +
                       "JOIN nutrition_result nr ON e.experiment_id = nr.experiment_id " +
                       "JOIN ground_truth_reel gtr ON t.transcript_id = gtr.transcript_id " +
                       "JOIN ground_truth_ingredient gti ON gtr.gt_reel_id = gti.gt_reel_id " +
                       "WHERE e.status = 'completed' " +
                       "AND LOWER(TRIM(gti.annotation_layer)) = 'layer2' " +
                       "GROUP BY e.experiment_id, r.reel_id_instagram, m.model_name, " +
                       "pt.technique_name, e.rag_enabled, nr.total_calories, " +
                       "nr.total_protein_g, nr.total_fat_g, nr.total_carbohydrate_g " +
                       "ORDER BY e.experiment_id";

            case "LAYER 3A":
                return "SELECT m.model_name, pt.technique_name, e.rag_enabled, " +
                       "COUNT(*) AS total_runs, " +
                       "SUM(CASE WHEN nr.json_valid = TRUE THEN 1 ELSE 0 END) AS valid_count, " +
                       "SUM(CASE WHEN nr.json_valid = FALSE OR nr.json_valid IS NULL THEN 1 ELSE 0 END) AS invalid_count, " +
                       "ROUND(SUM(CASE WHEN nr.json_valid = TRUE THEN 1 ELSE 0 END) * 100.0 / COUNT(*), 2) AS validity_rate_pct " +
                       "FROM experiment e " +
                       "JOIN llm_model m ON e.model_id = m.model_id " +
                       "JOIN prompt_technique pt ON e.technique_id = pt.technique_id " +
                       "LEFT JOIN nutrition_result nr ON e.experiment_id = nr.experiment_id " +
                       "WHERE e.status = 'completed' " +
                       "GROUP BY m.model_name, pt.technique_name, e.rag_enabled " +
                       "ORDER BY m.model_name, pt.technique_name";

            case "EXECUTION TIME":
                return "SELECT m.model_name, pt.technique_name, COUNT(*) AS runs, " +
                       "ROUND(AVG(e.execution_time_ms) / 1000, 2) AS avg_seconds, " +
                       "ROUND(MIN(e.execution_time_ms) / 1000, 2) AS min_seconds, " +
                       "ROUND(MAX(e.execution_time_ms) / 1000, 2) AS max_seconds " +
                       "FROM experiment e " +
                       "JOIN llm_model m ON e.model_id = m.model_id " +
                       "JOIN prompt_technique pt ON e.technique_id = pt.technique_id " +
                       "WHERE e.status = 'completed' AND e.execution_time_ms IS NOT NULL " +
                       "GROUP BY m.model_id, m.model_name, pt.technique_id, pt.technique_name " +
                       "ORDER BY m.model_name, pt.technique_name";

            default:
                return null;
        }
    }

    // ================================================================
    // PRIVATE HELPERS
    // ================================================================
    
    private String readTranscriptText(String filePath, String fileName) {
        if (filePath == null || fileName == null) return "";
        try {
            File f = new File(filePath, fileName);
            if (f.exists()) return Files.readString(f.toPath());

            File f2 = new File(filePath);
            if (f2.exists() && f2.isFile()) return Files.readString(f2.toPath());
        } catch (Exception e) {
            System.err.println("[DB] Cannot read transcript file: " + filePath + File.separator + fileName + " - " + e.getMessage());
        }
        return "";
    }

    private int insertExperiment(Connection conn, int transcriptId, int modelId, int techniqueId) throws SQLException {
        String sql = "INSERT INTO experiment (transcript_id, model_id, technique_id, rag_enabled, status, executed_at) " +
                     "VALUES (?, ?, ?, FALSE, 'running', NOW())";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, transcriptId);
            ps.setInt(2, modelId);
            ps.setInt(3, techniqueId);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) return rs.getInt(1);
        }
        throw new SQLException("Cannot insert experiment");
    }

    private void updateExperimentStatus(Connection conn, int experimentId, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE experiment SET status=? WHERE experiment_id=?")) {
            ps.setString(1, status);
            ps.setInt(2, experimentId);
            ps.executeUpdate();
        }
    }

    private void insertInvalidNutritionResult(Connection conn, int experimentId, 
                                             String rawJson, String error) throws SQLException {
        String sql = "INSERT INTO nutrition_result (experiment_id, raw_json_output, json_valid, error_note) " +
                     "VALUES (?, ?, FALSE, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, experimentId);
            String raw = rawJson.length() > 10000 ? rawJson.substring(0, 10000) + "..." : rawJson;
            ps.setString(2, raw);
            String errorMsg = error != null ? error : "Unknown parse error";
            if (errorMsg.length() > 500) errorMsg = errorMsg.substring(0, 500) + "...";
            ps.setString(3, errorMsg);
            ps.executeUpdate();
            System.err.println("[DB] Invalid result saved for experiment " + experimentId);
        }
    }

    private int insertValidNutritionResult(Connection conn, int experimentId, JSONObject json, String cleanJson) throws SQLException {
        String sql = "INSERT INTO nutrition_result (experiment_id, recipe_name, servings_estimated, raw_json_output, json_valid, " +
                "serving_calories, serving_total_fat_g, serving_saturated_fat_g, serving_cholesterol_mg, serving_sodium_mg, serving_carbohydrate_g, serving_fiber_g, serving_sugars_g, serving_protein_g, serving_vitamin_d_mcg, serving_calcium_mg, serving_iron_mg, serving_potassium_mg, " +
                "total_calories, total_fat_g, total_saturated_fat_g, total_cholesterol_mg, total_sodium_mg, total_carbohydrate_g, total_fiber_g, total_sugars_g, total_protein_g, total_vitamin_d_mcg, total_calcium_mg, total_iron_mg, total_potassium_mg) " +
                "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        JSONObject serving = json.optJSONObject("amount_per_serving");
        if (serving == null) serving = new JSONObject();
        JSONObject total = json.optJSONObject("nutrition_total");
        if (total == null) total = new JSONObject();
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, experimentId);
            ps.setString(2, json.optString("recipe_name", "Unknown"));
            ps.setInt(3, json.optInt("servings_estimated", 1));
            ps.setString(4, cleanJson);
            ps.setBoolean(5, true);
            setNutrition(ps, 6, serving);
            setNutrition(ps, 19, total);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            if (rs.next()) return rs.getInt(1);
        }
        throw new SQLException("Cannot insert nutrition_result");
    }

    private void setNutrition(PreparedStatement ps, int start, JSONObject obj) throws SQLException {
        ps.setDouble(start, obj.optDouble("calories", 0));
        ps.setDouble(start + 1, obj.optDouble("total_fat_g", 0));
        ps.setDouble(start + 2, obj.optDouble("saturated_fat_g", 0));
        ps.setDouble(start + 3, obj.optDouble("cholesterol_mg", 0));
        ps.setDouble(start + 4, obj.optDouble("sodium_mg", 0));
        ps.setDouble(start + 5, obj.optDouble("total_carbohydrate_g", 0));
        ps.setDouble(start + 6, obj.optDouble("dietary_fiber_g", 0));
        ps.setDouble(start + 7, obj.optDouble("total_sugars_g", 0));
        ps.setDouble(start + 8, obj.optDouble("protein_g", 0));
        ps.setDouble(start + 9, obj.optDouble("vitamin_d_mcg", 0));
        ps.setDouble(start + 10, obj.optDouble("calcium_mg", 0));
        ps.setDouble(start + 11, obj.optDouble("iron_mg", 0));
        ps.setDouble(start + 12, obj.optDouble("potassium_mg", 0));
    }

    private void insertIngredientResults(Connection conn, int resultId, JSONArray ingredients) throws SQLException {
        String sql = "INSERT INTO ingredient_result (result_id, name_original, name_en, quantity_value, unit_original, unit_en, estimated_weight_g, calories, total_fat_g, saturated_fat_g, cholesterol_mg, sodium_mg, total_carbohydrate_g, dietary_fiber_g, total_sugars_g, protein_g, vitamin_d_mcg, calcium_mg, iron_mg, potassium_mg) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < ingredients.length(); i++) {
                JSONObject ing = ingredients.optJSONObject(i);
                if (ing == null) continue;
                ps.setInt(1, resultId);
                ps.setString(2, ing.optString("ingredient_name_original", ing.optString("name_original", "")));
                ps.setString(3, ing.optString("ingredient_name_en", ing.optString("name_en", "")));
                ps.setDouble(4, ing.optDouble("quantity_value", 0));
                ps.setString(5, ing.optString("quantity_unit_original", ing.optString("unit_original", "")));
                ps.setString(6, ing.optString("quantity_unit_en", ing.optString("unit_en", "")));
                ps.setDouble(7, ing.optDouble("estimated_weight_g", 0));
                ps.setDouble(8, ing.optDouble("calories", 0));
                ps.setDouble(9, ing.optDouble("total_fat_g", 0));
                ps.setDouble(10, ing.optDouble("saturated_fat_g", 0));
                ps.setDouble(11, ing.optDouble("cholesterol_mg", 0));
                ps.setDouble(12, ing.optDouble("sodium_mg", 0));
                ps.setDouble(13, ing.optDouble("total_carbohydrate_g", 0));
                ps.setDouble(14, ing.optDouble("dietary_fiber_g", 0));
                ps.setDouble(15, ing.optDouble("total_sugars_g", 0));
                ps.setDouble(16, ing.optDouble("protein_g", 0));
                ps.setDouble(17, ing.optDouble("vitamin_d_mcg", 0));
                ps.setDouble(18, ing.optDouble("calcium_mg", 0));
                ps.setDouble(19, ing.optDouble("iron_mg", 0));
                ps.setDouble(20, ing.optDouble("potassium_mg", 0));
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private int getId(Connection conn, String sql, String param) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getInt(1);
        }
        throw new SQLException("ID not found: " + param);
    }

    private int count(Connection conn, String sql) throws SQLException {
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            rs.next();
            return rs.getInt(1);
        }
    }

    private String safe(String s) {
        return s == null ? "" : s;
    }
    
    public JSONArray getModelIngredientsForExperiment(int experimentId) {
        JSONArray ingredients = new JSONArray();

        String sql =
            "SELECT " +
            "ir.ingredient_id, " +
            "ir.name_original, " +
            "ir.name_en, " +
            "ir.quantity_value, " +
            "ir.unit_original, " +
            "ir.unit_en, " +
            "ir.estimated_weight_g, " +
            "ir.calories, " +
            "ir.total_fat_g, " +
            "ir.total_carbohydrate_g, " +
            "ir.protein_g " +
            "FROM ingredient_result ir " +
            "JOIN nutrition_result nr " +
            "  ON ir.result_id = nr.result_id " +
            "WHERE nr.experiment_id = ? " +
            "ORDER BY ir.ingredient_id";

        try (
            Connection connection = DatabaseConnection.getConnection();
            PreparedStatement statement = connection.prepareStatement(sql)
        ) {
            statement.setInt(1, experimentId);

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    JSONObject ingredient = new JSONObject();

                    ingredient.put(
                        "ingredient_id",
                        rs.getInt("ingredient_id")
                    );

                    ingredient.put(
                        "name_original",
                        nullableString(rs, "name_original")
                    );

                    ingredient.put(
                        "name_en",
                        nullableString(rs, "name_en")
                    );

                    ingredient.put(
                        "quantity_value",
                        nullableNumber(rs, "quantity_value")
                    );

                    ingredient.put(
                        "unit_original",
                        nullableString(rs, "unit_original")
                    );

                    ingredient.put(
                        "unit_en",
                        nullableString(rs, "unit_en")
                    );

                    ingredient.put(
                        "estimated_weight_g",
                        nullableNumber(rs, "estimated_weight_g")
                    );

                    ingredient.put(
                        "calories",
                        nullableNumber(rs, "calories")
                    );

                    ingredient.put(
                        "total_fat_g",
                        nullableNumber(rs, "total_fat_g")
                    );

                    ingredient.put(
                        "total_carbohydrate_g",
                        nullableNumber(rs, "total_carbohydrate_g")
                    );

                    ingredient.put(
                        "protein_g",
                        nullableNumber(rs, "protein_g")
                    );

                    ingredients.put(ingredient);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to load model ingredients for experiment "
                + experimentId,
                e
            );
        }

        return ingredients;
    }
    
    public JSONArray getGroundTruthIngredientsForExperiment(int experimentId) {
        return getGroundTruthIngredientsForExperiment(experimentId, "layer1");
    }

    public JSONArray getGroundTruthIngredientsForExperiment(
            int experimentId,
            String annotationLayer) {

        JSONArray ingredients = new JSONArray();

        String sql =
            "SELECT DISTINCT " +
            "gti.gt_ingredient_id, " +
            "gti.name_original, " +
            "gti.name_en, " +
            "gti.quantity_expression, " +
            "gti.quantity_value_culinary, " +
            "gti.quantity_unit_culinary, " +
            "gti.estimated_weight_g, " +
            "gti.calories, " +
            "gti.total_fat_g, " +
            "gti.total_carbohydrate_g, " +
            "gti.protein_g, " +
            "gti.annotation_layer " +
            "FROM ground_truth_ingredient gti " +
            "JOIN ground_truth_reel gtr " +
            "  ON gti.gt_reel_id = gtr.gt_reel_id " +
            "JOIN experiment e " +
            "  ON gtr.transcript_id = e.transcript_id " +
            "WHERE e.experiment_id = ? " +
            "AND LOWER(TRIM(gti.annotation_layer)) = ? " +
            "ORDER BY gti.gt_ingredient_id";

        try (Connection connection = DatabaseConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setInt(1, experimentId);
            statement.setString(2,
                    annotationLayer == null ? "layer1" : annotationLayer.trim().toLowerCase(Locale.ROOT));

            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    JSONObject ingredient = new JSONObject();
                    ingredient.put("gt_ingredient_id", rs.getInt("gt_ingredient_id"));
                    ingredient.put("name_original", nullableString(rs, "name_original"));
                    ingredient.put("name_en", nullableString(rs, "name_en"));
                    ingredient.put("quantity_expression", nullableString(rs, "quantity_expression"));
                    ingredient.put("quantity_value", nullableNumber(rs, "quantity_value_culinary"));
                    ingredient.put("quantity_unit_culinary", nullableString(rs, "quantity_unit_culinary"));
                    // Keep the old key used by the client detail table.
                    ingredient.put("unit", nullableString(rs, "quantity_unit_culinary"));
                    ingredient.put("estimated_weight_g", nullableNumber(rs, "estimated_weight_g"));
                    ingredient.put("calories", nullableNumber(rs, "calories"));
                    ingredient.put("total_fat_g", nullableNumber(rs, "total_fat_g"));
                    ingredient.put("total_carbohydrate_g", nullableNumber(rs, "total_carbohydrate_g"));
                    ingredient.put("protein_g", nullableNumber(rs, "protein_g"));
                    ingredient.put("annotation_layer", nullableString(rs, "annotation_layer"));
                    ingredients.put(ingredient);
                }
            }

        } catch (Exception e) {
            throw new RuntimeException(
                "Failed to load ground-truth ingredients for experiment " + experimentId +
                " and layer " + annotationLayer,
                e
            );
        }

        return ingredients;
    }

    private Object nullableNumber(
            ResultSet rs,
            String column) throws SQLException {

        Object value = rs.getObject(column);
        return value == null ? JSONObject.NULL : value;
    }

    private Object nullableString(
            ResultSet rs,
            String column) throws SQLException {

        String value = rs.getString(column);
        return value == null ? JSONObject.NULL : value;
    }
}