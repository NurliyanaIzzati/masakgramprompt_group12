package edu.utem.ftmk.analyzer;

import edu.utem.ftmk.llm.LLMService;
import org.json.JSONObject;
import org.json.JSONArray;

/**
 * LLMAnalyzer handles transcript analysis by sending prompts
 * to the selected Ollama model through LLMService.
 *
 * This class acts as the bridge between the server layer
 * and the local LLM models, with built-in prompt engineering
 * and response validation.
 */
public class LLMAnalyzer {

    // Encapsulation
    private LLMService llmService;
    
    // Constants for controlling response quality
    private static final int MAX_PROMPT_LENGTH = 12000;
    private static final int MAX_RESPONSE_LENGTH = 15000;

    /**
     * Constructor
     */
    public LLMAnalyzer() {
        this.llmService = new LLMService();
    }

    /**
     * Sends transcript to the selected LLM model with structured prompt
     *
     * @param transcript Transcript text
     * @param modelName Ollama model name
     * @return LLM response (validated JSON)
     */
    public String analyze(String transcript, String modelName) {

        System.out.println("[Server] Processing transcript with model: " + modelName);
        System.out.println("[Server] Transcript length: " + transcript.length() + " chars");

        // Step 1: Truncate transcript if too long
        String truncatedTranscript = truncateTranscript(transcript);

        // Step 2: Build structured prompt
        String prompt = buildStructuredPrompt(truncatedTranscript);

        System.out.println("[Server] Prompt length: " + prompt.length() + " chars");

        // Step 3: Get response from LLM
        String response = llmService.prompt(modelName, prompt);

        if (response == null || response.trim().isEmpty()) {
            System.err.println("[Server] ❌ Empty response from LLM");
            return createFallbackJSON("Empty response from LLM");
        }

        System.out.println("[Server] Response length: " + response.length() + " chars");

        // Step 4: Validate and clean response
        String cleanedResponse = cleanResponse(response);

        if (cleanedResponse == null) {
            System.err.println("[Server] ❌ Response validation failed - returning fallback");
            return createFallbackJSON("Invalid JSON response");
        }

        // Step 5: Ensure response is not too large
        if (cleanedResponse.length() > MAX_RESPONSE_LENGTH) {
            System.out.println("[Server] ⚠️ Response truncated from " + 
                             cleanedResponse.length() + " to " + MAX_RESPONSE_LENGTH + " chars");
            cleanedResponse = cleanedResponse.substring(0, MAX_RESPONSE_LENGTH);
            // Close any open JSON structures
            cleanedResponse = closeIncompleteJSON(cleanedResponse);
        }

        return cleanedResponse;
    }

    /**
     * Builds a structured prompt with clear JSON output requirements
     */
    private String buildStructuredPrompt(String transcript) {
        StringBuilder prompt = new StringBuilder();

        prompt.append("You are a precise JSON generator for nutritional analysis.\n\n");
        prompt.append("TASK: Extract recipe and nutrition information from the following cooking transcript.\n\n");

        prompt.append("CRITICAL RULES:\n");
        prompt.append("1. Output ONLY valid JSON - no explanations, no markdown, no conversation\n");
        prompt.append("2. Use null if information is missing\n");
        prompt.append("3. Keep responses concise and focused\n");
        prompt.append("4. Use English for all keys and values\n");
        prompt.append("5. Numbers should be numeric, not strings\n\n");

        prompt.append("REQUIRED JSON SCHEMA:\n");
        prompt.append("{\n");
        prompt.append("  \"recipe_name\": \"\",\n");
        prompt.append("  \"servings_estimated\": 1,\n");
        prompt.append("  \"ingredients\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"ingredient_name_original\": \"\",\n");
        prompt.append("      \"ingredient_name_en\": \"\",\n");
        prompt.append("      \"quantity_value\": 0,\n");
        prompt.append("      \"quantity_unit_original\": \"\",\n");
        prompt.append("      \"quantity_unit_en\": \"\",\n");
        prompt.append("      \"estimated_weight_g\": 0\n");
        prompt.append("    }\n");
        prompt.append("  ],\n");
        prompt.append("  \"amount_per_serving\": {\n");
        prompt.append("    \"calories\": 0,\n");
        prompt.append("    \"protein_g\": 0,\n");
        prompt.append("    \"total_fat_g\": 0,\n");
        prompt.append("    \"total_carbohydrate_g\": 0\n");
        prompt.append("  },\n");
        prompt.append("  \"nutrition_total\": {\n");
        prompt.append("    \"calories\": 0,\n");
        prompt.append("    \"protein_g\": 0,\n");
        prompt.append("    \"total_fat_g\": 0,\n");
        prompt.append("    \"total_carbohydrate_g\": 0\n");
        prompt.append("  }\n");
        prompt.append("}\n\n");

        prompt.append("TRANSCRIPT:\n");
        prompt.append(transcript);
        prompt.append("\n\n");
        prompt.append("REMEMBER: Output ONLY the JSON object. No additional text.");

        return prompt.toString();
    }

