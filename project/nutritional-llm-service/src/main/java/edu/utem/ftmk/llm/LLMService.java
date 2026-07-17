package edu.utem.ftmk.llm;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import java.time.Duration;

public class LLMService {

    private static final String OLLAMA_BASE_URL = "http://localhost:11434";

    public static final String LLAMA = "llama3.2:3b";
    public static final String PHI = "phi4-mini";
    public static final String QWEN = "qwen2.5:3b";
    public static final String SEALION = "aisingapore/Gemma-SEA-LION-v4-4B-VL";
    public static final String MEDGEMMA = "medgemma:4b";

    public ChatModel buildModel(String modelName) {
        return OllamaChatModel.builder()
                .baseUrl(OLLAMA_BASE_URL)
                .modelName(modelName)
                .temperature(0.05)
                .numPredict(8000) 
                // INCREASE THIS: 5 minutes is usually enough for medgemma
                .timeout(Duration.ofMinutes(360)) 
                .build();
    }
    

    public String prompt(String modelName, String userPrompt) {
        ChatModel model = buildModel(modelName);
        return model.chat(userPrompt);
    }
}