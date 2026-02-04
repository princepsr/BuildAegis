package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.model.RiskLevel;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeFormatter;

/**
 * Calculates risk levels deterministically based on objective metrics.
 *
 * <p>This service performs risk assessment without AI, using:
 * <ul>
 *   <li>Vulnerability counts and severity</li>
 *   <li>Project maintenance metrics (age, activity)</li>
 *   <li>Community adoption metrics (stars, issues)</li>
 * </ul>
 */
@Service
public class DeterministicRiskCalculator {

    /**
     * Calculates risk level and score based on deterministic metrics.
     *
     * @param dependency the dependency to assess
     * @param enrichment enrichment data (may be null)
     * @return deterministic risk result
     */
    public RiskResult calculateRisk(DependencyCoordinate dependency, DependencyEnrichmentDto enrichment) {
        int score = 0;
        StringBuilder rationale = new StringBuilder();

        // Base score starts at 20 (low risk)
        score = 20;

        // Vulnerability scoring (most important factor)
        if (enrichment != null && enrichment.vulnerabilityCount() != null) {
            int vulnCount = enrichment.vulnerabilityCount();
            if (vulnCount > 0) {
                score += Math.min(vulnCount * 25, 60); // Cap at 60 points from vulnerabilities
                rationale.append("Has ").append(vulnCount).append(" known vulnerabilities. ");
            }
        }

        // Maintenance scoring
        if (enrichment != null) {
            // Check last activity
            if (enrichment.githubLastPushedAt() != null) {
                try {
                    java.time.Instant instant = enrichment.githubLastPushedAt();
                    LocalDate lastPush = instant.atZone(java.time.ZoneOffset.UTC).toLocalDate();
                    
                    Period inactive = Period.between(lastPush, LocalDate.now());
                    if (inactive.getYears() > 2) {
                        score += 15;
                        rationale.append("No activity for over 2 years. ");
                    } else if (inactive.getYears() > 1) {
                        score += 10;
                        rationale.append("No activity for over 1 year. ");
                    }
                } catch (Exception e) {
                    // If we can't parse the date, be conservative
                    score += 5;
                    rationale.append("Unable to determine last activity date. ");
                }
            }

            // Check issue backlog
            if (enrichment.githubOpenIssues() != null) {
                int openIssues = enrichment.githubOpenIssues();
                if (openIssues > 200) {
                    score += 10;
                    rationale.append("High number of open issues (").append(openIssues).append("). ");
                } else if (openIssues > 50) {
                    score += 5;
                }
            }

            // Community adoption (inverse scoring - more stars = lower risk)
            if (enrichment.githubStars() != null) {
                int stars = enrichment.githubStars();
                if (stars < 10) {
                    score += 10;
                    rationale.append("Low community adoption (").append(stars).append(" stars). ");
                } else if (stars < 100) {
                    score += 5;
                }
                // Popular projects get no penalty
            }
        }

        // Cap score at 100
        score = Math.min(score, 100);

        // Determine risk level
        RiskLevel riskLevel = determineRiskLevel(score);

        return new RiskResult(riskLevel, score, rationale.toString());
    }

    private RiskLevel determineRiskLevel(int score) {
        if (score >= 70) {
            return RiskLevel.HIGH;
        } else if (score >= 40) {
            return RiskLevel.MEDIUM;
        } else if (score >= 20) {
            return RiskLevel.LOW;
        } else {
            return RiskLevel.UNKNOWN;
        }
    }

    /**
     * Result of deterministic risk calculation.
     *
     * @param riskLevel calculated risk level
     * @param riskScore numeric score (0-100)
     * @param rationale explanation of how the score was calculated
     */
    public record RiskResult(
            RiskLevel riskLevel,
            int riskScore,
            String rationale
    ) {}
}
