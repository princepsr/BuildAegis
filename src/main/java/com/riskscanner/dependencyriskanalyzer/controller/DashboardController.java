package com.riskscanner.dependencyriskanalyzer.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.dto.DependencyRiskDto;
import com.riskscanner.dependencyriskanalyzer.model.DependencyRiskCacheEntity;
import com.riskscanner.dependencyriskanalyzer.model.ProjectDependencyEntity;
import com.riskscanner.dependencyriskanalyzer.repository.DependencyRiskCacheRepository;
import com.riskscanner.dependencyriskanalyzer.repository.ProjectDependencyRepository;
import com.riskscanner.dependencyriskanalyzer.service.AiSettingsService;
import com.riskscanner.dependencyriskanalyzer.service.ProjectPathKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only endpoints for displaying cached results.
 *
 * <p>Cache lookups are scoped to the currently configured AI provider/model.
 */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    private final DependencyRiskCacheRepository cacheRepository;
    private final ProjectDependencyRepository projectDependencyRepository;
    private final AiSettingsService aiSettingsService;
    private final ObjectMapper objectMapper;

    public DashboardController(
            DependencyRiskCacheRepository cacheRepository,
            ProjectDependencyRepository projectDependencyRepository,
            AiSettingsService aiSettingsService,
            ObjectMapper objectMapper
    ) {
        this.cacheRepository = cacheRepository;
        this.projectDependencyRepository = projectDependencyRepository;
        this.aiSettingsService = aiSettingsService;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns cached dependency risk results for the currently configured provider/model.
     *
     * @param projectPath optional project folder or build file; when given, only the dependencies found in that
     *                    project at its last analysis are returned. Without it, all cached results are returned.
     */
    @GetMapping("/cached-results")
    public List<DependencyRiskDto> getCachedResults(
            @RequestParam(name = "projectPath", required = false) String projectPath
    ) throws Exception {
        String provider = aiSettingsService.getProvider();
        String model = aiSettingsService.getModel();

        List<DependencyRiskDto> results = new ArrayList<>();

        if (projectPath != null && !projectPath.isBlank()) {
            for (ProjectDependencyEntity dep : projectDependencyRepository.findByProjectPath(ProjectPathKey.of(projectPath))) {
                var cached = cacheRepository.findByGroupIdAndArtifactIdAndVersionAndProviderAndModel(
                        dep.getGroupId(), dep.getArtifactId(), dep.getVersion(), provider, model);
                if (cached.isPresent()) {
                    results.add(toDto(cached.get(), dep.isDirect()));
                }
            }
            return results;
        }

        for (var entity : cacheRepository.findByProviderAndModel(provider, model)) {
            results.add(toDto(entity, isDirectInAnyProject(entity)));
        }
        return results;
    }

    /**
     * Convenience for callers that want every cached result (e.g. exports without a project path).
     */
    public List<DependencyRiskDto> getCachedResults() throws Exception {
        return getCachedResults(null);
    }

    /**
     * Without a project there is no single answer: a dependency is direct if it is direct in any analyzed project.
     * Dependencies analyzed before projects were recorded have no information and keep the previous default (direct).
     */
    private boolean isDirectInAnyProject(DependencyRiskCacheEntity entity) {
        var rows = projectDependencyRepository.findByGroupIdAndArtifactIdAndVersion(
                entity.getGroupId(), entity.getArtifactId(), entity.getVersion());
        return rows.isEmpty() || rows.stream().anyMatch(ProjectDependencyEntity::isDirect);
    }

    private DependencyRiskDto toDto(DependencyRiskCacheEntity entity, boolean direct) throws Exception {
        List<String> recommendations = objectMapper.readValue(
                entity.getRecommendationsJson(),
                new TypeReference<List<String>>() {}
        );

        DependencyEnrichmentDto enrichment = entity.getEnrichmentJson() == null
                ? null
                : objectMapper.readValue(entity.getEnrichmentJson(), DependencyEnrichmentDto.class);

        return new DependencyRiskDto(
                entity.getGroupId(),
                entity.getArtifactId(),
                entity.getVersion(),
                entity.getBuildTool(),
                enrichment,
                entity.getRiskLevel(),
                entity.getRiskScore(),
                entity.getExplanation(),
                recommendations,
                true,
                entity.getAnalyzedAt(),
                entity.getProvider(),
                entity.getModel(),
                direct
        );
    }
}