    /**
     * Truncate transcript to prevent token overflow
     */
    private String truncateTranscript(String transcript) {
        if (transcript == null) return "";
        
        if (transcript.length() <= MAX_PROMPT_LENGTH) {
            return transcript;
        }

        System.out.println("[Server] ⚠️ Transcript truncated from " + 
                         transcript.length() + " to " + MAX_PROMPT_LENGTH + " chars");
        
        // Keep first part and last part for context
        int keepStart = (int)(MAX_PROMPT_LENGTH * 0.7);
        int keepEnd = (int)(MAX_PROMPT_LENGTH * 0.15);
        
        return transcript.substring(0, keepStart) + 
               "\n...[transcript truncated for length]...\n" +
               transcript.substring(transcript.length() - keepEnd);
    }

    /**
     * Clean and validate LLM response
     */
    private String cleanResponse(String response) {
        if (response == null) return null;

        String cleaned = response.trim();

        // Remove markdown code blocks
        cleaned = cleaned.replaceAll("(?s)```json\\s*", "")
                        .replaceAll("(?s)```\\s*", "")
                        .replaceAll("(?s)```\\s*$", "")
                        .trim();

        // Remove common prefixes
        cleaned = cleaned.replaceAll("(?i)^\\s*(?:json|JSON)\\s*[:=]\\s*", "");

        // Extract JSON if embedded in text
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');

        if (start >= 0 && end > start) {
            cleaned = cleaned.substring(start, end + 1);
        } else {
            // Try array
            start = cleaned.indexOf('[');
            end = cleaned.lastIndexOf(']');
            if (start >= 0 && end > start) {
                cleaned = cleaned.substring(start, end + 1);
            } else {
                return null;
            }
        }

        // Fix common JSON issues
        cleaned = fixCommonJSONIssues(cleaned);

        // Validate JSON structure
        if (!isValidJSON(cleaned)) {
            // Try one more aggressive fix
            cleaned = aggressiveJSONFix(cleaned);
            if (!isValidJSON(cleaned)) {
                return null;
            }
        }

        return cleaned;
    }

    /**
     * Fix common JSON issues
     */
    private String fixCommonJSONIssues(String json) {
        String fixed = json;

        // Remove trailing commas
        fixed = fixed.replaceAll(",\\s*}", "}")
                     .replaceAll(",\\s*]", "]");

        // Fix single quotes
        fixed = fixed.replaceAll("'(\\w+)'\\s*:", "\"$1\":");
        fixed = fixed.replaceAll(":\\s*'([^']*)'", ": \"$1\"");

        // Add quotes to unquoted property names
        fixed = fixed.replaceAll("(\\{|,)\\s*(\\w+)\\s*:", "$1\"$2\":");

        // Fix unquoted values (except null, true, false, numbers)
        fixed = fixed.replaceAll(":\\s*([A-Za-z][A-Za-z0-9_]*)\\s*(,|})", ": \"$1\"$2");

        // Remove BOM
        fixed = fixed.replace("\uFEFF", "").trim();

        return fixed;
    }

