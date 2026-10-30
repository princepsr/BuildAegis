package com.riskscanner.dependencyriskanalyzer.repository;

import com.riskscanner.dependencyriskanalyzer.model.ProjectDependencyEntity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
class ProjectDependencyRepositoryTest {

    @Autowired
    ProjectDependencyRepository repository;

    @Test
    void reanalyzingReplacesTheProjectsDependenciesWithoutUniqueViolations() {
        repository.saveAll(List.of(
                new ProjectDependencyEntity("p1", "g", "a", "1.0", true),
                new ProjectDependencyEntity("p1", "g", "b", "1.0", false),
                new ProjectDependencyEntity("p2", "g", "a", "1.0", false)));
        repository.flush();

        // Same transaction: delete the project's rows, then insert the same coordinates again (plus a new one)
        repository.deleteByProjectPath("p1");
        repository.saveAll(List.of(
                new ProjectDependencyEntity("p1", "g", "a", "1.0", false),
                new ProjectDependencyEntity("p1", "g", "c", "2.0", true)));
        repository.flush();

        var p1 = repository.findByProjectPath("p1");
        assertEquals(2, p1.size());
        assertFalse(p1.stream().filter(d -> d.getArtifactId().equals("a")).findFirst().orElseThrow().isDirect());
        assertTrue(p1.stream().anyMatch(d -> d.getArtifactId().equals("c")));
        // other projects are untouched
        assertEquals(1, repository.findByProjectPath("p2").size());
    }

    @Test
    void sameDependencyCanBeDirectInOneProjectAndTransitiveInAnother() {
        repository.saveAll(List.of(
                new ProjectDependencyEntity("p1", "g", "a", "1.0", true),
                new ProjectDependencyEntity("p2", "g", "a", "1.0", false)));
        repository.flush();

        var rows = repository.findByGroupIdAndArtifactIdAndVersion("g", "a", "1.0");
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(ProjectDependencyEntity::isDirect));
    }
}
