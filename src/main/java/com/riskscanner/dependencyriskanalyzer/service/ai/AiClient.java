package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;

/**
 * Abstraction for AI providers that can analyze a single dependency.
 *
 * <p>Implementations should:
 * <ul>
 *   <li>Accept an API key and model name via constructor or builder.</li>
 *   <li>Make a best-effort request to the provider.</li>
 *   <li>Return a structured {@link DependencyRiskAnalysisResult} or a fallback with {@code RiskLevel.UNKNOWN}.</li>
 * </ul>
 */
public interface AiClient {

    /**
     * Analyzes a single dependency and returns a structured risk assessment.
     *
     * @param dependency the dependency to analyze
     * @param enrichment optional enrichment metadata (may be null)
     * @return structured analysis result
     */
    DependencyRiskAnalysisResult analyzeDependencyRisk(DependencyCoordinate dependency, DependencyEnrichmentDto enrichment);

    /**
     * Performs a lightweight request to validate the API key and model.
     *
     * @throws IllegalStateException if the provider/model/key is invalid or unreachable
     */
    void testConnection();

    /**
     * Structured result returned by an AI provider.
     *
     * @param riskLevel    risk level (may be UNKNOWN if parsing failed)
     * @param riskScore    numeric risk score (may be null)
     * @param explanation  plain-English explanation
     * @param recommendations list of recommendations
     */
    record DependencyRiskAnalysisResult(
            com.riskscanner.dependencyriskanalyzer.model.RiskLevel riskLevel,
            Integer riskScore,
            String explanation,
            java.util.List<String> recommendations
    ) {}
}
