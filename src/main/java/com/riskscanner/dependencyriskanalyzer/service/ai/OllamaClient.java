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
 * Ollama implementation of {@link AiClient}.
 *
 * <p>Uses the Ollama /api/generate endpoint. The model can be any model you have pulled locally
 * (e.g., llama3.2, mistral, codellama). By default assumes Ollama is running at http://localhost:11434.
 */
public class OllamaClient implements AiClient {

    private final HttpClient httpClient;
    private final String model;
    private final String baseUrl;

    public OllamaClient(String model, String baseUrl) {
        this.httpClient = HttpClient.newHttpClient();
        this.model = model;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public DependencyRiskAnalysisResult analyzeDependencyRisk(DependencyCoordinate dependency, DependencyEnrichmentDto enrichment) {
        String requestBody = buildRequestBody(dependency, enrichment);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(60))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Ollama API error: " + response.statusCode() + " " + response.body());
            }
            return parseOllamaResponse(response.body());
        } catch (Exception e) {
            return new DependencyRiskAnalysisResult(RiskLevel.UNKNOWN, null, "Ollama request failed: " + e.getMessage(), List.of());
        }
    }

    @Override
    public void testConnection() {
        String testBody = """
                {
                  "model": "%s",
                  "prompt": "Return exactly the word pong",
                  "stream": false
                }
                """.formatted(model);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/generate"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(testBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Ollama test failed: " + response.statusCode() + " " + response.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Ollama test failed", e);
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

        return """
                {
                  "model": "%s",
                  "system": "%s",
                  "prompt": "%s",
                  "stream": false,
                  "options": {
                    "temperature": 0.2,
                    "num_predict": 600
                  }
                }
                """.formatted(model, systemMessage.replace("\"", "\\\""), prompt.toString().replace("\"", "\\\""));
    }

    private DependencyRiskAnalysisResult parseOllamaResponse(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            String content = root.path("response").asText();

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
            return new DependencyRiskAnalysisResult(RiskLevel.UNKNOWN, null, "Ollama response parse error: " + e.getMessage(), List.of());
        }
    }
}
