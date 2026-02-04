package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Fallback explanation service that provides deterministic, template-based explanations
 * when AI is unavailable or not configured.
 */
@Service
public class FallbackExplanationService implements AIExplanationService {

    @Override
    public ExplanationResult explainRisk(
            DependencyCoordinate dependency,
            DependencyEnrichmentDto enrichment,
            String deterministicRiskLevel,
            Integer deterministicRiskScore
    ) {
        StringBuilder explanation = new StringBuilder();
        List<String> recommendations = new ArrayList<>();

        // Base explanation
        explanation.append("Dependency ")
                .append(dependency.groupId())
                .append(":")
                .append(dependency.artifactId())
                .append(":")
                .append(dependency.version())
                .append(" has been analyzed with a risk level of ")
                .append(deterministicRiskLevel)
                .append(".");

        // Add vulnerability context
        if (enrichment != null && enrichment.vulnerabilityCount() != null && enrichment.vulnerabilityCount() > 0) {
            explanation.append(" It has ")
                    .append(enrichment.vulnerabilityCount())
                    .append(" known vulnerabilities.");
            recommendations.add("Review and address known vulnerabilities.");
            if (enrichment.vulnerabilityIds() != null && !enrichment.vulnerabilityIds().isEmpty()) {
                explanation.append(" Affected CVEs: ").append(String.join(", ", enrichment.vulnerabilityIds())).append(".");
            }
        } else {
            explanation.append(" No known vulnerabilities were found in public databases.");
        }

        // Add maintenance context
        if (enrichment != null) {
            if (enrichment.githubStars() != null) {
                explanation.append(" The project has ")
                        .append(enrichment.githubStars())
                        .append(" GitHub stars.");
            }
            if (enrichment.githubOpenIssues() != null && enrichment.githubOpenIssues() > 50) {
                explanation.append(" High number of open issues (")
                        .append(enrichment.githubOpenIssues())
                        .append(") may indicate maintenance challenges.");
                recommendations.add("Monitor project maintenance activity.");
            }
            if (enrichment.githubLastPushedAt() != null) {
                explanation.append(" Last activity recorded on ")
                        .append(enrichment.githubLastPushedAt())
                        .append(".");
            }
        }

        // Add risk-level specific recommendations
        switch (deterministicRiskLevel.toUpperCase()) {
            case "HIGH" -> {
                recommendations.add("Consider upgrading to a newer version if available.");
                recommendations.add("Evaluate alternative libraries with better security maintenance.");
            }
            case "MEDIUM" -> {
                recommendations.add("Monitor for security updates.");
                recommendations.add("Review usage patterns to ensure they follow security best practices.");
            }
            case "LOW" -> {
                recommendations.add("Continue routine monitoring for new vulnerabilities.");
            }
            default -> {
                recommendations.add("Review dependency necessity and update regularly.");
            }
        }

        return new ExplanationResult(explanation.toString(), recommendations, false);
    }

    @Override
    public boolean isAvailable() {
        return false; // This is always the fallback
    }
}
