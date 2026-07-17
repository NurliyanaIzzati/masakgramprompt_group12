package edu.utem.ftmk.server;

import edu.utem.ftmk.analyzer.LLMAnalyzer;
import edu.utem.ftmk.database.DatabaseConnection;
import edu.utem.ftmk.database.DatabaseManager;
import edu.utem.ftmk.prompt.PromptManager;

import java.io.File;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * BatchSingleModelRunner - SINGLE TRANSCRIPT TEST VERSION
 * 
 * Runs ONE transcript with ONE selected LLM model and ONE selected prompt technique.
 * 
 * How to use:
 *   1. Set SELECTED_MODEL to one of the constants below
 *   2. Set SELECTED_TECHNIQUE to one of the technique names
 *   3. Set TRANSCRIPT_ID to the transcript you want to test
 *   4. Run As → Java Application in Eclipse
 *   5. Check the console and database for results
 */
public class BatchSingleModelRunner {

    // ================================================================
    // CONFIGURATION — CHANGE THESE BEFORE EACH RUN
    // ================================================================
    
    // Pick ONE model from these options:
    //   "llama3.2:3b"
    //   "phi4-mini"  
    //   "qwen2.5:3b"
    //   "aisingapore/Gemma-SEA-LION-v4-4B-VL"
    //   "medgemma:4b"
    private static final String SELECTED_MODEL = "phi4-mini";
    
    // Pick ONE technique from these options:
    //   "zero-shot"
    //   "few-shot"
    //   "chain-of-thought"
    //   "structured-output"
    private static final String SELECTED_TECHNIQUE = "structured-output";
    
    // ✅ Pick ONE transcript ID to test (1-50)
    private static final int TRANSCRIPT_ID = 48;  // ← CHANGE THIS
    
    // ================================================================
    // INNER CLASS FOR TRANSCRIPT DATA
    // ================================================================
    
    private static class TranscriptRecord {
        int transcriptId;
        String fileName;
        String filePath;
        String transcriptText;
        
        TranscriptRecord(int transcriptId, String fileName, String filePath) {
            this.transcriptId = transcriptId;
            this.fileName = fileName;
            this.filePath = filePath;
        }
    }
    
    // ================================================================
    // MAIN
    // ================================================================
    
    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  MasakGramPrompt — Single Transcript Test");
        System.out.println("============================================================");
        System.out.println("  Selected model     : " + SELECTED_MODEL);
        System.out.println("  Selected technique : " + SELECTED_TECHNIQUE);
        System.out.println("  Transcript ID      : " + TRANSCRIPT_ID);
        System.out.println("============================================================");
        System.out.println();
        
        // Check if the model is available in the database
        if (!isModelRegistered(SELECTED_MODEL)) {
            System.err.println("ERROR: Model '" + SELECTED_MODEL + "' is not registered in the llm_model table.");
            System.err.println("Please add it first using:");
            System.err.println("  INSERT INTO llm_model (model_tag, model_name) VALUES ('" + SELECTED_MODEL + "', '" + SELECTED_MODEL + "');");
            return;
        }
        
        // Check if the technique is available in the database
        if (!isTechniqueRegistered(SELECTED_TECHNIQUE)) {
            System.err.println("ERROR: Technique '" + SELECTED_TECHNIQUE + "' is not registered in the prompt_technique table.");
            System.err.println("Please add it first using:");
            System.err.println("  INSERT INTO prompt_technique (technique_name) VALUES ('" + SELECTED_TECHNIQUE + "');");
            return;
        }
        
        // Load the specific transcript
        TranscriptRecord tr = loadTranscript(TRANSCRIPT_ID);
        
        if (tr == null) {
            System.err.println("ERROR: Transcript #" + TRANSCRIPT_ID + " not found.");
            return;
        }
        
        System.out.println("✅ Loaded transcript #" + tr.transcriptId + " — " + tr.fileName);
        
        // Read transcript text from disk
        String text = readTranscriptFile(tr.filePath, tr.fileName);
        if (text == null || text.trim().isEmpty()) {
            System.err.println("ERROR: Transcript file is missing or empty: " + tr.fileName);
            return;
        }
        
        tr.transcriptText = text;
        System.out.println("✅ Transcript text loaded (" + text.length() + " chars)");
        System.out.println("   Preview: " + text.substring(0, Math.min(100, text.length())) + "...");
        System.out.println();
        
