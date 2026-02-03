package com.riskscanner.dependencyriskanalyzer.service.ai;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/**
 * Factory that creates the appropriate {@link AiClient} based on the provider identifier.
 *
 * <p>Supported providers:
 * <ul>
 *   <li>openai</li>
 *   <li>gemini</li>
 *   <li>claude</li>
 *   <li>ollama</li>
 *   <li>azure-openai</li>
 * </ul>
 *
 * <p>For Azure OpenAI, the API key must be the Azure key and the endpoint must be provided
 * via {@code riskscanner.ai.azure-openai.endpoint}. For Ollama, the base URL defaults to
 * {@code http://localhost:11434} and can be overridden via {@code riskscanner.ai.ollama.base-url}.
 */
@Lazy
@Component
public class AiClientFactory {

    private final String ollamaBaseUrl;
    private final String azureOpenAiEndpoint;

    public AiClientFactory() {
        this.ollamaBaseUrl = "http://localhost:11434";
        this.azureOpenAiEndpoint = "";
    }

    public AiClient create(String provider, String apiKey, String model) {
        if (provider == null) {
            throw new IllegalArgumentException("Provider must not be null");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("API key must not be null/blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Model must not be null/blank");
        }

        return switch (provider.toLowerCase()) {
            case "openai" -> new OpenAiClient(apiKey, model);
            case "gemini" -> new GeminiClient(apiKey, model);
            case "claude" -> new ClaudeClient(apiKey, model);
            case "ollama" -> new OllamaClient(model, ollamaBaseUrl);
            case "azure-openai" -> {
                if (azureOpenAiEndpoint == null || azureOpenAiEndpoint.isBlank()) {
                    throw new IllegalArgumentException("Azure OpenAI endpoint must be configured via riskscanner.ai.azure-openai.endpoint");
                }
                yield new AzureOpenAiClient(apiKey, azureOpenAiEndpoint, model);
            }
            default -> throw new IllegalArgumentException("Unsupported AI provider: " + provider);
        };
    }
}
