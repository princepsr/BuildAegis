package com.riskscanner.dependencyriskanalyzer.service;

import com.lowagie.text.Document;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.riskscanner.dependencyriskanalyzer.dto.DependencyRiskDto;
import com.riskscanner.dependencyriskanalyzer.dto.ProjectAnalysisResponse;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

/**
 * Generates a simple PDF report for a {@link ProjectAnalysisResponse}.
 *
 * <p>Used by {@code /api/export/pdf}. This intentionally produces a compact summary suitable
 * for sharing (dependency + risk + score + explanation).
 */
@Service
public class PdfExportService {

    /**
     * Generate a PDF report.
     *
     * @param response analysis response
     * @return PDF bytes
     */
    public byte[] generate(ProjectAnalysisResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document();
            PdfWriter.getInstance(document, out);
            document.open();

            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16);
            Font normal = FontFactory.getFont(FontFactory.HELVETICA, 10);

            document.add(new Paragraph("Risk Scanner Report", title));
            document.add(new Paragraph("Project: " + (response.projectPath() == null || response.projectPath().isBlank() ? "(cached results)" : response.projectPath()), normal));
            document.add(new Paragraph("Analyzed At: " + response.analyzedAt(), normal));
            document.add(new Paragraph(" "));

            PdfPTable table = new PdfPTable(new float[]{3f, 1.1f, 1f, 4f});
            table.setWidthPercentage(100);

            table.addCell(new Phrase("Dependency", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10)));
            table.addCell(new Phrase("Risk", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10)));
            table.addCell(new Phrase("Score", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10)));
            table.addCell(new Phrase("Explanation", FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10)));

            java.util.List<DependencyRiskDto> rows = response.results() == null ? java.util.List.of() : response.results();
            for (DependencyRiskDto r : rows) {
                String dep = r.groupId() + ":" + r.artifactId() + ":" + r.version();
                table.addCell(new Phrase(dep, normal));
                table.addCell(new Phrase(String.valueOf(r.riskLevel()), normal));
                table.addCell(new Phrase(r.riskScore() == null ? "" : String.valueOf(r.riskScore()), normal));
                table.addCell(new Phrase(r.explanation() == null ? "" : r.explanation(), normal));
            }

            document.add(table);
            document.close();
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate PDF", e);
        }
    }
}