    /**
     * Aggressive JSON fix as last resort
     */
    private String aggressiveJSONFix(String json) {
        if (json == null) return null;

        // Count braces
        int openBraces = 0;
        int closeBraces = 0;
        for (char c : json.toCharArray()) {
            if (c == '{') openBraces++;
            else if (c == '}') closeBraces++;
        }

        // Close incomplete JSON
        if (openBraces > closeBraces) {
            StringBuilder fixed = new StringBuilder(json);
            for (int i = 0; i < openBraces - closeBraces; i++) {
                fixed.append("}");
            }
            json = fixed.toString();
        }

        // Ensure object has at least minimal structure
        try {
            JSONObject obj = new JSONObject(json);
            // Add default fields if missing
            if (!obj.has("recipe_name")) obj.put("recipe_name", "Unknown Recipe");
            if (!obj.has("servings_estimated")) obj.put("servings_estimated", 1);
            if (!obj.has("ingredients")) obj.put("ingredients", new JSONArray());
            if (!obj.has("amount_per_serving")) obj.put("amount_per_serving", new JSONObject());
            if (!obj.has("nutrition_total")) obj.put("nutrition_total", new JSONObject());
            return obj.toString();
        } catch (Exception e) {
            // If still invalid, create minimal valid JSON
            return createFallbackJSON("Invalid JSON structure after fixes");
        }
    }

    /**
     * Validate JSON structure
     */
    private boolean isValidJSON(String json) {
        if (json == null || json.trim().isEmpty()) return false;
        
        try {
            new JSONObject(json);
            return true;
        } catch (Exception e) {
            try {
                new JSONArray(json);
                return true;
            } catch (Exception e2) {
                return false;
            }
        }
    }

    /**
     * Close incomplete JSON
     */
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
        return json;
    }

    /**
     * Create fallback JSON when all else fails
     */
    private String createFallbackJSON(String error) {
        JSONObject fallback = new JSONObject();
        fallback.put("recipe_name", "Error - " + error);
        fallback.put("servings_estimated", 1);
        fallback.put("ingredients", new JSONArray());
        
        JSONObject serving = new JSONObject();
        serving.put("calories", 0);
        serving.put("protein_g", 0);
        serving.put("total_fat_g", 0);
        serving.put("total_carbohydrate_g", 0);
        fallback.put("amount_per_serving", serving);
        
        JSONObject total = new JSONObject();
        total.put("calories", 0);
        total.put("protein_g", 0);
        total.put("total_fat_g", 0);
        total.put("total_carbohydrate_g", 0);
        fallback.put("nutrition_total", total);
        
        System.err.println("[Server] ⚠️ Created fallback JSON due to: " + error);
        return fallback.toString();
    }

    /**
     * Alternative analyze method with retry logic for failed extractions
     */
    public String analyzeWithRetry(String transcript, String modelName, int maxRetries) {
        String response = analyze(transcript, modelName);
        
        // Check if response is valid JSON
        if (response != null && response.length() > 10 && 
            (response.trim().startsWith("{") || response.trim().startsWith("["))) {
            return response;
        }
        
        // Retry with simplified prompt
        for (int attempt = 1; attempt < maxRetries; attempt++) {
            System.out.println("[Server] 🔄 Retry attempt " + attempt + " for model: " + modelName);
            
            // Use a simpler prompt for retry
            String simplifiedPrompt = buildSimplifiedPrompt(transcript);
            String retryResponse = llmService.prompt(modelName, simplifiedPrompt);
            
            if (retryResponse != null && !retryResponse.trim().isEmpty()) {
                String cleaned = cleanResponse(retryResponse);
                if (cleaned != null) {
                    return cleaned;
                }
            }
            
            // Wait a bit between retries
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        
        System.err.println("[Server] ❌ All " + maxRetries + " retry attempts failed");
        return createFallbackJSON("All retry attempts failed");
    }

    /**
     * Simplified prompt for retry attempts
     */
    private String buildSimplifiedPrompt(String transcript) {
        return "Extract recipe info as JSON from this transcript. Output ONLY valid JSON:\n\n" +
               transcript + "\n\n" +
               "JSON format: {\"recipe_name\":\"\", \"ingredients\":[], \"servings_estimated\":1, " +
               "\"amount_per_serving\":{\"calories\":0,\"protein_g\":0}," +
               "\"nutrition_total\":{\"calories\":0,\"protein_g\":0}}";
    }
}