package com.riskscanner.dependencyriskanalyzer.dto;

/**
 * Request DTO for running a project analysis.
 *
 * @param projectPath  directory containing a supported build file (or direct build file path)
 * @param forceRefresh when true, bypasses cached results and re-runs enrichment + AI analysis
 * @param aiEnabled    when true, the backend may call the configured AI provider; when false/null, AI is never called
 */
public record ProjectAnalysisRequest(
        String projectPath,
        boolean forceRefresh,
        Boolean aiEnabled
) {
}
