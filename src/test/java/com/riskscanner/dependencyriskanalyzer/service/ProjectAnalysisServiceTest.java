package com.riskscanner.dependencyriskanalyzer.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.dto.ProjectAnalysisRequest;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.model.DependencyRiskCacheEntity;
import com.riskscanner.dependencyriskanalyzer.model.ProjectDependencyEntity;
import com.riskscanner.dependencyriskanalyzer.model.RiskLevel;
import com.riskscanner.dependencyriskanalyzer.repository.DependencyRiskCacheRepository;
import com.riskscanner.dependencyriskanalyzer.repository.ProjectDependencyRepository;
import com.riskscanner.dependencyriskanalyzer.service.ai.DeterministicRiskCalculator;
import com.riskscanner.dependencyriskanalyzer.service.ai.FallbackExplanationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectAnalysisServiceTest {

    private DependencyScannerService scanner;
    private AIAnalysisService aiAnalysisService;
    private AiSettingsService aiSettings;
    private DependencyRiskCacheRepository cacheRepository;
    private MetadataEnrichmentService metadata;
    private ProjectDependencyRepository projectDependencies;
    private ProjectAnalysisService service;

    private final DependencyCoordinate direct = new DependencyCoordinate("g", "direct", "1.0", "maven", "compile", true);
    private final DependencyCoordinate transitive = new DependencyCoordinate("g", "trans", "2.0", "maven", "compile", false);

    @BeforeEach
    void setUp() throws Exception {
        scanner = mock(DependencyScannerService.class);
        aiAnalysisService = mock(AIAnalysisService.class);
        aiSettings = mock(AiSettingsService.class);
        cacheRepository = mock(DependencyRiskCacheRepository.class);
        metadata = mock(MetadataEnrichmentService.class);
        projectDependencies = mock(ProjectDependencyRepository.class);

        when(aiSettings.getProvider()).thenReturn("openai");
        when(aiSettings.getModel()).thenReturn("gpt");
        when(scanner.scanProject(anyString())).thenReturn(List.of(direct, transitive));
        when(metadata.enrich(any())).thenAnswer(i -> new DependencyEnrichmentDto("Maven", "x", "1", 1, List.of("CVE-1"),
                null, null, null, null, null, null, "HIGH", null, null, null));
        when(cacheRepository.findByGroupIdAndArtifactIdAndVersionAndProviderAndModel(anyString(), anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(cacheRepository.save(any(DependencyRiskCacheEntity.class))).thenAnswer(i -> i.getArgument(0));

        service = new ProjectAnalysisService(scanner, aiAnalysisService, aiSettings, cacheRepository, metadata,
                new ObjectMapper().registerModule(new JavaTimeModule()), new DeterministicRiskCalculator(),
                new FallbackExplanationService(), projectDependencies);
    }

    private static AIAnalysisService.DependencyRiskAnalysisResult aiResult(boolean fromAi, boolean cacheable) {
        return new AIAnalysisService.DependencyRiskAnalysisResult(RiskLevel.HIGH, 80, "explanation", List.of("rec"), fromAi, cacheable);
    }

    @Test
    void failedAiResultsAreNotCachedButSuccessfulOnesAre() throws Exception {
        when(aiAnalysisService.analyzeDependencyRisk(any(), any()))
                .thenReturn(aiResult(false, false))   // AI failed (e.g. 429): stand-in text
                .thenReturn(aiResult(true, true));    // AI worked

        var response = service.analyze(new ProjectAnalysisRequest("/proj", true, true));

        assertEquals(2, response.results().size());
        verify(cacheRepository, times(1)).save(any(DependencyRiskCacheEntity.class));
    }

    @Test
    void templateExplanationsChosenOnPurposeAreCached() throws Exception {
        when(aiAnalysisService.analyzeDependencyRisk(any(), any())).thenReturn(aiResult(false, true));

        service.analyze(new ProjectAnalysisRequest("/proj", true, true));

        verify(cacheRepository, times(2)).save(any(DependencyRiskCacheEntity.class));
    }

    @Test
    void withoutAiEverythingIsCachedAndAiIsNeverCalled() throws Exception {
        service.analyze(new ProjectAnalysisRequest("/proj", true, false));

        verify(aiAnalysisService, never()).analyzeDependencyRisk(any(), any());
        verify(cacheRepository, times(2)).save(any(DependencyRiskCacheEntity.class));
    }

    @Test
    void cachedResultsAreReturnedWithoutCallingAiOrEnrichment() throws Exception {
        var cached = new DependencyRiskCacheEntity();
        cached.setGroupId("g");
        cached.setArtifactId("direct");
        cached.setVersion("1.0");
        cached.setBuildTool("maven");
        cached.setProvider("openai");
        cached.setModel("gpt");
        cached.setRiskLevel(RiskLevel.MEDIUM);
        cached.setRiskScore(50);
        cached.setExplanation("from cache");
        cached.setRecommendationsJson("[\"r\"]");
        when(cacheRepository.findByGroupIdAndArtifactIdAndVersionAndProviderAndModel("g", "direct", "1.0", "openai", "gpt"))
                .thenReturn(Optional.of(cached));
        when(scanner.scanProject(anyString())).thenReturn(List.of(direct));

        var response = service.analyze(new ProjectAnalysisRequest("/proj", false, true));

        assertEquals(1, response.results().size());
        assertTrue(response.results().get(0).fromCache());
        assertEquals("from cache", response.results().get(0).explanation());
        verify(aiAnalysisService, never()).analyzeDependencyRisk(any(), any());
        verify(metadata, never()).enrich(any());
    }

    @Test
    void recordsWhichDependenciesBelongToWhichProjectAndWhetherTheyAreDirect() throws Exception {
        service.analyze(new ProjectAnalysisRequest("/proj/pom.xml", true, false));

        String key = ProjectPathKey.of("/proj/pom.xml");
        verify(projectDependencies).deleteByProjectPath(key);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<ProjectDependencyEntity>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(projectDependencies).saveAll(captor.capture());
        List<ProjectDependencyEntity> saved = new java.util.ArrayList<>();
        captor.getValue().forEach(saved::add);

        assertEquals(2, saved.size());
        assertTrue(saved.stream().allMatch(e -> e.getProjectPath().equals(key)));
        assertTrue(saved.stream().filter(e -> e.getArtifactId().equals("direct")).findFirst().orElseThrow().isDirect());
        assertFalse(saved.stream().filter(e -> e.getArtifactId().equals("trans")).findFirst().orElseThrow().isDirect());
    }

    @Test
    void duplicatesAreAnalyzedOnce() throws Exception {
        when(scanner.scanProject(anyString())).thenReturn(List.of(direct, direct, transitive));

        var response = service.analyze(new ProjectAnalysisRequest("/proj", true, false));

        assertEquals(2, response.results().size());
    }

    @Test
    void aMissingProjectPathIsRejected() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> service.analyze(new ProjectAnalysisRequest(" ", true, false)));
    }
}
