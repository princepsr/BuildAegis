package com.riskscanner.dependencyriskanalyzer.controller;

import com.riskscanner.dependencyriskanalyzer.dto.ProjectAnalysisRequest;
import com.riskscanner.dependencyriskanalyzer.dto.ProjectAnalysisResponse;
import com.riskscanner.dependencyriskanalyzer.service.DependencyScannerService;
import com.riskscanner.dependencyriskanalyzer.service.ProjectAnalysisService;
import org.springframework.web.bind.annotation.*;

/**
 * Project-level REST API.
 *
 * <p>Primary entry point used by the UI:
 * <ul>
 *   <li>{@code POST /api/project/analyze} - scan + enrich + AI analyze + cache</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/project")
public class ProjectAnalysisController {

    private final DependencyScannerService dependencyScannerService;
    private final ProjectAnalysisService projectAnalysisService;

    public ProjectAnalysisController(DependencyScannerService dependencyScannerService, ProjectAnalysisService projectAnalysisService) {
        this.dependencyScannerService = dependencyScannerService;
        this.projectAnalysisService = projectAnalysisService;
    }

    /**
     * Runs full analysis for a project.
     *
     * <p>See {@link com.riskscanner.dependencyriskanalyzer.service.ProjectAnalysisService} for the pipeline.
     */
    @PostMapping("/analyze")
    public ProjectAnalysisResponse analyze(@RequestBody ProjectAnalysisRequest request) throws Exception {
        return projectAnalysisService.analyze(request);
    }
}
