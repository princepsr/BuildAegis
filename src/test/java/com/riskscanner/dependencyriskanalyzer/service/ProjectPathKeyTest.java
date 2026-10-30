package com.riskscanner.dependencyriskanalyzer.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class ProjectPathKeyTest {

    @Test
    void folderAndBuildFileInsideItShareAKey() {
        assertEquals(ProjectPathKey.of("proj-a"), ProjectPathKey.of("proj-a/pom.xml"));
        assertEquals(ProjectPathKey.of("proj-a"), ProjectPathKey.of("proj-a/build.gradle.kts"));
    }

    @Test
    void trailingSeparatorsAndDotsAreNormalized() {
        assertEquals(ProjectPathKey.of("proj-a"), ProjectPathKey.of("proj-a/"));
        assertEquals(ProjectPathKey.of("proj-a"), ProjectPathKey.of("./proj-a/../proj-a"));
    }

    @Test
    void differentProjectsHaveDifferentKeys() {
        assertNotEquals(ProjectPathKey.of("proj-a"), ProjectPathKey.of("proj-b"));
    }

    @Test
    void blankPathGivesEmptyKey() {
        assertEquals("", ProjectPathKey.of(null));
        assertEquals("", ProjectPathKey.of("  "));
    }
}
