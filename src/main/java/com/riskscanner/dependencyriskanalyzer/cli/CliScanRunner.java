package com.riskscanner.dependencyriskanalyzer.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskscanner.dependencyriskanalyzer.dto.ProjectAnalysisRequest;
import com.riskscanner.dependencyriskanalyzer.dto.ProjectAnalysisResponse;
import com.riskscanner.dependencyriskanalyzer.service.ProjectAnalysisService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Command-line mode for CI pipelines. Active only when {@code buildaegis.cli.project} is set; in that case the
 * application starts without a web server, scans the project, prints a report and exits.
 *
 * <pre>
 * java -jar buildaegis.jar --buildaegis.cli.project=/path/to/project --buildaegis.cli.fail-on=high
 * </pre>
 *
 * <p>Options:
 * <ul>
 *   <li>{@code --buildaegis.cli.project}: project folder or build file (required to enable CLI mode)</li>
 *   <li>{@code --buildaegis.cli.fail-on}: {@code none|low|medium|high|critical} (default {@code high})</li>
 *   <li>{@code --buildaegis.cli.format}: {@code text} (default) or {@code json}</li>
 *   <li>{@code --buildaegis.cli.output}: write the report to this file instead of the console</li>
 * </ul>
 *
 * <p>Exit codes: {@code 0} passed, {@code 1} findings at or above the threshold, {@code 2} the scan or the options
 * were invalid. AI is never used in CLI mode.
 */
@Component
@ConditionalOnProperty(name = "buildaegis.cli.project")
public class CliScanRunner implements CommandLineRunner, ExitCodeGenerator {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FINDINGS = 1;
    public static final int EXIT_ERROR = 2;

    private final ProjectAnalysisService analysisService;
    private final ObjectMapper objectMapper;

    @Value("${buildaegis.cli.project}")
    private String project;

    @Value("${buildaegis.cli.fail-on:high}")
    private String failOn;

    @Value("${buildaegis.cli.format:text}")
    private String format;

    @Value("${buildaegis.cli.output:}")
    private String output;

    private int exitCode = EXIT_OK;

    public CliScanRunner(ProjectAnalysisService analysisService, ObjectMapper objectMapper) {
        this.analysisService = analysisService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        try {
            ScanReport.Level threshold = ScanReport.Level.parseThreshold(failOn);
            boolean json = "json".equalsIgnoreCase(format);
            if (!json && !"text".equalsIgnoreCase(format)) {
                throw new IllegalArgumentException("Invalid format '" + format + "'. Use text or json.");
            }

            ProjectAnalysisResponse response = analysisService.analyze(new ProjectAnalysisRequest(project, true, false));
            ScanReport.Result result = ScanReport.evaluate(project, response.results(), threshold);

            String report;
            if (json) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("result", result);
                body.put("dependencies", response.results());
                report = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(body);
            } else {
                report = ScanReport.toText(result);
            }
            write(report);

            exitCode = result.failed() ? EXIT_FINDINGS : EXIT_OK;
        } catch (Exception e) {
            System.err.println("BuildAegis scan failed: " + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            exitCode = EXIT_ERROR;
        }
    }

    private void write(String report) throws java.io.IOException {
        if (output != null && !output.isBlank()) {
            Files.writeString(Path.of(output), report, StandardCharsets.UTF_8);
            System.out.println("Report written to " + output);
        } else {
            System.out.println(report);
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
