package edu.utem.ftmk.llm;

/**
 * LLMServiceTest is a simple verification runner that confirms all four
 * locally hosted LLMs are reachable and responding correctly via LLMService.
 *
 * This is not a unit test framework class (no JUnit). It is a standalone
 * Java application intended to be run manually in Eclipse to verify the
 * Ollama server is running and all models are loaded correctly before
 * beginning the nutritional analysis experiments.
 *
 * How to run:
 * 1. Ensure Ollama is running (look for the Ollama icon in the menu bar)
 * 2. Right-click this file in Eclipse -> Run As -> Java Application
 * 3. Check the Console tab for responses from each model
 *
 * Expected output:
 * Each model should return a short response to the test prompt.
 * If a model fails, an error message is printed and the next model is tried.
 */
public class LLMServiceTest {

    /**
     * Main entry point to the application
     * @param args
     */
    public static void main(String[] args) {
        // Create an instance of LLMService to send prompts to the models
        LLMService service = new LLMService();

        // A simple one-sentence prompt used to verify each model is working.
        // Kept short so the test completes quickly.
        String testPrompt = "Name one common cooking ingredient. Reply in one sentence.";

        // List of all four model names used in this study.
        String[] models = {
                LLMService.LLAMA,
                LLMService.PHI,
                LLMService.QWEN,
                LLMService.SEALION,
                LLMService.MEDGEMMA
            };
        
        // Loop through each model, send the test prompt, and print the response.
        // Each model is tested independently so a failure in one does not
        // stop the remaining models from being tested.
        for (String model : models) {
            System.out.println("--------------------------------------------------");
            System.out.println("Model : " + model);
            System.out.print("Response : ");

            try {
                // Send the prompt to the current model and print the response
                String response = service.prompt(model, testPrompt);
                System.out.println(response);
            } catch (Exception e) {
                // If the model fails (e.g. not loaded, timeout, server down),
                // print the error and continue to the next model
                System.out.println("ERROR - " + e.getMessage());
            }
        }
        System.out.println("--------------------------------------------------");
        System.out.println("Test complete.");
    }
}