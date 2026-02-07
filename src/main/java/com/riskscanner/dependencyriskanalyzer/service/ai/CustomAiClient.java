package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.model.RiskLevel;
import okhttp3.*;
import org.springframework.stereotype.Component;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.*;

/**
 * Custom AI provider implementation that works with any OpenAI-compatible API.
 *
 * <p>This client allows users to connect to any AI service that provides an
 * OpenAI-compatible chat completions API endpoint. Examples include:
 * <ul>
 *   <li>Local models (LM Studio, Oobabooga, etc.)</li>
 *   <li>Custom deployments</li>
 *   <li>Other cloud providers not in the predefined list</li>
 * </ul>
 *
 * <p>The endpoint should be the base URL of the API (e.g., https://api.example.com/v1).
 */
public class CustomAiClient implements AiClient {

    private final OkHttpClient httpClient;
    private final String model;
    private final String endpoint;
    private final String apiKey;
    private final ObjectMapper objectMapper;

    public CustomAiClient(String apiKey, String endpoint, String model) {
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofMinutes(2))
                .writeTimeout(Duration.ofMinutes(2))
                .build();
        this.model = model;
        this.endpoint = endpoint;
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
    }

    private String extractAssistantText(JsonNode jsonResponse) {
        JsonNode message = jsonResponse.path("choices").path(0).path("message");
        String content = message.path("content").asText("");
        if (content != null && !content.isBlank()) {
            return content.trim();
        }

        // Some OpenAI-compatible providers (e.g. OpenRouter with some models) may return empty
        // `content` and put the result under `reasoning`.
        String reasoning = message.path("reasoning").asText("");
        if (reasoning != null && !reasoning.isBlank()) {
            return reasoning.trim();
        }

        return "";
    }

    @Override
    public DependencyRiskAnalysisResult analyzeDependencyRisk(DependencyCoordinate dependency, DependencyEnrichmentDto enrichment) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "system", "content", 
                "You are a software supply-chain security expert. You must respond with STRICT JSON only (no markdown, no code fences)."));
            messages.add(Map.of("role", "user", "content", buildPrompt(dependency, enrichment)));
            requestBody.put("messages", messages);
            requestBody.put("max_tokens", 600);
            requestBody.put("temperature", 0.2);

            String jsonRequest = objectMapper.writeValueAsString(requestBody);
            
            Request request = new Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("HTTP-Referer", "https://buildaegis.local")
                    .addHeader("X-Title", "BuildAegis")
                    .post(RequestBody.create(MediaType.get("application/json"), jsonRequest))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("HTTP " + response.code() + ": " + response.body().string());
                }

                String responseBody = response.body().string();
                JsonNode jsonResponse = objectMapper.readTree(responseBody);
                
                if (jsonResponse.has("choices") && jsonResponse.get("choices").size() > 0) {
                    String text = extractAssistantText(jsonResponse);
                    if (text.isBlank()) {
                        throw new IllegalStateException("Empty response from AI provider (content/reasoning missing)");
                    }
                    return parseResult(text);
                } else {
                    throw new IllegalStateException("Invalid response format from AI provider");
                }
            }
        } catch (Exception e) {
            return new DependencyRiskAnalysisResult(
                    RiskLevel.UNKNOWN,
                    null,
                    "Failed to get analysis from custom AI provider: " + e.getMessage(),
                    List.of("Check your API endpoint, model name, and API key")
            );
        }
    }

    @Override
    public String generateCompletion(String prompt) {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", prompt));
            requestBody.put("messages", messages);
            requestBody.put("max_tokens", 1000);
            requestBody.put("temperature", 0.7);

            String jsonRequest = objectMapper.writeValueAsString(requestBody);
            
            Request request = new Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("HTTP-Referer", "https://buildaegis.local")
                    .addHeader("X-Title", "BuildAegis")
                    .post(RequestBody.create(MediaType.get("application/json"), jsonRequest))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    return "Error: HTTP " + response.code() + " - " + response.body().string();
                }

                String responseBody = response.body().string();
                JsonNode jsonResponse = objectMapper.readTree(responseBody);
                
                if (jsonResponse.has("choices") && jsonResponse.get("choices").size() > 0) {
                    String text = extractAssistantText(jsonResponse);
                    return text.isBlank() ? "Error: Empty response from AI provider" : text;
                } else {
                    return "Error: Invalid response format from AI provider";
                }
            }
        } catch (Exception e) {
            return "Error: Failed to generate completion with custom AI provider: " + e.getMessage();
        }
    }

    @Override
    public void testConnection() {
        try {
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("model", model);
            
            List<Map<String, String>> messages = new ArrayList<>();
            messages.add(Map.of("role", "user", "content", "Respond with 'OK' if you can read this."));
            requestBody.put("messages", messages);
            // Give models enough tokens to actually put something in `content`.
            requestBody.put("max_tokens", 50);
            requestBody.put("temperature", 0.1);

            String jsonRequest = objectMapper.writeValueAsString(requestBody);
            
            Request request = new Request.Builder()
                    .url(endpoint)
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("HTTP-Referer", "https://buildaegis.local")
                    .addHeader("X-Title", "BuildAegis")
                    .post(RequestBody.create(MediaType.get("application/json"), jsonRequest))
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new IllegalStateException("HTTP " + response.code() + ": " + response.body().string());
                }

                String responseBody = response.body().string();
                JsonNode jsonResponse = objectMapper.readTree(responseBody);
                
                if (jsonResponse.has("choices") && jsonResponse.get("choices").size() > 0) {
                    // We primarily validate that authentication + endpoint + model work.
                    // Content may be empty for some models/providers; accept a successful response.
                    extractAssistantText(jsonResponse);
                } else {
                    throw new IllegalStateException("Invalid response format from AI provider");
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to connect to custom AI provider: " + e.getMessage(), e);
        }
    }

    private String buildPrompt(DependencyCoordinate dependency, DependencyEnrichmentDto enrichment) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze this dependency for security risks:\n");
        prompt.append("Group ID: ").append(dependency.groupId()).append("\n");
        prompt.append("Artifact ID: ").append(dependency.artifactId()).append("\n");
        prompt.append("Version: ").append(dependency.version()).append("\n");

        if (enrichment != null) {
            if (enrichment.vulnerabilityCount() != null && enrichment.vulnerabilityCount() > 0) {
                prompt.append("Known vulnerabilities: ").append(enrichment.vulnerabilityCount()).append("\n");
                if (enrichment.vulnerabilityIds() != null && !enrichment.vulnerabilityIds().isEmpty()) {
                    prompt.append("Vulnerability IDs: ").append(String.join(", ", enrichment.vulnerabilityIds())).append("\n");
                }
            }
        }

        prompt.append("\nRespond with JSON in this exact format:\n");
        prompt.append("{\n");
        prompt.append("  \"riskLevel\": \"CRITICAL|HIGH|MEDIUM|LOW|UNKNOWN\",\n");
        prompt.append("  \"riskScore\": 0-100,\n");
        prompt.append("  \"explanation\": \"Brief explanation of the risk\",\n");
        prompt.append("  \"recommendations\": [\"list\", \"of\", \"recommendations\"]\n");
        prompt.append("}");

        return prompt.toString();
    }

    private DependencyRiskAnalysisResult parseResult(String content) {
        try {
            // Simple JSON parsing - in production, you'd use a proper JSON parser
            String lowerContent = content.toLowerCase();
            
            RiskLevel riskLevel = RiskLevel.UNKNOWN;
            if (lowerContent.contains("\"risklevel\":\"high\"")) {
                riskLevel = RiskLevel.HIGH;
            } else if (lowerContent.contains("\"risklevel\":\"medium\"")) {
                riskLevel = RiskLevel.MEDIUM;
            } else if (lowerContent.contains("\"risklevel\":\"low\"")) {
                riskLevel = RiskLevel.LOW;
            }

            // Extract risk score
            Integer riskScore = null;
            if (lowerContent.contains("\"riskscore\"")) {
                try {
                    String scoreStr = lowerContent.split("\"riskscore\":")[1].split(",")[0].trim();
                    riskScore = Integer.parseInt(scoreStr.replaceAll("[^0-9]", ""));
                } catch (Exception e) {
                    // Keep null if parsing fails
                }
            }

            // Extract explanation
            String explanation = "Analysis completed";
            if (lowerContent.contains("\"explanation\"")) {
                try {
                    String exp = content.split("\"explanation\":\"")[1].split("\"")[0];
                    explanation = exp.replace("\\n", " ").trim();
                } catch (Exception e) {
                    // Keep default
                }
            }

            // Extract recommendations
            List<String> recommendations = List.of("Review dependency for known vulnerabilities");
            if (lowerContent.contains("\"recommendations\"")) {
                try {
                    String recs = content.split("\"recommendations\":")[1].split("]")[0];
                    if (recs.contains("[")) {
                        String[] items = recs.split("\\[")[1].split("\"");
                        List<String> parsed = new ArrayList<>();
                        for (int i = 0; i < items.length; i += 2) {
                            if (i + 1 < items.length && !items[i + 1].equals(",")) {
                                parsed.add(items[i + 1].replace("\\n", " ").trim());
                            }
                        }
                        if (!parsed.isEmpty()) {
                            recommendations = parsed;
                        }
                    }
                } catch (Exception e) {
                    // Keep default
                }
            }

            return new DependencyRiskAnalysisResult(riskLevel, riskScore, explanation, recommendations);
        } catch (Exception e) {
            return new DependencyRiskAnalysisResult(
                    RiskLevel.UNKNOWN,
                    null,
                    "Failed to parse AI response: " + e.getMessage(),
                    List.of("Review dependency manually")
            );
        }
    }
}
