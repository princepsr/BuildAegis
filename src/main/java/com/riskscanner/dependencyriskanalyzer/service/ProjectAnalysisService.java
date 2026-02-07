package com.riskscanner.dependencyriskanalyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.riskscanner.dependencyriskanalyzer.dto.DependencyRiskDto;
import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.dto.ProjectAnalysisRequest;
import com.riskscanner.dependencyriskanalyzer.dto.ProjectAnalysisResponse;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.model.DependencyRiskCacheEntity;
import com.riskscanner.dependencyriskanalyzer.repository.DependencyRiskCacheRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.riskscanner.dependencyriskanalyzer.service.ai.FallbackExplanationService;
import com.riskscanner.dependencyriskanalyzer.service.ai.DeterministicRiskCalculator;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates end-to-end project analysis.
 *
 * <p>Pipeline per request:
 * <ol>
 *   <li>Scan project build files for dependencies ({@link DependencyScannerService}).</li>
 *   <li>Optionally enrich each dependency with external metadata ({@link MetadataEnrichmentService}).</li>
 *   <li>Analyze each dependency via AI ({@link AIAnalysisService}).</li>
 *   <li>Cache results in the local database ({@link DependencyRiskCacheRepository}).</li>
 * </ol>
 *
 * <p>Caching notes:
 * <ul>
 *   <li>Cache keys include dependency coordinates + AI provider + model.</li>
 *   <li>Use {@link ProjectAnalysisRequest#forceRefresh()} to bypass cached results.</li>
 * </ul>
 */
@Service
public class ProjectAnalysisService {

    private final DependencyScannerService dependencyScannerService;
    private final AIAnalysisService aiAnalysisService;
    private final AiSettingsService aiSettingsService;
    private final DependencyRiskCacheRepository cacheRepository;
    private final MetadataEnrichmentService metadataEnrichmentService;
    private final ObjectMapper objectMapper;
    private final DeterministicRiskCalculator riskCalculator;
    private final FallbackExplanationService fallbackExplanationService;

    public ProjectAnalysisService(
            DependencyScannerService dependencyScannerService,
            AIAnalysisService aiAnalysisService,
            AiSettingsService aiSettingsService,
            DependencyRiskCacheRepository cacheRepository,
            MetadataEnrichmentService metadataEnrichmentService,
            ObjectMapper objectMapper,
            DeterministicRiskCalculator riskCalculator,
            FallbackExplanationService fallbackExplanationService
    ) {
        this.dependencyScannerService = dependencyScannerService;
        this.aiAnalysisService = aiAnalysisService;
        this.aiSettingsService = aiSettingsService;
        this.cacheRepository = cacheRepository;
        this.metadataEnrichmentService = metadataEnrichmentService;
        this.objectMapper = objectMapper;
        this.riskCalculator = riskCalculator;
        this.fallbackExplanationService = fallbackExplanationService;
    }

    /**
     * Scans a project and returns a per-dependency risk assessment.
     *
     * @param request request containing the project path and whether to bypass cache
     * @return the analysis response
     */
    @Transactional
    public ProjectAnalysisResponse analyze(ProjectAnalysisRequest request) throws Exception {
        if (request == null || request.projectPath() == null || request.projectPath().isBlank()) {
            throw new IllegalArgumentException("projectPath is required");
        }

        boolean forceRefresh = request.forceRefresh();
        boolean aiEnabled = Boolean.TRUE.equals(request.aiEnabled());
        String provider = aiEnabled ? aiSettingsService.getProvider() : "disabled";
        String model = aiEnabled ? aiSettingsService.getModel() : "disabled";

        List<DependencyCoordinate> scanned = dependencyScannerService.scanProject(request.projectPath());

        Map<String, DependencyCoordinate> deduped = new LinkedHashMap<>();
        for (DependencyCoordinate d : scanned) {
            String key = d.groupId() + ":" + d.artifactId() + ":" + d.version() + ":" + d.buildTool();
            deduped.putIfAbsent(key, d);
        }

        List<DependencyRiskDto> results = new ArrayList<>();
        Instant analyzedAt = Instant.now();

        for (DependencyCoordinate dependency : deduped.values()) {
            DependencyRiskCacheEntity cached = cacheRepository
                    .findByGroupIdAndArtifactIdAndVersionAndProviderAndModel(
                            dependency.groupId(),
                            dependency.artifactId(),
                            dependency.version(),
                            provider,
                            model)
                    .orElse(null);

            if (cached != null && !forceRefresh) {
                DependencyEnrichmentDto enrichment = cached.getEnrichmentJson() == null
                        ? null
                        : objectMapper.readValue(cached.getEnrichmentJson(), DependencyEnrichmentDto.class);

                results.add(new DependencyRiskDto(
                        cached.getGroupId(),
                        cached.getArtifactId(),
                        cached.getVersion(),
                        cached.getBuildTool(),
                        enrichment,
                        cached.getRiskLevel(),
                        cached.getRiskScore(),
                        cached.getExplanation(),
                        objectMapper.readValue(cached.getRecommendationsJson(), new TypeReference<List<String>>() {}),
                        true,
                        cached.getAnalyzedAt(),
                        cached.getProvider(),
                        cached.getModel(),
                        dependency.isDirect()
                ));
                continue;
            }

            DependencyEnrichmentDto enrichment = metadataEnrichmentService.enrich(dependency);

            AIAnalysisService.DependencyRiskAnalysisResult analysis;
            if (aiEnabled) {
                analysis = aiAnalysisService.analyzeDependencyRisk(dependency, enrichment);
            } else {
                DeterministicRiskCalculator.RiskResult riskResult = riskCalculator.calculateRisk(dependency, enrichment);
                var fallback = fallbackExplanationService.explainRisk(
                        dependency,
                        enrichment,
                        riskResult.riskLevel().name(),
                        riskResult.riskScore()
                );
                analysis = new AIAnalysisService.DependencyRiskAnalysisResult(
                        riskResult.riskLevel(),
                        riskResult.riskScore(),
                        fallback.explanation(),
                        fallback.recommendations()
                );
            }

            DependencyRiskCacheEntity entity = cached == null ? new DependencyRiskCacheEntity() : cached;
            entity.setGroupId(dependency.groupId());
            entity.setArtifactId(dependency.artifactId());
            entity.setVersion(dependency.version());
            entity.setBuildTool(dependency.buildTool());
            entity.setProvider(provider);
            entity.setModel(model);
            entity.setRiskLevel(analysis.riskLevel());
            entity.setRiskScore(analysis.riskScore());
            entity.setExplanation(analysis.explanation());
            entity.setRecommendationsJson(objectMapper.writeValueAsString(analysis.recommendations()));
            entity.setEnrichmentJson(objectMapper.writeValueAsString(enrichment));
            entity.setAnalyzedAt(Instant.now());
            cacheRepository.save(entity);

            results.add(new DependencyRiskDto(
                    entity.getGroupId(),
                    entity.getArtifactId(),
                    entity.getVersion(),
                    entity.getBuildTool(),
                    enrichment,
                    entity.getRiskLevel(),
                    entity.getRiskScore(),
                    entity.getExplanation(),
                    analysis.recommendations(),
                    false,
                    entity.getAnalyzedAt(),
                    entity.getProvider(),
                    entity.getModel(),
                    dependency.isDirect()
            ));
        }

        return new ProjectAnalysisResponse(request.projectPath(), analyzedAt, results);
    }
}
