package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.model.RiskLevel;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Google Gemini implementation of {@link AiClient}.
 *
 * <p>Uses the Gemini generateContent REST API. The model can be any Gemini model
 * (e.g., gemini-1.5-flash, gemini-1.5-pro).
 */
public class GeminiClient implements AiClient {

    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;

    public GeminiClient(String apiKey, String model) {
        this.httpClient = HttpClient.newHttpClient();
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public DependencyRiskAnalysisResult analyzeDependencyRisk(DependencyCoordinate dependency, DependencyEnrichmentDto enrichment) {
        String requestBody = buildRequestBody(dependency, enrichment);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(30))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Gemini API error: " + response.statusCode() + " " + response.body());
            }
            return parseGeminiResponse(response.body());
        } catch (Exception e) {
            return new DependencyRiskAnalysisResult(RiskLevel.UNKNOWN, null, "Gemini request failed: " + e.getMessage(), List.of());
        }
    }

    @Override
    public String generateCompletion(String prompt) {
        String requestBody = String.format("""
                {
                  "contents": [{
                    "parts":[{
                      "text":"%s"
                    }]
                  }]
                }
                """, JsonText.escape(prompt));
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(60))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Gemini API error: " + response.statusCode() + " " + response.body());
            }
            return parseGeminiTextResponse(response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Gemini request failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void testConnection() {
        String testBody = """
                {
                  "contents": [{
                    "parts":[{
                      "text":"Return exactly the word pong"
                    }]
                  }]
                }
                """;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(testBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Gemini test failed: " + response.statusCode() + " " + response.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Gemini test failed", e);
        }
    }

    private String buildRequestBody(DependencyCoordinate dependency, DependencyEnrichmentDto enrichment) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Dependency: ")
                .append(dependency.groupId())
                .append(":")
                .append(dependency.artifactId())
                .append(":")
                .append(dependency.version())
                .append("\n");
        prompt.append("BuildTool: ").append(dependency.buildTool()).append("\n");

        if (enrichment != null) {
            prompt.append("\nEnrichment:\n");
            if (enrichment.vulnerabilityCount() != null) {
                prompt.append("Known Vulnerabilities Count: ").append(enrichment.vulnerabilityCount()).append("\n");
            }
            if (enrichment.vulnerabilityIds() != null && !enrichment.vulnerabilityIds().isEmpty()) {
                prompt.append("Vulnerability IDs: ").append(String.join(", ", enrichment.vulnerabilityIds())).append("\n");
            }
            if (enrichment.scmUrl() != null) {
                prompt.append("SCM URL: ").append(enrichment.scmUrl()).append("\n");
            }
            if (enrichment.githubRepo() != null) {
                prompt.append("GitHub Repo: ").append(enrichment.githubRepo()).append("\n");
            }
            if (enrichment.githubStars() != null) {
                prompt.append("GitHub Stars: ").append(enrichment.githubStars()).append("\n");
            }
            if (enrichment.githubOpenIssues() != null) {
                prompt.append("GitHub Open Issues: ").append(enrichment.githubOpenIssues()).append("\n");
            }
            if (enrichment.githubLastPushedAt() != null) {
                prompt.append("GitHub Last Pushed At: ").append(enrichment.githubLastPushedAt()).append("\n");
            }
        }

        prompt.append("\nTask: Estimate maintenance and security risk using the dependency identifier and enrichment (if present). ");
        prompt.append("Provide a risk level and short plain-English explanation.\n\n");
        prompt.append("Return JSON with this exact schema:\n");
        prompt.append("{\n");
        prompt.append("  \"riskLevel\": \"HIGH\" | \"MEDIUM\" | \"LOW\" | \"UNKNOWN\",\n");
        prompt.append("  \"riskScore\": number (0-100),\n");
        prompt.append("  \"explanation\": string,\n");
        prompt.append("  \"recommendations\": [string]\n");
        prompt.append("}");

        String systemMessage = "You are a software supply-chain security expert. You must respond with STRICT JSON only (no markdown, no code fences).";
        String fullPrompt = systemMessage + "\n\n" + prompt;

        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode payload = mapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode contents = payload.putArray("contents");
            com.fasterxml.jackson.databind.node.ObjectNode contentNode = contents.addObject();
            com.fasterxml.jackson.databind.node.ArrayNode parts = contentNode.putArray("parts");
            com.fasterxml.jackson.databind.node.ObjectNode partNode = parts.addObject();
            partNode.put("text", fullPrompt);

            com.fasterxml.jackson.databind.node.ObjectNode generationConfig = payload.putObject("generationConfig");
            generationConfig.put("responseMimeType", "application/json");
            com.fasterxml.jackson.databind.node.ObjectNode schema = generationConfig.putObject("responseJsonSchema");
            schema.put("type", "object");
            com.fasterxml.jackson.databind.node.ObjectNode properties = schema.putObject("properties");
            com.fasterxml.jackson.databind.node.ObjectNode riskLevelProp = properties.putObject("riskLevel");
            riskLevelProp.put("type", "string");
            riskLevelProp.put("description", "Risk level: HIGH, MEDIUM, LOW, or UNKNOWN");
            com.fasterxml.jackson.databind.node.ObjectNode riskScoreProp = properties.putObject("riskScore");
            riskScoreProp.put("type", "integer");
            riskScoreProp.put("description", "Risk score from 0 to 100");
            com.fasterxml.jackson.databind.node.ObjectNode explanationProp = properties.putObject("explanation");
            explanationProp.put("type", "string");
            explanationProp.put("description", "Plain-English explanation of the risk");
            com.fasterxml.jackson.databind.node.ObjectNode recommendationsProp = properties.putObject("recommendations");
            recommendationsProp.put("type", "array");
            com.fasterxml.jackson.databind.node.ObjectNode recItems = recommendationsProp.putObject("items");
            recItems.put("type", "string");
            schema.putArray("required").add("riskLevel").add("explanation");

            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build Gemini request JSON", e);
        }
    }

    private String parseGeminiTextResponse(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            return root.path("candidates")
                    .get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse Gemini response: " + e.getMessage(), e);
        }
    }

    private DependencyRiskAnalysisResult parseGeminiResponse(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            String content = root.path("candidates")
                    .get(0)
                    .path("content")
                    .path("parts")
                    .get(0)
                    .path("text")
                    .asText();

            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(content);
            RiskLevel riskLevel;
            try {
                riskLevel = RiskLevel.valueOf(node.path("riskLevel").asText("UNKNOWN").toUpperCase());
            } catch (Exception ignored) {
                riskLevel = RiskLevel.UNKNOWN;
            }

            Integer riskScore = node.hasNonNull("riskScore") ? node.get("riskScore").asInt() : null;
            String explanation = node.path("explanation").asText("");

            List<String> recommendations = new ArrayList<>();
            com.fasterxml.jackson.databind.JsonNode recs = node.path("recommendations");
            if (recs.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode rec : recs) {
                    recommendations.add(rec.asText());
                }
            }

            return new DependencyRiskAnalysisResult(riskLevel, riskScore, explanation, recommendations);
        } catch (Exception e) {
            return new DependencyRiskAnalysisResult(RiskLevel.UNKNOWN, null, "Gemini response parse error: " + e.getMessage(), List.of());
        }
    }
}
