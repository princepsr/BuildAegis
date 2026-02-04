package com.riskscanner.dependencyriskanalyzer.service;

import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.service.ai.AIExplanationService;
import com.riskscanner.dependencyriskanalyzer.service.ai.AiClient;
import com.riskscanner.dependencyriskanalyzer.service.ai.AiClientFactory;
import com.riskscanner.dependencyriskanalyzer.service.ai.DeterministicRiskCalculator;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Risk analysis service that combines deterministic calculation with optional AI explanations.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Calculate risk levels deterministically using objective metrics.</li>
 *   <li>Generate natural language explanations using AI when available.</li>
 *   <li>Provide fallback explanations when AI is not configured.</li>
 *   <li>Maintain backward compatibility with existing APIs.</li>
 * </ul>
 *
 * <p>AI is used only for explanations and recommendations, not for risk assessment.
 */
@Lazy
@Service
public class AIAnalysisService {

    private final AiSettingsService aiSettingsService;
    private final AiClientFactory aiClientFactory;
    private final DeterministicRiskCalculator riskCalculator;
    private final AIExplanationService explanationService;

    public AIAnalysisService(
            AiSettingsService aiSettingsService,
            @Lazy AiClientFactory aiClientFactory,
            DeterministicRiskCalculator riskCalculator,
            AIExplanationService explanationService
    ) {
        this.aiSettingsService = aiSettingsService;
        this.aiClientFactory = aiClientFactory;
        this.riskCalculator = riskCalculator;
        this.explanationService = explanationService;
    }

    /**
     * Analyzes the given dependencies for security vulnerabilities, known issues, and maintenance status.
     *
     * <p>This method is a legacy endpoint that returns free-form text. It is recommended to use
     * {@link #analyzeDependencyRisk(DependencyCoordinate)} or
     * {@link #analyzeDependencyRisk(DependencyCoordinate, DependencyEnrichmentDto)} instead.
     *
     * @param dependencies the dependencies to analyze
     * @return the analysis result as a string
     */
    public String analyzeDependencies(List<String> dependencies) {
        // Legacy method: not all providers implement free-form text; fallback to a simple concatenation
        StringBuilder sb = new StringBuilder();
        for (String dep : dependencies) {
            sb.append("- ").append(dep).append("\n");
        }
        sb.append("\n(Only per-dependency structured analysis is supported for multi-provider mode.)");
        return sb.toString();
    }

    /**
     * Tests that the currently configured provider/model/api key can successfully reach the AI provider.
     *
     * <p>This is used by the UI to validate configuration before running analysis.
     * If AI is not configured, this will throw an exception.
     */
    public void testConnection() {
        if (!explanationService.isAvailable()) {
            throw new IllegalStateException("AI is not configured. Please configure an AI provider and API key.");
        }
        AiClient client = aiClientFactory.create(
                aiSettingsService.getProvider(),
                aiSettingsService.getApiKeyOrThrow(),
                aiSettingsService.getModel()
        );
        client.testConnection();
    }

    /**
     * Checks if AI is configured and available for generating explanations.
     *
     * @return true if AI is available; false if fallback will be used
     */
    public boolean isAIAvailable() {
        return explanationService.isAvailable();
    }

    /**
     * Analyzes a single dependency using deterministic calculation and optional AI explanation.
     *
     * <p>This overload performs analysis without enrichment. Prefer
     * {@link #analyzeDependencyRisk(DependencyCoordinate, DependencyEnrichmentDto)} when enrichment is available.
     *
     * @param dependency the dependency to analyze
     * @return the structured analysis result
     */
    public DependencyRiskAnalysisResult analyzeDependencyRisk(DependencyCoordinate dependency) {
        return analyzeDependencyRisk(dependency, null);
    }

    /**
     * Analyzes a single dependency using deterministic risk calculation and optional AI explanation.
     *
     * <p>Risk level and score are calculated deterministically based on objective metrics.
     * AI is used only for generating natural language explanations and recommendations when available.
     * If AI is not configured, deterministic template-based explanations are provided.
     *
     * @param dependency the dependency to analyze
     * @param enrichment the enrichment data (optional)
     * @return the structured analysis result
     */
    public DependencyRiskAnalysisResult analyzeDependencyRisk(DependencyCoordinate dependency, DependencyEnrichmentDto enrichment) {
        if (dependency == null) {
            throw new IllegalArgumentException("dependency must not be null");
        }

        // Step 1: Calculate risk deterministically
        DeterministicRiskCalculator.RiskResult riskResult = riskCalculator.calculateRisk(dependency, enrichment);

        // Step 2: Generate explanation (AI if available, otherwise fallback)
        AIExplanationService.ExplanationResult explanationResult = explanationService.explainRisk(
                dependency,
                enrichment,
                riskResult.riskLevel().name(),
                riskResult.riskScore()
        );

        // Step 3: Combine results
        return new DependencyRiskAnalysisResult(
                riskResult.riskLevel(),
                riskResult.riskScore(),
                explanationResult.explanation(),
                explanationResult.recommendations()
        );
    }

    /**
     * Parsed structured result returned by the analysis service.
     */
    public record DependencyRiskAnalysisResult(
            com.riskscanner.dependencyriskanalyzer.model.RiskLevel riskLevel,
            Integer riskScore,
            String explanation,
            List<String> recommendations
    ) {
    }
}