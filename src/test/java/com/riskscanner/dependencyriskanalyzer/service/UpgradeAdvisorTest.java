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
    void prefersAFixOnTheSameMajorLine() {
        // spring-webmvc 6.2.2: most advisories are fixed in 6.2.x, two only in 7.0.9
        List<List<String>> fixes = List.of(
                List.of("7.0.8", "6.2.19"),
                List.of("7.0.6", "6.2.17"),
                List.of("7.0.9"),
                List.of("7.0.9"));

        assertEquals("6.2.19", UpgradeAdvisor.suggestFixVersion("6.2.2", fixes));
        String note = UpgradeAdvisor.fixNote("6.2.19", fixes);
        assertTrue(note.startsWith("2 of 4 vulnerabilities"));
        assertTrue(note.contains("not fixed in this minor version (6.2.x)"));
        assertTrue(note.contains("major upgrade to 7.0.9"));
    }

    @Test
    void fallsBackToAnotherMajorWhenNothingIsFixedOnTheCurrentLine() {
        List<List<String>> fixes = List.of(List.of("7.0.9"));
        assertEquals("7.0.9", UpgradeAdvisor.suggestFixVersion("6.2.2", fixes));
        assertNull(UpgradeAdvisor.fixNote("7.0.9", fixes));
    }

    @Test
    void noNoteWhenTheSuggestionCoversEverything() {
        List<List<String>> fixes = List.of(List.of("2.9.5", "2.10.1"), List.of("2.9.8"));
        assertNull(UpgradeAdvisor.fixNote(UpgradeAdvisor.suggestFixVersion("2.9.0", fixes), fixes));
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
