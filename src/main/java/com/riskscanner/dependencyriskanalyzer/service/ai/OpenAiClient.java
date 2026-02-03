package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.model.RiskLevel;
import com.theokanning.openai.completion.chat.ChatCompletionRequest;
import com.theokanning.openai.completion.chat.ChatMessage;
import com.theokanning.openai.completion.chat.ChatMessageRole;
import com.theokanning.openai.service.OpenAiService;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * OpenAI implementation of {@link AiClient}.
 *
 * <p>Uses the OpenAI chat completions API. The model can be any chat model supported by OpenAI
 * (e.g., gpt-4o, gpt-4o-mini, gpt-3.5-turbo).
 */
public class OpenAiClient implements AiClient {

    private final OpenAiService openAiService;
    private final String model;

    public OpenAiClient(String apiKey, String model) {
        this.openAiService = new OpenAiService(apiKey);
        this.model = model;
    }

    @Override
    public DependencyRiskAnalysisResult analyzeDependencyRisk(DependencyCoordinate dependency, DependencyEnrichmentDto enrichment) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage(ChatMessageRole.SYSTEM.value(),
                "You are a software supply-chain security expert. You must respond with STRICT JSON only (no markdown, no code fences)."));
        messages.add(new ChatMessage(ChatMessageRole.USER.value(), buildPrompt(dependency, enrichment)));

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(model)
                .messages(messages)
                .maxTokens(600)
                .temperature(0.2)
                .build();

        String content = openAiService.createChatCompletion(request)
                .getChoices().get(0).getMessage().getContent().trim();

        return parseResult(content);
    }

    @Override
    public void testConnection() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage(ChatMessageRole.SYSTEM.value(), "Return exactly 'pong'."));
        messages.add(new ChatMessage(ChatMessageRole.USER.value(), "ping"));

        ChatCompletionRequest request = ChatCompletionRequest.builder()
                .model(model)
                .messages(messages)
                .maxTokens(5)
                .temperature(0.0)
                .build();

        openAiService.createChatCompletion(request);
    }

    private String buildPrompt(DependencyCoordinate dependency, DependencyEnrichmentDto enrichment) {
        StringBuilder sb = new StringBuilder();
        sb.append("Dependency: ")
                .append(dependency.groupId())
                .append(":")
                .append(dependency.artifactId())
                .append(":")
                .append(dependency.version())
                .append("\n");
        sb.append("BuildTool: ").append(dependency.buildTool()).append("\n");

        if (enrichment != null) {
            sb.append("\nEnrichment:\n");
            if (enrichment.vulnerabilityCount() != null) {
                sb.append("Known Vulnerabilities Count: ").append(enrichment.vulnerabilityCount()).append("\n");
            }
            if (enrichment.vulnerabilityIds() != null && !enrichment.vulnerabilityIds().isEmpty()) {
                sb.append("Vulnerability IDs: ").append(String.join(", ", enrichment.vulnerabilityIds())).append("\n");
            }
            if (enrichment.scmUrl() != null) {
                sb.append("SCM URL: ").append(enrichment.scmUrl()).append("\n");
            }
            if (enrichment.githubRepo() != null) {
                sb.append("GitHub Repo: ").append(enrichment.githubRepo()).append("\n");
            }
            if (enrichment.githubStars() != null) {
                sb.append("GitHub Stars: ").append(enrichment.githubStars()).append("\n");
            }
            if (enrichment.githubOpenIssues() != null) {
                sb.append("GitHub Open Issues: ").append(enrichment.githubOpenIssues()).append("\n");
            }
            if (enrichment.githubLastPushedAt() != null) {
                sb.append("GitHub Last Pushed At: ").append(enrichment.githubLastPushedAt()).append("\n");
            }
        }

        sb.append("\nTask: Estimate maintenance and security risk using the dependency identifier and enrichment (if present). ");
        sb.append("Provide a risk level and short plain-English explanation.\n\n");
        sb.append("Return JSON with this exact schema:\n");
        sb.append("{\n");
        sb.append("  \"riskLevel\": \"HIGH\" | \"MEDIUM\" | \"LOW\" | \"UNKNOWN\",\n");
        sb.append("  \"riskScore\": number (0-100),\n");
        sb.append("  \"explanation\": string,\n");
        sb.append("  \"recommendations\": [string]\n");
        sb.append("}");
        return sb.toString();
    }

    private DependencyRiskAnalysisResult parseResult(String json) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(json);
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
            return new DependencyRiskAnalysisResult(RiskLevel.UNKNOWN, null, json, List.of());
        }
    }
}
