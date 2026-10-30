package com.riskscanner.dependencyriskanalyzer.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskscanner.dependencyriskanalyzer.dto.ProjectAnalysisRequest;
import com.riskscanner.dependencyriskanalyzer.dto.ProjectAnalysisResponse;
import com.riskscanner.dependencyriskanalyzer.service.ProjectAnalysisService;
import com.riskscanner.dependencyriskanalyzer.service.PdfExportService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;

/**
 * Export endpoints for reports.
 *
 * <p>Both endpoints accept a {@link ProjectAnalysisRequest} and invoke the same analysis pipeline as
 * {@code /api/project/analyze}. The response is returned as a downloadable attachment.
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    private final ProjectAnalysisService projectAnalysisService;
    private final ObjectMapper objectMapper;
    private final PdfExportService pdfExportService;
    private final DashboardController dashboardController;

    public ExportController(ProjectAnalysisService projectAnalysisService, ObjectMapper objectMapper, PdfExportService pdfExportService, DashboardController dashboardController) {
        this.dashboardController = dashboardController;
        this.projectAnalysisService = projectAnalysisService;
        this.objectMapper = objectMapper;
        this.pdfExportService = pdfExportService;
    }

    /**
     * Generates an analysis and returns it as a JSON download.
     */
    @PostMapping(value = "/json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<byte[]> exportJson(@RequestBody ProjectAnalysisRequest request) throws Exception {
        var response = resolveResponse(request);

        String filename = "risk-report-" + Instant.now().toString().replace(":", "-") + ".json";
        byte[] bytes = objectMapper.writeValueAsBytes(response);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_JSON)
                .contentLength(bytes.length)
                .body(bytes);
    }

    /**
     * Generates an analysis and returns it as a PDF download.
     */
    @PostMapping(value = "/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> exportPdf(@RequestBody ProjectAnalysisRequest request) throws Exception {
        var response = resolveResponse(request);
        byte[] pdfBytes = pdfExportService.generate(response);

        String filename = "risk-report-" + Instant.now().toString().replace(":", "-") + ".pdf";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_PDF)
                .contentLength(pdfBytes.length)
                .body(pdfBytes);
    }

    /**
     * Runs the analysis for the requested project, or, when no project path is given (e.g. the UI is
     * showing cached results), exports the cached results for the current AI provider/model.
     */
    private ProjectAnalysisResponse resolveResponse(ProjectAnalysisRequest request) throws Exception {
        if (request == null || request.projectPath() == null || request.projectPath().isBlank()) {
            return new ProjectAnalysisResponse(null, Instant.now(), dashboardController.getCachedResults());
        }
        return projectAnalysisService.analyze(request);
    }
}
