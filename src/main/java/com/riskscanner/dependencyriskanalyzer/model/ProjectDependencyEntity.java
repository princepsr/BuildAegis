package com.riskscanner.dependencyriskanalyzer.model;

import jakarta.persistence.*;

/**
 * Records which dependencies were found in which project at the last analysis, and whether each one is a
 * direct dependency of that project.
 *
 * <p>{@link DependencyRiskCacheEntity} is keyed by dependency only (shared across projects), so this table
 * lets the UI show cached results for one project and label direct vs transitive dependencies correctly.
 */
@Entity
@Table(
        name = "project_dependency",
        uniqueConstraints = @UniqueConstraint(columnNames = {"project_path", "group_id", "artifact_id", "version"})
)
public class ProjectDependencyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Normalized project key, see {@code ProjectPathKey}. */
    @Column(name = "project_path", nullable = false, length = 1000)
    private String projectPath;

    @Column(name = "group_id", nullable = false)
    private String groupId;

    @Column(name = "artifact_id", nullable = false)
    private String artifactId;

    @Column(nullable = false)
    private String version;

    @Column(name = "direct_dependency", nullable = false)
    private boolean direct;

    protected ProjectDependencyEntity() {
    }

    public ProjectDependencyEntity(String projectPath, String groupId, String artifactId, String version, boolean direct) {
        this.projectPath = projectPath;
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.direct = direct;
    }

    public Long getId() {
        return id;
    }

    public String getProjectPath() {
        return projectPath;
    }

    public String getGroupId() {
        return groupId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public boolean isDirect() {
        return direct;
    }
}
