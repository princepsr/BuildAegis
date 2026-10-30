package com.riskscanner.dependencyriskanalyzer.service;

import com.riskscanner.dependencyriskanalyzer.dto.ProjectAnalysisResponse;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PdfExportServiceTest {

    private final PdfExportService service = new PdfExportService();

    @Test
    void generatesPdfWhenProjectPathIsMissing() {
        byte[] pdf = service.generate(new ProjectAnalysisResponse(null, Instant.now(), List.of()));
        assertTrue(pdf.length > 0);
        assertTrue(new String(pdf, 0, 4).equals("%PDF"));
    }

    @Test
    void generatesPdfWhenResultsAreNull() {
        byte[] pdf = service.generate(new ProjectAnalysisResponse("", Instant.now(), null));
        assertTrue(pdf.length > 0);
    }
}
