package com.riskscanner.dependencyriskanalyzer.controller;

import java.util.Map;
import com.riskscanner.dependencyriskanalyzer.dto.AiAskRequest;
import com.riskscanner.dependencyriskanalyzer.dto.AiSettingsRequest;
import com.riskscanner.dependencyriskanalyzer.dto.AiSettingsResponse;
import com.riskscanner.dependencyriskanalyzer.service.AIAnalysisService;
import com.riskscanner.dependencyriskanalyzer.service.AiSettingsService;
import com.riskscanner.dependencyriskanalyzer.service.ai.AiAskService;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

/**
 * API for configuring and validating the AI provider settings.
 *
 * <p>Settings are persisted locally (H2) and the API key is stored encrypted when
 * {@code buildaegis.encryption.secret} is configured.
 */
@RestController
@RequestMapping("/api/ai")
public class AiSettingsController {

    private final AiSettingsService aiSettingsService;
    private final AIAnalysisService aiAnalysisService;
    private final AiAskService aiAskService;

    public AiSettingsController(AiSettingsService aiSettingsService, AIAnalysisService aiAnalysisService,
                                @Lazy AiAskService aiAskService) {
        this.aiSettingsService = aiSettingsService;
        this.aiAnalysisService = aiAnalysisService;
        this.aiAskService = aiAskService;
    }

    /**
     * Returns the current AI configuration (provider/model) and whether it is configured.
     */
    @GetMapping("/settings")
    public AiSettingsResponse getSettings() {
        return aiSettingsService.getSettingsMasked();
    }

    /**
     * Saves the AI configuration.
     */
    @PutMapping("/settings")
    public AiSettingsResponse saveSettings(@RequestBody AiSettingsRequest request) {
        return aiSettingsService.saveSettings(request);
    }

    /**
     * Runs a small request against the provider to validate the configuration.
     */
    @PostMapping("/test-connection")
    public Map<String, String> testConnection() {
        aiAnalysisService.testConnection();
        return Map.of("status", "OK");
    }

    /**
     * Answers a question about one finding using only the finding's details from the request.
     */
    @PostMapping("/ask")
    public Map<String, String> ask(@RequestBody AiAskRequest request) {
        return Map.of("answer", aiAskService.ask(request));
    }
}
