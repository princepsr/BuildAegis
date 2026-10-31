package com.riskscanner.dependencyriskanalyzer.service.dependency;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavenDependencyResolverTest {

    @TempDir
    Path tmp;

    @Test
    void scanningAProjectThatIsNotPublishedAnywhereDoesNotTryToDownloadTheProjectItself() throws Exception {
        // The project's own coordinates (this group, artifact and version exist in no repository) must not be resolved
        // remotely: only its declared dependencies are looked up. With no dependencies nothing needs to be downloaded.
        Files.writeString(tmp.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0">
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.example.unpublished</groupId>
                  <artifactId>never-released</artifactId>
                  <version>9.9.9-not-on-central</version>
                </project>
                """);

        var result = assertDoesNotThrow(() -> new MavenDependencyResolver().resolveDependencies(tmp));

        assertTrue(result.isEmpty());
    }
}
