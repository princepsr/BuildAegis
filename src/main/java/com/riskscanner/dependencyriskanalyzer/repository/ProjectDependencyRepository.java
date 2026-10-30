package com.riskscanner.dependencyriskanalyzer.repository;

import com.riskscanner.dependencyriskanalyzer.model.ProjectDependencyEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProjectDependencyRepository extends JpaRepository<ProjectDependencyEntity, Long> {

    List<ProjectDependencyEntity> findByProjectPath(String projectPath);

    List<ProjectDependencyEntity> findByGroupIdAndArtifactIdAndVersion(String groupId, String artifactId, String version);

    /**
     * Bulk delete that executes immediately, so re-inserting the same coordinates in the same transaction
     * does not violate the unique constraint (Hibernate would otherwise flush inserts before deletes).
     */
    @Modifying
    @Query("delete from ProjectDependencyEntity e where e.projectPath = :projectPath")
    void deleteByProjectPath(@Param("projectPath") String projectPath);
}
