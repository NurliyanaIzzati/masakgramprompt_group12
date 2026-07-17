package edu.utem.ftmk.prompt;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * PromptManager handles loading and formatting prompt templates.
 * Satisfies OOP Encapsulation by hiding file I/O logic.
 */
public class PromptManager {

    private static final String PROMPTS_DIR = "prompts/";

    public String buildFullPrompt(String techniqueName, String transcript) {

        // Database/dropdown uses hyphen: zero-shot
        // File names use underscore: zero_shot_system.txt
        String fileTechniqueName = techniqueName.replace("-", "_");

        String systemPath = PROMPTS_DIR + fileTechniqueName + "_system.txt";
        String userPath = PROMPTS_DIR + fileTechniqueName + "_user.txt";

        String systemPrompt = readFile(systemPath);
        String userPrompt = readFile(userPath);

        if (systemPrompt.isBlank() || userPrompt.isBlank()) {
            throw new IllegalArgumentException(
                    "Prompt file missing or empty for technique: " + techniqueName
            );
        }

        String formattedUserPrompt = userPrompt.replace("{{TRANSCRIPT}}", transcript);

        return systemPrompt + "\n\n" + formattedUserPrompt;
    }

    private String readFile(String path) {
        try {
            return Files.readString(Paths.get(path));
        } catch (IOException e) {
            System.err.println("[PromptManager] Error reading file: " + path);
            return "";
        }
    }
}