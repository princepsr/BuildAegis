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
 * Anthropic Claude implementation of {@link AiClient}.
 *
 * <p>Uses the Claude messages API. The model can be any Claude model
 * (e.g., claude-3-5-haiku-20241022, claude-3-5-sonnet-20241022).
 */
public class ClaudeClient implements AiClient {

    private final HttpClient httpClient;
    private final String apiKey;
    private final String model;

    public ClaudeClient(String apiKey, String model) {
        this.httpClient = HttpClient.newHttpClient();
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public DependencyRiskAnalysisResult analyzeDependencyRisk(DependencyCoordinate dependency, DependencyEnrichmentDto enrichment) {
        String requestBody = buildRequestBody(dependency, enrichment);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .timeout(Duration.ofSeconds(30))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Claude API error: " + response.statusCode() + " " + response.body());
            }
            return parseClaudeResponse(response.body());
        } catch (Exception e) {
            return new DependencyRiskAnalysisResult(RiskLevel.UNKNOWN, null, "Claude request failed: " + e.getMessage(), List.of());
        }
    }

    @Override
    public void testConnection() {
        String testBody = """
                {
                  "model": "%s",
                  "max_tokens": 5,
                  "messages": [
                    {"role": "user", "content": "Return exactly the word pong"}
                  ]
                }
                """.formatted(model);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("https://api.anthropic.com/v1/messages"))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(testBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Claude test failed: " + response.statusCode() + " " + response.body());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Claude test failed", e);
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
                  "max_tokens": 600,
                  "temperature": 0.2,
                  "system": "%s",
                  "messages": [
                    {"role": "user", "content": "%s"}
                  ]
                }
                """.formatted(model, systemMessage.replace("\"", "\\\""), prompt.toString().replace("\"", "\\\""));
    }

    private DependencyRiskAnalysisResult parseClaudeResponse(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(json);
            String content = root.path("content")
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
            return new DependencyRiskAnalysisResult(RiskLevel.UNKNOWN, null, "Claude response parse error: " + e.getMessage(), List.of());
        }
    }
}
