package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FallbackExplanationServiceTest {

    private final FallbackExplanationService service = new FallbackExplanationService();
    private final DependencyCoordinate dep = new DependencyCoordinate("g", "a", "1.0", "maven", "compile");

    private DependencyEnrichmentDto enrichment(Boolean exploited, Double epss, String fix) {
        return new DependencyEnrichmentDto("Maven", "g:a", "1.0", 2, List.of("CVE-1"), null, null, null, null, null,
                fix, "CRITICAL", Map.of("CRITICAL", 1, "LOW", 1), exploited, epss);
    }

    @Test
    void mentionsSeverityExploitationAndEpss() {
        var result = service.explainRisk(dep, enrichment(true, 0.92, null), "HIGH", 90);

        assertFalse(result.fromAI());
        assertTrue(result.explanation().contains("CRITICAL"));
        assertTrue(result.explanation().contains("exploited in the wild"));
        assertTrue(result.explanation().contains("92%"));
        assertTrue(result.recommendations().get(0).startsWith("Prioritize"));
    }

    @Test
    void includesUpgradeGuidanceWhenFixVersionIsKnown() {
        var result = service.explainRisk(dep, enrichment(false, null, "2.0.0"), "HIGH", 75);

        assertTrue(result.recommendations().stream().anyMatch(r -> r.contains("2.0.0") && r.contains("major")));
        assertFalse(result.explanation().contains("exploited in the wild"));
    }

    @Test
    void worksWithoutEnrichment() {
        var result = service.explainRisk(dep, null, "LOW", 20);
        assertFalse(result.explanation().isBlank());
        assertFalse(result.recommendations().isEmpty());
    }
}
