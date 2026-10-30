package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.model.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicRiskCalculatorTest {

    private final DeterministicRiskCalculator calculator = new DeterministicRiskCalculator();
    private final DependencyCoordinate dep = new DependencyCoordinate("g", "a", "1.0", "maven", "compile");

    private DependencyEnrichmentDto enrichment(Integer vulnCount, Map<String, Integer> severities, String maxSeverity,
                                               Boolean knownExploited, Double epss) {
        return new DependencyEnrichmentDto("Maven", "g:a", "1.0", vulnCount, List.of(), null, null, null, null, null,
                null, maxSeverity, severities, knownExploited, epss);
    }

    private DeterministicRiskCalculator.RiskResult risk(DependencyEnrichmentDto e) {
        return calculator.calculateRisk(dep, e);
    }

    @Test
    void cleanDependencyHasBaseScore() {
        var r = risk(enrichment(0, null, null, null, null));
        assertEquals(20, r.riskScore());
        assertEquals(RiskLevel.LOW, r.riskLevel());
    }

    @Test
    void noEnrichmentStillScores() {
        assertEquals(20, risk(null).riskScore());
    }

    @Test
    void oneCriticalVulnerabilityIsHighRisk() {
        var r = risk(enrichment(1, Map.of("CRITICAL", 1), "CRITICAL", null, null));
        assertEquals(75, r.riskScore());
        assertEquals(RiskLevel.HIGH, r.riskLevel());
        assertTrue(r.rationale().contains("CRITICAL"));
    }

    @Test
    void severityOrdersTheScores() {
        int critical = risk(enrichment(1, Map.of("CRITICAL", 1), "CRITICAL", null, null)).riskScore();
        int high = risk(enrichment(1, Map.of("HIGH", 1), "HIGH", null, null)).riskScore();
        int medium = risk(enrichment(1, Map.of("MEDIUM", 1), "MEDIUM", null, null)).riskScore();
        int low = risk(enrichment(1, Map.of("LOW", 1), "LOW", null, null)).riskScore();

        assertEquals(60, high);
        assertEquals(40, medium);
        assertEquals(28, low);
        assertTrue(critical > high && high > medium && medium > low);
    }

    @Test
    void manyLowSeverityVulnerabilitiesDoNotOutrankOneCritical() {
        var manyLow = risk(enrichment(5, Map.of("LOW", 5), "LOW", null, null));
        var oneCritical = risk(enrichment(1, Map.of("CRITICAL", 1), "CRITICAL", null, null));
        assertTrue(oneCritical.riskScore() > manyLow.riskScore());
    }

    @Test
    void fallsBackToCountBasedScoringWithoutSeverityData() {
        assertEquals(45, risk(enrichment(1, null, null, null, null)).riskScore());
        assertEquals(80, risk(enrichment(5, null, null, null, null)).riskScore());
    }

    @Test
    void extraVulnerabilitiesAddALittleAndAreCapped() {
        assertEquals(84, risk(enrichment(4, Map.of("CRITICAL", 4), "CRITICAL", null, null)).riskScore());
        assertEquals(85, risk(enrichment(20, Map.of("CRITICAL", 20), "CRITICAL", null, null)).riskScore());
        assertEquals(75, risk(enrichment(20, Map.of("HIGH", 20), "HIGH", null, null)).riskScore());
    }

    @Test
    void severalHighVulnerabilitiesDoNotSaturateTheScale() {
        int one = risk(enrichment(1, Map.of("HIGH", 1), "HIGH", null, null)).riskScore();
        int two = risk(enrichment(2, Map.of("HIGH", 2), "HIGH", null, null)).riskScore();
        int critical = risk(enrichment(1, Map.of("CRITICAL", 1), "CRITICAL", null, null)).riskScore();
        int exploited = risk(enrichment(1, Map.of("HIGH", 1), "HIGH", true, null)).riskScore();

        assertEquals(63, two);
        assertTrue(one < two && two < critical && critical < exploited);
    }

    @Test
    void vulnerabilitiesWithoutSeverityCountAsMedium() {
        // 1 known HIGH + 2 without severity information: 40 for the worst, 2 * 3 for the extra ones
        var r = risk(enrichment(3, Map.of("HIGH", 1), "HIGH", null, null));
        assertEquals(66, r.riskScore());
    }

    @Test
    void knownExploitedVulnerabilityForcesVeryHighScore() {
        var r = risk(enrichment(1, Map.of("LOW", 1), "LOW", true, null));
        assertEquals(90, r.riskScore());
        assertEquals(RiskLevel.HIGH, r.riskLevel());
        assertTrue(r.rationale().contains("KEV"));
    }

    @Test
    void epssRaisesScoreInSteps() {
        int base = risk(enrichment(1, Map.of("MEDIUM", 1), "MEDIUM", null, null)).riskScore();
        int elevated = risk(enrichment(1, Map.of("MEDIUM", 1), "MEDIUM", null, 0.2)).riskScore();
        int high = risk(enrichment(1, Map.of("MEDIUM", 1), "MEDIUM", null, 0.8)).riskScore();
        int negligible = risk(enrichment(1, Map.of("MEDIUM", 1), "MEDIUM", null, 0.01)).riskScore();

        assertEquals(base + 5, elevated);
        assertEquals(base + 10, high);
        assertEquals(base, negligible);
    }

    @Test
    void scoreNeverExceedsOneHundred() {
        var r = risk(enrichment(6, Map.of("CRITICAL", 6), "CRITICAL", true, 0.99));
        assertEquals(100, r.riskScore());
    }
}
