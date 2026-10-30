package com.riskscanner.dependencyriskanalyzer.cli;

import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.dto.DependencyRiskDto;
import com.riskscanner.dependencyriskanalyzer.model.RiskLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.riskscanner.dependencyriskanalyzer.cli.ScanReport.Level.CRITICAL;
import static com.riskscanner.dependencyriskanalyzer.cli.ScanReport.Level.HIGH;
import static com.riskscanner.dependencyriskanalyzer.cli.ScanReport.Level.LOW;
import static com.riskscanner.dependencyriskanalyzer.cli.ScanReport.Level.MEDIUM;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScanReportTest {

    private static DependencyRiskDto dep(String artifact, RiskLevel level, Integer score, Integer vulns,
                                         String maxSeverity, Boolean exploited, String fix) {
        var enrichment = new DependencyEnrichmentDto("Maven", "g:" + artifact, "1.0", vulns, List.of(), null, null, null, null,
                null, fix, maxSeverity, null, exploited, null);
        return new DependencyRiskDto("g", artifact, "1.0", "maven", enrichment, level, score, "explanation", List.of(),
                false, null, "disabled", "disabled", true);
    }

    private static DependencyRiskDto clean(String artifact) {
        return dep(artifact, RiskLevel.LOW, 20, 0, null, null, null);
    }

    @Test
    void severityFollowsAdvisorySeverityOverTheVulnerabilityCount() {
        assertEquals(CRITICAL, ScanReport.severityOf(dep("a", RiskLevel.HIGH, 75, 1, "CRITICAL", null, null)));
        assertEquals(LOW, ScanReport.severityOf(dep("a", RiskLevel.HIGH, 80, 12, "LOW", null, null)),
                "twelve low-severity issues are not critical");
    }

    @Test
    void knownExploitedIsAlwaysCritical() {
        assertEquals(CRITICAL, ScanReport.severityOf(dep("a", RiskLevel.LOW, 30, 1, "LOW", true, null)));
    }

    @Test
    void withoutSeverityDataItFallsBackToCountThenBackendLevel() {
        assertEquals(CRITICAL, ScanReport.severityOf(dep("a", RiskLevel.MEDIUM, 50, 10, null, null, null)));
        assertEquals(HIGH, ScanReport.severityOf(dep("a", RiskLevel.HIGH, 70, 2, null, null, null)));
        assertEquals(MEDIUM, ScanReport.severityOf(dep("a", RiskLevel.MEDIUM, 45, 1, null, null, null)));
        assertEquals(LOW, ScanReport.severityOf(clean("a")));
        assertEquals(HIGH, ScanReport.severityOf(dep("a", RiskLevel.UNKNOWN, null, 6, null, null, null)));
    }

    @Test
    void overallScoreIsWorstDependencyPlusSmallBumpForOtherVulnerableOnes() {
        var deps = List.of(
                dep("a", RiskLevel.HIGH, 80, 3, "CRITICAL", null, null),
                dep("b", RiskLevel.MEDIUM, 55, 1, "MEDIUM", null, null),
                dep("c", RiskLevel.MEDIUM, 55, 1, "MEDIUM", null, null),
                clean("d"));
        assertEquals(82, ScanReport.overallRiskScore(deps));
        assertEquals(0, ScanReport.overallRiskScore(List.of()));
        assertEquals(20, ScanReport.overallRiskScore(List.of(clean("x"))));
    }

    @Test
    void bumpIsCappedAtFiveAndScoreAtOneHundred() {
        var many = new java.util.ArrayList<DependencyRiskDto>();
        for (int i = 0; i < 20; i++) {
            many.add(dep("d" + i, RiskLevel.HIGH, 98, 1, "HIGH", null, null));
        }
        assertEquals(100, ScanReport.overallRiskScore(many));
        many.clear();
        for (int i = 0; i < 20; i++) {
            many.add(dep("d" + i, RiskLevel.MEDIUM, 50, 1, "MEDIUM", null, null));
        }
        assertEquals(55, ScanReport.overallRiskScore(many));
    }

    @Test
    void failsWhenAVulnerableDependencyReachesTheThreshold() {
        var deps = List.of(
                dep("tomcat", RiskLevel.HIGH, 80, 11, "CRITICAL", null, "10.1.36"),
                dep("logback", RiskLevel.HIGH, 70, 2, "HIGH", null, null),
                dep("spring", RiskLevel.MEDIUM, 55, 1, "MEDIUM", null, null),
                clean("clean-lib"));

        var result = ScanReport.evaluate("/proj", deps, HIGH);

        assertTrue(result.failed());
        assertEquals(2, result.failing().size());
        assertEquals("g:tomcat:1.0", result.failing().get(0).dependency(), "most severe first");
        assertEquals("10.1.36", result.failing().get(0).fixVersion());
        assertEquals(4, result.totalDependencies());
        assertEquals(3, result.vulnerableDependencies());
        assertEquals(Map.of(CRITICAL, 1, HIGH, 1, MEDIUM, 1, LOW, 1), result.counts());
    }

    @Test
    void passesWhenNothingReachesTheThreshold() {
        var result = ScanReport.evaluate("/proj", List.of(dep("spring", RiskLevel.MEDIUM, 55, 1, "MEDIUM", null, null), clean("x")), HIGH);
        assertFalse(result.failed());
        assertTrue(result.failing().isEmpty());
    }

    @Test
    void cleanDependenciesNeverFailTheBuildEvenAtTheLowestThreshold() {
        var result = ScanReport.evaluate("/proj", List.of(clean("a"), clean("b")), LOW);
        assertFalse(result.failed());
    }

    @Test
    void noThresholdNeverFails() {
        var result = ScanReport.evaluate("/proj", List.of(dep("a", RiskLevel.HIGH, 95, 12, "CRITICAL", true, null)), null);
        assertFalse(result.failed());
        assertNull(result.failOn());
        assertTrue(ScanReport.toText(result).contains("no fail-on threshold"));
    }

    @Test
    void dependenciesWithoutLookupResultsAreCountedAsUnchecked() {
        var unknown = dep("offline", RiskLevel.LOW, 20, null, null, null, null);
        var result = ScanReport.evaluate("/proj", List.of(clean("ok"), unknown, unknown), HIGH);

        assertEquals(2, result.uncheckedDependencies());
        assertTrue(ScanReport.toText(result).contains("2 of 3 dependencies could not be checked"));
        assertFalse(result.failed(), "unchecked dependencies are reported, they do not by themselves fail the findings check");
    }

    @Test
    void reportDoesNotSayPassedWhenDependenciesCouldNotBeChecked() {
        var unknown = dep("offline", RiskLevel.LOW, 20, null, null, null, null);
        String text = ScanReport.toText(ScanReport.evaluate("/proj", List.of(unknown), HIGH));

        assertTrue(text.contains("Result: INCOMPLETE (1 dependencies could not be checked)"));
        assertFalse(text.contains("Result: PASSED"));
    }

    @Test
    void fullyCheckedScanHasNoWarning() {
        var result = ScanReport.evaluate("/proj", List.of(clean("a"), clean("b")), HIGH);
        assertEquals(0, result.uncheckedDependencies());
        assertFalse(ScanReport.toText(result).contains("WARNING"));
    }

    @Test
    void thresholdParsing() {
        assertEquals(HIGH, ScanReport.Level.parseThreshold("high"));
        assertEquals(CRITICAL, ScanReport.Level.parseThreshold(" Critical "));
        assertNull(ScanReport.Level.parseThreshold("none"));
        assertNull(ScanReport.Level.parseThreshold(""));
        assertNull(ScanReport.Level.parseThreshold(null));
        assertThrows(IllegalArgumentException.class, () -> ScanReport.Level.parseThreshold("severe"));
    }

    @Test
    void textReportListsFindingsAndTheOutcome() {
        var result = ScanReport.evaluate("/proj", List.of(
                dep("tomcat", RiskLevel.HIGH, 80, 11, "CRITICAL", true, "10.1.36"), clean("x")), HIGH);
        String text = ScanReport.toText(result);

        assertTrue(text.contains("BuildAegis scan: /proj"));
        assertTrue(text.contains("g:tomcat:1.0"));
        assertTrue(text.contains("fix=10.1.36"));
        assertTrue(text.contains("[exploited in the wild]"));
        assertTrue(text.contains("Result: FAILED"));
    }

    @Test
    void textReportSaysPassedWhenClean() {
        String text = ScanReport.toText(ScanReport.evaluate("/proj", List.of(clean("x")), HIGH));
        assertTrue(text.contains("Result: PASSED"));
    }
}
