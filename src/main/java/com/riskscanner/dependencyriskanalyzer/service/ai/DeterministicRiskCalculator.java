package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.model.RiskLevel;
import org.springframework.stereotype.Service;

import java.util.Map;

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
                score += vulnerabilityPoints(enrichment, vulnCount);
                rationale.append("Has ").append(vulnCount).append(" known vulnerabilities");
                if (enrichment.maxSeverity() != null) {
                    rationale.append(" (highest severity ").append(enrichment.maxSeverity()).append(")");
                }
                rationale.append(". ");
            }
        }

        // Exploit data: vulnerabilities that are actually being attacked matter more than theoretical ones
        boolean knownExploited = enrichment != null && Boolean.TRUE.equals(enrichment.knownExploited());
        if (knownExploited) {
            score += 25;
            rationale.append("Exploited in the wild (CISA KEV). ");
        }
        if (enrichment != null && enrichment.maxEpssScore() != null) {
            double epss = enrichment.maxEpssScore();
            if (epss >= 0.5) {
                score += 10;
                rationale.append("High exploit probability (EPSS ").append(Math.round(epss * 100)).append("%). ");
            } else if (epss >= 0.1) {
                score += 5;
                rationale.append("Elevated exploit probability (EPSS ").append(Math.round(epss * 100)).append("%). ");
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
        if (knownExploited) {
            score = Math.max(score, KNOWN_EXPLOITED_FLOOR);
        }

        // Determine risk level
        RiskLevel riskLevel = determineRiskLevel(score);

        return new RiskResult(riskLevel, score, rationale.toString());
    }

    /** Points added per vulnerability, by severity. */
    private static final int CRITICAL_POINTS = 55;
    private static final int HIGH_POINTS = 40;
    private static final int MEDIUM_POINTS = 20;
    private static final int LOW_POINTS = 8;
    /** Vulnerabilities without severity information are treated like medium ones. */
    private static final int UNKNOWN_SEVERITY_POINTS = 20;
    /** Added for each vulnerability beyond the first, at most {@link #MAX_EXTRA_POINTS} in total. */
    private static final int EXTRA_VULNERABILITY_POINTS = 3;
    private static final int MAX_EXTRA_POINTS = 15;
    private static final int MAX_VULNERABILITY_POINTS = 65;
    /** A dependency with a vulnerability that is exploited in the wild is never scored below this. */
    private static final int KNOWN_EXPLOITED_FLOOR = 90;

    /**
     * Points for known vulnerabilities: the points of the most severe one, plus a small amount for each additional
     * vulnerability (so that a library with many issues ranks above one with a single issue of the same severity,
     * without every vulnerable library saturating the scale). Without severity data the old count-based rule
     * (25 per vulnerability, at most 60) is used.
     */
    private int vulnerabilityPoints(DependencyEnrichmentDto enrichment, int vulnCount) {
        Map<String, Integer> counts = enrichment.severityCounts();
        if (counts == null || counts.isEmpty()) {
            return Math.min(vulnCount * 25, 60);
        }

        int top = 0;
        int classified = 0;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            int n = e.getValue() == null ? 0 : e.getValue();
            if (n <= 0) {
                continue;
            }
            classified += n;
            top = Math.max(top, switch (e.getKey()) {
                case "CRITICAL" -> CRITICAL_POINTS;
                case "HIGH" -> HIGH_POINTS;
                case "LOW" -> LOW_POINTS;
                default -> MEDIUM_POINTS;
            });
        }
        if (classified < vulnCount) {
            top = Math.max(top, UNKNOWN_SEVERITY_POINTS);
        }
        int extra = Math.min(MAX_EXTRA_POINTS, Math.max(0, vulnCount - 1) * EXTRA_VULNERABILITY_POINTS);
        return Math.min(top + extra, MAX_VULNERABILITY_POINTS);
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
