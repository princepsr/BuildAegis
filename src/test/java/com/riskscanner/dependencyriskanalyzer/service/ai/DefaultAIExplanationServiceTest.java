package com.riskscanner.dependencyriskanalyzer.service.ai;

import com.riskscanner.dependencyriskanalyzer.dto.AiSettingsResponse;
import com.riskscanner.dependencyriskanalyzer.model.DependencyCoordinate;
import com.riskscanner.dependencyriskanalyzer.service.AiSettingsService;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultAIExplanationServiceTest {

    private final DependencyCoordinate dependency = new DependencyCoordinate("g", "a", "1.0", "MAVEN", "compile");

    private AIExplanationService.ExplanationResult explainWith(AiClient client) {
        AiClientFactory factory = mock(AiClientFactory.class);
        when(factory.create(anyString(), anyString(), anyString())).thenReturn(client);
        AiSettingsService settings = mock(AiSettingsService.class);
        when(settings.getSettings()).thenReturn(new AiSettingsResponse("openai", "m", null, "k", true, Instant.now()));
        when(settings.getApiKeyOrThrow()).thenReturn("k");
        var service = new DefaultAIExplanationService(factory, settings, new FallbackExplanationService());
        return service.explainRisk(dependency, null, "LOW", 10);
    }

    @Test
    void rateLimitFallsBackWithVisibleNotice() {
        AiClient client = mock(AiClient.class);
        when(client.analyzeDependencyRisk(any(), any())).thenThrow(new RuntimeException("HTTP 429 Too Many Requests"));

        var result = explainWith(client);

        assertFalse(result.fromAI());
        assertTrue(result.explanation().contains("429"));
    }

    @Test
    void unknownResultFromClientIsTreatedAsFailure() {
        AiClient client = mock(AiClient.class);
        when(client.analyzeDependencyRisk(any(), any())).thenReturn(
                new AiClient.DependencyRiskAnalysisResult(com.riskscanner.dependencyriskanalyzer.model.RiskLevel.UNKNOWN, null, "Gemini request failed: boom", java.util.List.of()));

        var result = explainWith(client);

        assertFalse(result.fromAI());
        assertTrue(result.explanation().contains("AI explanation unavailable"));
    }
}
