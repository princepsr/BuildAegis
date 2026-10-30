package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.service.AiSettingsService;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import com.riskscanner.dependencyriskanalyzer.service.UpgradeAdvisor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * AI-powered explanation service that delegates to the configured AiClient.
 *
 * <p>This service uses AI only for generating natural language explanations and recommendations.
 * All risk calculations are performed deterministically before calling this service.
 */
@Service
@Primary
@Lazy
public class DefaultAIExplanationService implements AIExplanationService {

    private final AiClientFactory aiClientFactory;
    private final AiSettingsService aiSettingsService;
    private final FallbackExplanationService fallbackService;

    public DefaultAIExplanationService(
            AiClientFactory aiClientFactory,
            AiSettingsService aiSettingsService,
            FallbackExplanationService fallbackService
    ) {
        this.aiClientFactory = aiClientFactory;
        this.aiSettingsService = aiSettingsService;
        this.fallbackService = fallbackService;
    }

    @Override
    public ExplanationResult explainRisk(
            DependencyCoordinate dependency,
            DependencyEnrichmentDto enrichment,
            String deterministicRiskLevel,
            Integer deterministicRiskScore
    ) {
        if (!isAvailable()) {
            return fallbackService.explainRisk(dependency, enrichment, deterministicRiskLevel, deterministicRiskScore);
        }

        try {
            var settings = aiSettingsService.getSettings();
            if (!isAvailable()) {
                return fallbackService.explainRisk(dependency, enrichment, deterministicRiskLevel, deterministicRiskScore);
            }
            
            AiClient client = aiClientFactory.create(
                    settings.provider(),
                    aiSettingsService.getApiKeyOrThrow(),
                    settings.model()
            );

            // Build prompt that includes deterministic analysis as context
            String prompt = buildExplanationPrompt(dependency, enrichment, deterministicRiskLevel, deterministicRiskScore);
            
            // For now, we'll reuse the existing analyzeDependencyRisk method but only use the explanation part
            // In a future iteration, we could add a dedicated "explain" method to AiClient
            AiClient.DependencyRiskAnalysisResult result = client.analyzeDependencyRisk(dependency, enrichment);
            
            // Some clients report failures as an UNKNOWN result carrying the error text instead of throwing
            if (result.riskLevel() == com.riskscanner.dependencyriskanalyzer.model.RiskLevel.UNKNOWN && result.riskScore() == null) {
                throw new IllegalStateException(result.explanation());
            }

            // Override with deterministic values to ensure AI doesn't change risk assessment
            List<String> recommendations = new ArrayList<>(result.recommendations() == null ? List.of() : result.recommendations());
            addUpgradeGuidance(client, dependency, enrichment, recommendations);

            return new ExplanationResult(
                    result.explanation(),
                    recommendations,
                    true
            );
        } catch (Exception e) {
            // Fall back gracefully if AI fails, and tell the user why the explanation is not AI-generated
            var fallback = fallbackService.explainRisk(dependency, enrichment, deterministicRiskLevel, deterministicRiskScore);
            return new ExplanationResult(describeFailure(e) + "\n\n" + fallback.explanation(), fallback.recommendations(), false);
        }
    }

    /**
     * Adds the suggested fix version with its expected behaviour-change level and, best-effort, an AI summary
     * of probable regressions when moving to that version. A failure here never fails the analysis.
     */
    private void addUpgradeGuidance(
            AiClient client,
            DependencyCoordinate dependency,
            DependencyEnrichmentDto enrichment,
            List<String> recommendations
    ) {
        if (enrichment == null || enrichment.suggestedFixVersion() == null) {
            return;
        }
        String target = enrichment.suggestedFixVersion();
        recommendations.add(UpgradeAdvisor.describeUpgrade(dependency.version(), target));
        try {
            String notes = client.generateCompletion(
                    "In at most 3 short sentences, list the probable regressions or behaviour changes a project could hit when upgrading "
                            + dependency.groupId() + ":" + dependency.artifactId() + " from " + dependency.version() + " to " + target
                            + ". Mention only changes you are reasonably sure of; if unsure, say the release notes should be checked. "
                            + "Reply with plain text only.");
            if (notes != null && !notes.isBlank()) {
                recommendations.add("Possible behaviour changes when upgrading to " + target + " (AI-generated, verify against release notes): " + notes.trim());
            }
        } catch (Exception ignored) {
            // Optional extra; the deterministic upgrade advice above is still returned
        }
    }

    private static String describeFailure(Exception e) {
        StringBuilder messages = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                messages.append(t.getMessage()).append(' ');
            }
        }
        String text = messages.toString().toLowerCase();
        if (text.contains("429") || text.contains("rate limit") || text.contains("too many requests")) {
            return "AI explanation unavailable: the AI provider rate limit was reached (HTTP 429). "
                    + "Showing the standard explanation instead; re-run the analysis later to get an AI explanation.";
        }
        return "AI explanation unavailable: the AI provider request failed. Showing the standard explanation instead.";
    }

    @Override
    public boolean isAvailable() {
        try {
            var settings = aiSettingsService.getSettings();
            if (settings == null || !settings.configured()) {
                return false;
            }
            if (settings.provider() == null || settings.provider().trim().isEmpty()) {
                return false;
            }
            // Try to get the API key to see if it's available
            aiSettingsService.getApiKeyOrThrow();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String buildExplanationPrompt(
            DependencyCoordinate dependency,
            DependencyEnrichmentDto enrichment,
            String deterministicRiskLevel,
            Integer deterministicRiskScore
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Provide a natural language explanation for the following dependency risk assessment.\n\n");
        prompt.append("Dependency: ").append(dependency.groupId()).append(":").append(dependency.artifactId()).append(":").append(dependency.version()).append("\n");
        prompt.append("Pre-calculated Risk Level: ").append(deterministicRiskLevel).append("\n");
        prompt.append("Pre-calculated Risk Score: ").append(deterministicRiskScore).append("\n");
        
        if (enrichment != null) {
            if (enrichment.vulnerabilityCount() != null) {
                prompt.append("Known Vulnerabilities: ").append(enrichment.vulnerabilityCount()).append("\n");
            }
            if (enrichment.vulnerabilityIds() != null && !enrichment.vulnerabilityIds().isEmpty()) {
                prompt.append("Vulnerability IDs: ").append(String.join(", ", enrichment.vulnerabilityIds())).append("\n");
            }
            if (enrichment.githubStars() != null) {
                prompt.append("GitHub Stars: ").append(enrichment.githubStars()).append("\n");
            }
            if (enrichment.githubOpenIssues() != null) {
                prompt.append("GitHub Open Issues: ").append(enrichment.githubOpenIssues()).append("\n");
            }
            if (enrichment.githubLastPushedAt() != null) {
                prompt.append("GitHub Last Pushed: ").append(enrichment.githubLastPushedAt()).append("\n");
            }
        }
        
        prompt.append("\nTask: Explain why this dependency has the given risk level and provide actionable recommendations.");
        prompt.append(" Do not question or change the risk level or score. Focus on explaining the factors that contribute to this assessment.");
        prompt.append(" Return JSON with: explanation (string) and recommendations (array of strings).");
        
        return prompt.toString();
    }
}
