package com.riskscanner.dependencyriskanalyzer.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UpgradeAdvisorTest {

    @Test
    void comparesVersionsNumerically() {
        assertTrue(UpgradeAdvisor.compareVersions("1.10.0", "1.9.9") > 0);
        assertEquals(0, UpgradeAdvisor.compareVersions("1.0", "1.0.0"));
        assertTrue(UpgradeAdvisor.compareVersions("2.0.0-beta", "2.0.0") < 0);
    }

    @Test
    void suggestsSmallestVersionFixingAllVulnerabilities() {
        String suggested = UpgradeAdvisor.suggestFixVersion("2.9.0", List.of(
                List.of("2.9.5", "2.10.1"),
                List.of("2.9.8", "2.10.3"),
                List.of("2.8.0")));
        assertEquals("2.9.8", suggested);
    }

    @Test
    void noSuggestionWhenNoFixKnown() {
        assertNull(UpgradeAdvisor.suggestFixVersion("1.0.0", List.of(List.of(), List.of("0.9.0"))));
    }

    @Test
    void describesChangeLevel() {
        assertTrue(UpgradeAdvisor.describeUpgrade("1.2.3", "2.0.0").contains("major"));
        assertTrue(UpgradeAdvisor.describeUpgrade("1.2.3", "1.3.0").contains("minor"));
        assertTrue(UpgradeAdvisor.describeUpgrade("1.2.3", "1.2.4").contains("patch"));
    }
}
