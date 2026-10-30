package com.riskscanner.dependencyriskanalyzer.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.riskscanner.dependencyriskanalyzer.BuildAegisApplication;
import com.riskscanner.dependencyriskanalyzer.dto.DependencyEnrichmentDto;
import com.riskscanner.dependencyriskanalyzer.dto.DependencyRiskDto;
import com.riskscanner.dependencyriskanalyzer.dto.ProjectAnalysisRequest;
import com.riskscanner.dependencyriskanalyzer.dto.ProjectAnalysisResponse;
import com.riskscanner.dependencyriskanalyzer.model.RiskLevel;
import com.riskscanner.dependencyriskanalyzer.service.ProjectAnalysisService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CliScanRunnerTest {

    @TempDir
    Path tmp;

    private ProjectAnalysisService analysisService;
    private CliScanRunner runner;
    private final ByteArrayOutputStream out = new ByteArrayOutputStream();
    private final ByteArrayOutputStream err = new ByteArrayOutputStream();
    private PrintStream originalOut;
    private PrintStream originalErr;

    private static DependencyRiskDto dep(String artifact, RiskLevel level, int score, int vulns, String severity) {
        var e = new DependencyEnrichmentDto("Maven", "g:" + artifact, "1.0", vulns, List.of(), null, null, null, null, null,
                null, severity, null, null, null);
        return new DependencyRiskDto("g", artifact, "1.0", "maven", e, level, score, "x", List.of(), false, null, "disabled", "disabled", true);
    }

    @BeforeEach
    void setUp() throws Exception {
        analysisService = mock(ProjectAnalysisService.class);
        runner = new CliScanRunner(analysisService, new ObjectMapper().registerModule(new JavaTimeModule()));
        ReflectionTestUtils.setField(runner, "project", "/proj");
        ReflectionTestUtils.setField(runner, "failOn", "high");
        ReflectionTestUtils.setField(runner, "format", "text");
        ReflectionTestUtils.setField(runner, "output", "");

        originalOut = System.out;
        originalErr = System.err;
        System.setOut(new PrintStream(out, true));
        System.setErr(new PrintStream(err, true));
    }

    private void restoreStreams() {
        System.setOut(originalOut);
        System.setErr(originalErr);
    }

    private void respondWith(DependencyRiskDto... deps) throws Exception {
        when(analysisService.analyze(any())).thenReturn(new ProjectAnalysisResponse("/proj", Instant.now(), List.of(deps)));
    }

    @Test
    void exitsWithOneWhenAFindingReachesTheThreshold() throws Exception {
        respondWith(dep("tomcat", RiskLevel.HIGH, 80, 11, "CRITICAL"));
        runner.run();
        restoreStreams();

        assertEquals(CliScanRunner.EXIT_FINDINGS, runner.getExitCode());
        assertTrue(out.toString().contains("Result: FAILED"));
    }

    @Test
    void exitsWithZeroWhenNothingReachesTheThreshold() throws Exception {
        respondWith(dep("spring", RiskLevel.MEDIUM, 55, 1, "MEDIUM"));
        runner.run();
        restoreStreams();

        assertEquals(CliScanRunner.EXIT_OK, runner.getExitCode());
        assertTrue(out.toString().contains("Result: PASSED"));
    }

    @Test
    void scansWithAiDisabledAndFreshData() throws Exception {
        respondWith();
        runner.run();
        restoreStreams();

        ArgumentCaptor<ProjectAnalysisRequest> captor = ArgumentCaptor.forClass(ProjectAnalysisRequest.class);
        verify(analysisService).analyze(captor.capture());
        assertEquals("/proj", captor.getValue().projectPath());
        assertTrue(captor.getValue().forceRefresh());
        assertEquals(Boolean.FALSE, captor.getValue().aiEnabled());
    }

    @Test
    void exitsWithTwoWhenTheScanFails() throws Exception {
        when(analysisService.analyze(any())).thenThrow(new Exception("No supported build file found in: /proj"));
        runner.run();
        restoreStreams();

        assertEquals(CliScanRunner.EXIT_ERROR, runner.getExitCode());
        assertTrue(err.toString().contains("No supported build file found"));
    }

    @Test
    void invalidOptionsAreAnErrorNotAPass() throws Exception {
        ReflectionTestUtils.setField(runner, "failOn", "severe");
        runner.run();
        assertEquals(CliScanRunner.EXIT_ERROR, runner.getExitCode());

        ReflectionTestUtils.setField(runner, "failOn", "high");
        ReflectionTestUtils.setField(runner, "format", "xml");
        runner.run();
        restoreStreams();
        assertEquals(CliScanRunner.EXIT_ERROR, runner.getExitCode());
    }

    @Test
    void failOnNoneNeverFails() throws Exception {
        ReflectionTestUtils.setField(runner, "failOn", "none");
        respondWith(dep("tomcat", RiskLevel.HIGH, 95, 12, "CRITICAL"));
        runner.run();
        restoreStreams();

        assertEquals(CliScanRunner.EXIT_OK, runner.getExitCode());
    }

    @Test
    void jsonReportCanBeWrittenToAFile() throws Exception {
        Path file = tmp.resolve("report.json");
        ReflectionTestUtils.setField(runner, "format", "json");
        ReflectionTestUtils.setField(runner, "output", file.toString());
        respondWith(dep("tomcat", RiskLevel.HIGH, 80, 11, "CRITICAL"), dep("clean", RiskLevel.LOW, 20, 0, null));

        runner.run();
        restoreStreams();

        assertEquals(CliScanRunner.EXIT_FINDINGS, runner.getExitCode());
        JsonNode json = new ObjectMapper().readTree(Files.readString(file));
        assertEquals(2, json.get("result").get("totalDependencies").asInt());
        assertTrue(json.get("result").get("failed").asBoolean());
        assertEquals(2, json.get("dependencies").size());
        assertFalse(out.toString().contains("\"dependencies\""), "report goes to the file, not the console");
    }

    @Test
    void cliModeIsDetectedFromTheProjectOption() {
        assertTrue(BuildAegisApplication.isCliMode(new String[]{"--buildaegis.cli.project=/x", "--buildaegis.cli.fail-on=high"}));
        assertFalse(BuildAegisApplication.isCliMode(new String[]{"--server.port=9090"}));
        assertFalse(BuildAegisApplication.isCliMode(new String[]{}));
        assertFalse(BuildAegisApplication.isCliMode(null));
    }
}