        // Run single processing
        runSingleProcessing(tr);
    }
    
    // ================================================================
    // SINGLE PROCESSING
    // ================================================================
    
    private static void runSingleProcessing(TranscriptRecord tr) {
        System.out.println("============================================================");
        System.out.println("  RUNNING SINGLE TEST");
        System.out.println("============================================================");
        
        LLMAnalyzer analyzer = new LLMAnalyzer();
        PromptManager promptMgr = new PromptManager();
        DatabaseManager dbManager = new DatabaseManager();
        
        long startTime = System.currentTimeMillis();
        
        try {
            System.out.println("[1/4] Building prompt...");
            String fullPrompt = promptMgr.buildFullPrompt(SELECTED_TECHNIQUE, tr.transcriptText);
            System.out.println("[1/4] ✅ Prompt built (" + fullPrompt.length() + " chars)");
            System.out.println();
            
            System.out.println("[2/4] Sending to LLM...");
            String result = analyzer.analyze(fullPrompt, SELECTED_MODEL);
            System.out.println("[2/4] ✅ LLM responded in " + (System.currentTimeMillis() - startTime) + "ms");
            System.out.println("[2/4] Response length: " + (result != null ? result.length() : 0) + " chars");
            System.out.println();
            
            // Show response preview
            System.out.println("[3/4] Response preview:");
            System.out.println("------------------------------------------------------------");
            if (result != null && result.length() > 0) {
                System.out.println(result.substring(0, Math.min(800, result.length())));
                if (result.length() > 800) {
                    System.out.println("... (truncated, full length: " + result.length() + " chars)");
                }
            } else {
                System.out.println("⚠️ Empty response!");
            }
            System.out.println("------------------------------------------------------------");
            System.out.println();
            
            System.out.println("[4/4] Saving to database...");
            boolean saved = dbManager.saveExperimentResult(
                SELECTED_MODEL, 
                SELECTED_TECHNIQUE, 
                result, 
                tr.transcriptId
            );
            
            long endTime = System.currentTimeMillis();
            long executionTimeMs = endTime - startTime;
            
            if (saved) {
                System.out.println("[4/4] ✅ Saved to database!");
                dbManager.updateExecutionTime(SELECTED_MODEL, SELECTED_TECHNIQUE, tr.transcriptId, executionTimeMs);
                System.out.println("[4/4] ✅ Execution time updated: " + executionTimeMs + "ms");
            } else {
                System.err.println("[4/4] ❌ Failed to save to database!");
            }
            
        } catch (Exception e) {
            System.err.println("❌ ERROR: " + e.getMessage());
            e.printStackTrace();
        }
        
        long totalTime = System.currentTimeMillis() - startTime;
        
        System.out.println();
        System.out.println("============================================================");
        System.out.println("  TEST COMPLETE");
        System.out.println("============================================================");
        System.out.println("  Model        : " + SELECTED_MODEL);
        System.out.println("  Technique    : " + SELECTED_TECHNIQUE);
        System.out.println("  Transcript   : " + tr.transcriptId);
        System.out.println("  Total time   : " + totalTime + "ms (" + (totalTime / 1000) + "s)");
        System.out.println("============================================================");
    }
    
    // ================================================================
    // DATABASE HELPERS
    // ================================================================
    
    private static boolean isModelRegistered(String modelTag) {
        String sql = "SELECT COUNT(*) FROM llm_model WHERE model_tag = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, modelTag);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            System.err.println("[DB] Error checking model: " + e.getMessage());
        }
        return false;
    }
    
    private static boolean isTechniqueRegistered(String techniqueName) {
        String sql = "SELECT COUNT(*) FROM prompt_technique WHERE technique_name = ?";
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, techniqueName);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
        } catch (Exception e) {
            System.err.println("[DB] Error checking technique: " + e.getMessage());
        }
        return false;
    }
    
    private static TranscriptRecord loadTranscript(int transcriptId) {
        String sql = "SELECT transcript_id, file_name, file_path FROM transcript WHERE transcript_id = ?";
        
        try (Connection conn = DatabaseConnection.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            
            ps.setInt(1, transcriptId);
            ResultSet rs = ps.executeQuery();
            
            if (rs.next()) {
                return new TranscriptRecord(
                    rs.getInt("transcript_id"),
                    rs.getString("file_name"),
                    rs.getString("file_path")
                );
            }
            
        } catch (Exception e) {
            System.err.println("[DB ERROR] Failed to load transcript: " + e.getMessage());
            e.printStackTrace();
        }
        
        return null;
    }
    
    private static String readTranscriptFile(String filePath, String fileName) {
        try {
            File file = new File(filePath, fileName);
            
            if (!file.exists()) {
                System.err.println("[FILE] Not found: " + file.getAbsolutePath());
                return null;
            }
            
            return Files.readString(file.toPath());
            
        } catch (Exception e) {
            System.err.println("[FILE] Error reading: " + e.getMessage());
            return null;
        }
    }
}